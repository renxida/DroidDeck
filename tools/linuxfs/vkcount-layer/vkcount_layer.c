/*
 * VK_LAYER_DROIDDECK_vkcount: per-frame Vulkan call counts and blocking-call times (experiment vk-count).
 *
 * Sits first in the game's layer list, so every time it reports covers everything below it: the WSI
 * layer, the libhybris compat layer and the driver. Every DROIDDECK_VKCOUNT_SECONDS (default 10) of
 * presents it prints one line to stderr, prefixed "droiddeck-vkcount:", with per-frame averages:
 *   counts:  submits, command buffers submitted, draws (direct + indirect), dispatches, pipeline binds,
 *            descriptor-set binds, render passes, barriers, copies
 *   ms:      time inside vkQueueSubmit, vkQueuePresentKHR (includes the WSI layer's CPU wait on the
 *            present fence), vkAcquireNextImageKHR, vkWaitForFences and queue/device idle waits
 *   passes:  passes begun per frame by render area and the first attachment's load/store ops (top 16 kinds)
 *   misc:    pipelines compiled and the ms spent compiling them, memory allocations
 *   memory:  device memory the app holds now (from vkAllocateMemory and vkFreeMemory), the host-visible
 *            part, and the peak; and the BC-compressed images it holds, with the size they take once the
 *            libhybris compat layer decodes them for a GPU without BC support. Those decoded copies are
 *            allocated below this layer, so they are an estimate from each image's format and extent.
 *            "bound" splits what the app has placed in that memory between images and buffers (each
 *            object's memory requirement at bind time); live minus bound is allocator slack.
 * The present, acquire and wait times are time the CPU spent blocked on the GPU or the compositor;
 * the rest of the frame interval is CPU work. Counters are relaxed atomics; nothing is held per call.
 * Block cap (experiment vk-blocks-64): with DROIDDECK_VK_BLOCK_MB set, a plain vkAllocateMemory (no dedicated,
 * import or export info) whose size is a power of two above the cap and at most 1 GiB fails with
 * VK_ERROR_OUT_OF_DEVICE_MEMORY. Those are the Vulkan Memory Allocator's new blocks (Godot uses VMA: 256 MiB
 * preferred, starting at 1/8 and doubling), and VMA answers a failed block by asking again at half the size, so
 * the game's blocks stop growing at the cap and less of its device memory sits allocated but unused. A real
 * resource of exactly 128 or 256 MiB would fail too; none of Slay the Spire 2's is that size. The reports
 * then end with how many such blocks were refused.
 * With DROIDDECK_VKCOUNT_FILE set, the same lines are appended to that file too. DROIDDECK_VKCOUNT_SECONDS=0
 * turns the reports off (the layer is then loaded only to pace frames).
 *
 * Frame pacing: with DROIDDECK_PACE_FPS set to a rate, each vkQueuePresentKHR returns no sooner than one
 * frame interval after the last, so the game starts its next frame (and reads input) after the wait, as a
 * game's own limiter would. It holds games that set their own frame rate and present mode after start
 * (Slay the Spire 2 re-applies its saved settings over Godot's --max-fps). DROIDDECK_PACE_FILE names a file
 * holding the rate in force now; the app rewrites it for the thermal governor and when the player has been
 * idle, and the layer checks it at most every 100 ms. A rate of 0 there means no pacing until it changes.
 */
#define _GNU_SOURCE
#include <vulkan/vk_layer.h>
#include <vulkan/vulkan.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <sys/stat.h>
#include <time.h>

#define EXPORT __attribute__((visibility("default")))
#define MAX_OBJECTS 16

typedef void *dispatch_key;
static dispatch_key key_of(const void *object) { return *(dispatch_key *)object; }

static uint64_t now_ns(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (uint64_t)t.tv_sec * 1000000000u + (uint64_t)t.tv_nsec;
}

/* ---- counters ---- */

#define COUNTERS(X) \
    X(frames) X(submits) X(command_buffers) X(draws) X(indirect_draws) X(dispatches) X(pipeline_binds) \
    X(set_binds) X(push_constants) X(passes) X(barriers) X(copies) X(pipelines) X(allocations) \
    X(submit_ns) X(present_ns) X(acquire_ns) X(fence_ns) X(idle_ns) X(pipeline_ns) X(pace_ns)
enum { 
#define X(name) C_##name,
    COUNTERS(X)
#undef X
    C_COUNT };
static const char *const counter_names[] = {
#define X(name) #name,
    COUNTERS(X)
#undef X
};
static uint64_t counters[C_COUNT];
static void add(int counter, uint64_t value) { __atomic_fetch_add(&counters[counter], value, __ATOMIC_RELAXED); }
static void bump(int counter) { add(counter, 1); }

/* ---- memory gauges: what is held now, not per window ---- */

/* Handle -> bytes, open addressing with linear probing and backward-shift deletion, under mem_lock. */
#define TABLE_SIZE (1u << 16)
struct entry { uint64_t handle, bytes, extra; };
struct table { struct entry slots[TABLE_SIZE]; uint32_t used; };
static uint32_t slot_of(uint64_t handle) { return (uint32_t)((handle * 0x9E3779B97F4A7C15ull) >> 48) & (TABLE_SIZE - 1); }
static int table_put(struct table *t, uint64_t handle, uint64_t bytes, uint64_t extra) {
    if (!handle || t->used >= TABLE_SIZE - TABLE_SIZE / 8) return 0;
    uint32_t i = slot_of(handle);
    while (t->slots[i].handle && t->slots[i].handle != handle) i = (i + 1) & (TABLE_SIZE - 1);
    if (!t->slots[i].handle) t->used++;
    t->slots[i] = (struct entry){handle, bytes, extra};
    return 1;
}
static int table_take(struct table *t, uint64_t handle, struct entry *out) {
    if (!handle) return 0;
    uint32_t i = slot_of(handle);
    while (t->slots[i].handle != handle) {
        if (!t->slots[i].handle) return 0;
        i = (i + 1) & (TABLE_SIZE - 1);
    }
    *out = t->slots[i];
    uint32_t hole = i;
    for (uint32_t j = (hole + 1) & (TABLE_SIZE - 1); t->slots[j].handle; j = (j + 1) & (TABLE_SIZE - 1)) {
        uint32_t home = slot_of(t->slots[j].handle);
        /* An entry stays put while its home slot lies cyclically in (hole, j]; otherwise it fills the hole. */
        int stays = hole <= j ? (hole < home && home <= j) : (hole < home || home <= j);
        if (!stays) { t->slots[hole] = t->slots[j]; hole = j; }
    }
    t->slots[hole].handle = 0;
    t->used--;
    return 1;
}
static uint64_t table_get(const struct table *t, uint64_t handle) { /* extra, or 0 when absent */
    if (!handle) return 0;
    for (uint32_t i = slot_of(handle); t->slots[i].handle; i = (i + 1) & (TABLE_SIZE - 1))
        if (t->slots[i].handle == handle) return t->slots[i].extra;
    return 0;
}
static struct table memory_table, image_table, bound_table;
static pthread_mutex_t mem_lock = PTHREAD_MUTEX_INITIALIZER;
static uint64_t live_bytes, host_bytes, peak_bytes, live_allocations, untracked_allocations;
static uint64_t bc_bytes, bc_decoded_bytes, bc_images;
/* bound_table: extra is 1 for an image, 2 for a buffer. */
static uint64_t bound_image_bytes, bound_images, bound_buffer_bytes, bound_buffers;
static void unbind(uint64_t handle) { /* mem_lock held */
    struct entry gone;
    if (!table_take(&bound_table, handle, &gone)) return;
    if (gone.extra == 1) { bound_image_bytes -= gone.bytes; bound_images--; }
    else { bound_buffer_bytes -= gone.bytes; bound_buffers--; }
}

static uint64_t block_cap, blocks_refused;

/* The allocation is one of VMA's plain blocks over the cap: a power of two above it, at most 1 GiB, with
 * nothing in pNext but allocate flags or a priority. */
static int refuse_block(const VkMemoryAllocateInfo *info) {
    uint64_t size = info->allocationSize;
    if (!block_cap || size <= block_cap || size > (1ull << 30) || (size & (size - 1))) return 0;
    for (const VkBaseInStructure *p = info->pNext; p; p = p->pNext)
        if (p->sType != VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_FLAGS_INFO &&
            p->sType != VK_STRUCTURE_TYPE_MEMORY_PRIORITY_ALLOCATE_INFO_EXT) return 0;
    return 1;
}

static uint64_t window_ns = 10000000000ull, window_start;
static int reports_off;
static FILE *extra;
static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;

/* ---- render passes by size and first attachment's load/store ops ----
 * Each pass begun in the window is filed by its render area and the load and store op of its first colour
 * attachment (depth when it has none). On a tiler a pass that loads or stores reads or writes its whole area
 * in main memory, so full-screen load/store passes are the ones worth merging; small viewports are not. */
enum { OP_UNKNOWN = 0 };
static const char *load_name(int op) {
    switch (op) { case 1 + VK_ATTACHMENT_LOAD_OP_LOAD: return "load"; case 1 + VK_ATTACHMENT_LOAD_OP_CLEAR: return "clear";
                  case 1 + VK_ATTACHMENT_LOAD_OP_DONT_CARE: return "dontcare"; case 0: return "?"; default: return "none"; }
}
static const char *store_name(int op) {
    switch (op) { case 1 + VK_ATTACHMENT_STORE_OP_STORE: return "store"; case 1 + VK_ATTACHMENT_STORE_OP_DONT_CARE: return "dontcare";
                  case 0: return "?"; default: return "none"; }
}
static int load_code(VkAttachmentLoadOp op) { return op == VK_ATTACHMENT_LOAD_OP_LOAD || op == VK_ATTACHMENT_LOAD_OP_CLEAR ||
                                                     op == VK_ATTACHMENT_LOAD_OP_DONT_CARE ? 1 + (int)op : 9; }
static int store_code(VkAttachmentStoreOp op) { return op == VK_ATTACHMENT_STORE_OP_STORE ||
                                                       op == VK_ATTACHMENT_STORE_OP_DONT_CARE ? 1 + (int)op : 9; }
#define PASS_KINDS 16
struct pass_kind { uint32_t w, h; uint8_t load, store; uint64_t count; };
static struct pass_kind pass_kinds[PASS_KINDS];
static uint64_t passes_unfiled;
static struct table render_pass_table; /* render pass -> extra = load code * 16 + store code (under mem_lock) */
static pthread_mutex_t pass_lock = PTHREAD_MUTEX_INITIALIZER;
static void file_pass(uint32_t w, uint32_t h, int load, int store) {
    if (reports_off) return;
    pthread_mutex_lock(&pass_lock);
    int i = 0;
    for (; i < PASS_KINDS && pass_kinds[i].count; i++)
        if (pass_kinds[i].w == w && pass_kinds[i].h == h && pass_kinds[i].load == load && pass_kinds[i].store == store) break;
    if (i < PASS_KINDS) {
        if (!pass_kinds[i].count) pass_kinds[i] = (struct pass_kind){w, h, (uint8_t)load, (uint8_t)store, 0};
        pass_kinds[i].count++;
    } else passes_unfiled++;
    pthread_mutex_unlock(&pass_lock);
}
static int pass_order(const void *a, const void *b) {
    const struct pass_kind *x = a, *y = b;
    return x->count < y->count ? 1 : x->count > y->count ? -1 : 0;
}
/* Appends " | passes by area, load/store: WxH load/store N, ..." (per frame) and clears the window's kinds. */
static int pass_summary(char *out, size_t room, double frames) {
    struct pass_kind k[PASS_KINDS]; uint64_t unfiled;
    pthread_mutex_lock(&pass_lock);
    memcpy(k, pass_kinds, sizeof k); memset(pass_kinds, 0, sizeof pass_kinds);
    unfiled = passes_unfiled; passes_unfiled = 0;
    pthread_mutex_unlock(&pass_lock);
    int used = 0;
    while (used < PASS_KINDS && k[used].count) used++;
    if (!used) return 0;
    qsort(k, used, sizeof *k, pass_order);
    int n = snprintf(out, room, " | passes by area, load/store per frame:");
    for (int i = 0; i < used && n > 0 && (size_t)n < room; i++)
        n += snprintf(out + n, room - n, "%s %ux%u %s/%s %.1f", i ? "," : "", k[i].w, k[i].h, load_name(k[i].load),
                      store_name(k[i].store), k[i].count / frames);
    if (unfiled && n > 0 && (size_t)n < room) n += snprintf(out + n, room - n, ", other %.1f", unfiled / frames);
    return n > 0 && (size_t)n < room ? n : 0;
}

static void report(uint64_t now, const char *why) {
    uint64_t snapshot[C_COUNT];
    for (int i = 0; i < C_COUNT; i++) snapshot[i] = __atomic_exchange_n(&counters[i], 0, __ATOMIC_RELAXED);
    struct { uint64_t live, host, peak, allocations, untracked, bc, bc_decoded, bc_images, image, images, buffer,
             buffers; } mem;
    pthread_mutex_lock(&mem_lock);
    mem.live = live_bytes; mem.host = host_bytes; mem.peak = peak_bytes; mem.allocations = live_allocations;
    mem.untracked = untracked_allocations; mem.bc = bc_bytes; mem.bc_decoded = bc_decoded_bytes;
    mem.bc_images = bc_images;
    mem.image = bound_image_bytes; mem.images = bound_images; mem.buffer = bound_buffer_bytes; mem.buffers = bound_buffers;
    pthread_mutex_unlock(&mem_lock);
    double seconds = (double)(now - window_start) / 1e9;
    double frames = snapshot[C_frames] ? (double)snapshot[C_frames] : 1.0;
    char line[2200];
    int n = snprintf(line, sizeof line,
        "droiddeck-vkcount: %s %.1fs %llu frames %.1f fps | per frame: %.1f submits %.1f cmdbufs %.0f draws "
        "(%.0f indirect) %.1f dispatches %.0f pipeline-binds %.0f set-binds %.0f push-consts %.1f passes "
        "%.1f barriers %.1f copies | ms/frame: submit %.2f present %.2f acquire %.2f fence-wait %.2f idle %.2f pace %.2f "
        "| pipelines %llu in %.0f ms, allocations %llu | memory %.1f MB live (%.1f MB host-visible, peak %.1f MB) "
        "in %llu allocations%s, bound %.1f MB in %llu images and %.1f MB in %llu buffers, "
        "BC textures %.1f MB -> %.1f MB decoded (%llu images)",
        why, seconds, (unsigned long long)snapshot[C_frames], snapshot[C_frames] / (seconds > 0 ? seconds : 1),
        snapshot[C_submits] / frames, snapshot[C_command_buffers] / frames,
        (snapshot[C_draws] + snapshot[C_indirect_draws]) / frames, snapshot[C_indirect_draws] / frames,
        snapshot[C_dispatches] / frames, snapshot[C_pipeline_binds] / frames, snapshot[C_set_binds] / frames,
        snapshot[C_push_constants] / frames, snapshot[C_passes] / frames, snapshot[C_barriers] / frames,
        snapshot[C_copies] / frames, snapshot[C_submit_ns] / 1e6 / frames, snapshot[C_present_ns] / 1e6 / frames,
        snapshot[C_acquire_ns] / 1e6 / frames, snapshot[C_fence_ns] / 1e6 / frames, snapshot[C_idle_ns] / 1e6 / frames,
        snapshot[C_pace_ns] / 1e6 / frames, (unsigned long long)snapshot[C_pipelines], snapshot[C_pipeline_ns] / 1e6,
        (unsigned long long)snapshot[C_allocations], mem.live / 1048576.0, mem.host / 1048576.0,
        mem.peak / 1048576.0, (unsigned long long)mem.allocations, mem.untracked ? " (some untracked)" : "",
        mem.image / 1048576.0, (unsigned long long)mem.images, mem.buffer / 1048576.0, (unsigned long long)mem.buffers,
        mem.bc / 1048576.0, mem.bc_decoded / 1048576.0, (unsigned long long)mem.bc_images);
    (void)counter_names;
    if (n <= 0 || n >= (int)sizeof line) return;
    if (block_cap)
        n += snprintf(line + n, sizeof line - n, ", blocks capped at %llu MB (%llu bigger refused)",
                      (unsigned long long)(block_cap >> 20),
                      (unsigned long long)__atomic_load_n(&blocks_refused, __ATOMIC_RELAXED));
    n += pass_summary(line + n, sizeof line - n - 2, frames);
    if (n >= (int)sizeof line - 1) n = (int)sizeof line - 2;
    line[n] = '\n';
    line[n + 1] = 0;
    fputs(line, stderr);
    if (extra) { fputs(line, extra); fflush(extra); }
    window_start = now;
}

static void frame_done(void) {
    bump(C_frames);
    uint64_t now = now_ns();
    if (reports_off || now - __atomic_load_n(&window_start, __ATOMIC_RELAXED) < window_ns) return;
    if (pthread_mutex_trylock(&lock)) return;
    if (now - window_start >= window_ns) report(now, "window");
    pthread_mutex_unlock(&lock);
}

/* ---- dispatch tables ---- */

#define DEVICE_FUNCTIONS(X) \
    X(DestroyDevice) X(QueueSubmit) X(QueueSubmit2) X(QueueSubmit2KHR) X(QueuePresentKHR) \
    X(AcquireNextImageKHR) X(AcquireNextImage2KHR) X(WaitForFences) X(QueueWaitIdle) X(DeviceWaitIdle) \
    X(CreateGraphicsPipelines) X(CreateComputePipelines) X(AllocateMemory) X(FreeMemory) X(CreateImage) \
    X(DestroyImage) X(DestroyBuffer) X(BindImageMemory) X(BindImageMemory2) X(BindImageMemory2KHR) \
    X(BindBufferMemory) X(BindBufferMemory2) X(BindBufferMemory2KHR) X(GetImageMemoryRequirements) \
    X(GetBufferMemoryRequirements) \
    X(CmdDraw) X(CmdDrawIndexed) X(CmdDrawIndirect) X(CmdDrawIndexedIndirect) X(CmdDrawIndirectCount) \
    X(CmdDrawIndexedIndirectCount) X(CmdDispatch) X(CmdDispatchIndirect) X(CmdBindPipeline) \
    X(CmdBindDescriptorSets) X(CmdPushConstants) X(CmdBeginRenderPass) X(CmdBeginRenderPass2) \
    X(CmdBeginRenderPass2KHR) X(CmdBeginRendering) X(CmdBeginRenderingKHR) X(CmdPipelineBarrier) \
    X(CmdPipelineBarrier2) X(CmdPipelineBarrier2KHR) X(CmdCopyBuffer) X(CmdCopyBufferToImage) X(CmdCopyImage) \
    X(CmdBlitImage) X(CreateRenderPass) X(CreateRenderPass2) X(CreateRenderPass2KHR) X(DestroyRenderPass)

struct device_data {
    dispatch_key key;
    VkDevice handle;
    PFN_vkGetDeviceProcAddr next;
    uint32_t host_visible_types; /* bit i: memory type i is host-visible */
#define X(name) PFN_vk##name name;
    DEVICE_FUNCTIONS(X)
#undef X
};
struct instance_data {
    dispatch_key key;
    VkInstance handle;
    PFN_vkGetInstanceProcAddr next;
    PFN_vkDestroyInstance destroy;
};
static struct device_data devices[MAX_OBJECTS];
static struct instance_data instances[MAX_OBJECTS];

/* Entries are written once under the lock before the device is handed out, so readers need none. */
static const struct device_data *device_of(const void *object) {
    dispatch_key key = key_of(object);
    for (int i = 0; i < MAX_OBJECTS; i++)
        if (devices[i].key == key && devices[i].handle) return &devices[i];
    return NULL;
}
static const struct instance_data *instance_of(const void *object) {
    dispatch_key key = key_of(object);
    for (int i = 0; i < MAX_OBJECTS; i++)
        if (instances[i].key == key && instances[i].handle) return &instances[i];
    return NULL;
}

#define NEXT(object, name) const struct device_data *d_ = device_of(object); PFN_vk##name next_ = d_ ? d_->name : NULL

/* ---- instance ---- */

static VkResult VKAPI_CALL create_instance(const VkInstanceCreateInfo *info, const VkAllocationCallbacks *allocator,
                                           VkInstance *instance) {
    VkLayerInstanceCreateInfo *chain = (VkLayerInstanceCreateInfo *)info->pNext;
    while (chain && !(chain->sType == VK_STRUCTURE_TYPE_LOADER_INSTANCE_CREATE_INFO && chain->function == VK_LAYER_LINK_INFO))
        chain = (VkLayerInstanceCreateInfo *)chain->pNext;
    if (!chain || !chain->u.pLayerInfo) return VK_ERROR_INITIALIZATION_FAILED;
    PFN_vkGetInstanceProcAddr next = chain->u.pLayerInfo->pfnNextGetInstanceProcAddr;
    chain->u.pLayerInfo = chain->u.pLayerInfo->pNext;
    PFN_vkCreateInstance create = (PFN_vkCreateInstance)next(NULL, "vkCreateInstance");
    if (!create) return VK_ERROR_INITIALIZATION_FAILED;
    VkResult result = create(info, allocator, instance);
    if (result != VK_SUCCESS) return result;
    pthread_mutex_lock(&lock);
    for (int i = 0; i < MAX_OBJECTS; i++)
        if (!instances[i].handle) {
            instances[i] = (struct instance_data){key_of(*instance), *instance, next,
                                                  (PFN_vkDestroyInstance)next(*instance, "vkDestroyInstance")};
            break;
        }
    pthread_mutex_unlock(&lock);
    return result;
}

static void VKAPI_CALL destroy_instance(VkInstance instance, const VkAllocationCallbacks *allocator) {
    const struct instance_data *data = instance_of(instance);
    PFN_vkDestroyInstance destroy = data ? data->destroy : NULL;
    pthread_mutex_lock(&lock);
    for (int i = 0; i < MAX_OBJECTS; i++)
        if (instances[i].handle == instance) memset(&instances[i], 0, sizeof instances[i]);
    pthread_mutex_unlock(&lock);
    if (destroy) destroy(instance, allocator);
}

static VkResult VKAPI_CALL create_device(VkPhysicalDevice physical, const VkDeviceCreateInfo *info,
                                         const VkAllocationCallbacks *allocator, VkDevice *device) {
    VkLayerDeviceCreateInfo *chain = (VkLayerDeviceCreateInfo *)info->pNext;
    while (chain && !(chain->sType == VK_STRUCTURE_TYPE_LOADER_DEVICE_CREATE_INFO && chain->function == VK_LAYER_LINK_INFO))
        chain = (VkLayerDeviceCreateInfo *)chain->pNext;
    if (!chain || !chain->u.pLayerInfo) return VK_ERROR_INITIALIZATION_FAILED;
    PFN_vkGetInstanceProcAddr next_gipa = chain->u.pLayerInfo->pfnNextGetInstanceProcAddr;
    PFN_vkGetDeviceProcAddr next_gdpa = chain->u.pLayerInfo->pfnNextGetDeviceProcAddr;
    chain->u.pLayerInfo = chain->u.pLayerInfo->pNext;
    const struct instance_data *owner = instance_of(physical);
    PFN_vkCreateDevice create = (PFN_vkCreateDevice)next_gipa(owner ? owner->handle : NULL, "vkCreateDevice");
    if (!create) return VK_ERROR_INITIALIZATION_FAILED;
    VkResult result = create(physical, info, allocator, device);
    if (result != VK_SUCCESS) return result;
    struct device_data data = {.key = key_of(*device), .handle = *device, .next = next_gdpa};
    PFN_vkGetPhysicalDeviceMemoryProperties properties =
        (PFN_vkGetPhysicalDeviceMemoryProperties)next_gipa(owner ? owner->handle : NULL, "vkGetPhysicalDeviceMemoryProperties");
    if (properties) {
        VkPhysicalDeviceMemoryProperties memory;
        properties(physical, &memory);
        for (uint32_t i = 0; i < memory.memoryTypeCount && i < 32; i++)
            if (memory.memoryTypes[i].propertyFlags & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) data.host_visible_types |= 1u << i;
    }
#define X(name) data.name = (PFN_vk##name)next_gdpa(*device, "vk" #name);
    DEVICE_FUNCTIONS(X)
#undef X
    pthread_mutex_lock(&lock);
    for (int i = 0; i < MAX_OBJECTS; i++)
        if (!devices[i].handle) { devices[i] = data; break; }
    if (!window_start) window_start = now_ns();
    pthread_mutex_unlock(&lock);
    return result;
}

static void VKAPI_CALL destroy_device(VkDevice device, const VkAllocationCallbacks *allocator) {
    NEXT(device, DestroyDevice);
    pthread_mutex_lock(&lock);
    if (!reports_off && __atomic_load_n(&counters[C_frames], __ATOMIC_RELAXED)) report(now_ns(), "final");
    for (int i = 0; i < MAX_OBJECTS; i++)
        if (devices[i].handle == device) memset(&devices[i], 0, sizeof devices[i]);
    pthread_mutex_unlock(&lock);
    if (next_) next_(device, allocator);
}

/* ---- timed calls ---- */

#define TIMED(counter, call) do { uint64_t t0_ = now_ns(); result = call; add(counter, now_ns() - t0_); } while (0)

static VkResult VKAPI_CALL queue_submit(VkQueue queue, uint32_t count, const VkSubmitInfo *submits, VkFence fence) {
    NEXT(queue, QueueSubmit);
    VkResult result;
    bump(C_submits);
    for (uint32_t i = 0; i < count; i++) add(C_command_buffers, submits[i].commandBufferCount);
    TIMED(C_submit_ns, next_(queue, count, submits, fence));
    return result;
}

static VkResult submit2(PFN_vkQueueSubmit2 next_, VkQueue queue, uint32_t count, const VkSubmitInfo2 *submits,
                        VkFence fence) {
    VkResult result;
    bump(C_submits);
    for (uint32_t i = 0; i < count; i++) add(C_command_buffers, submits[i].commandBufferInfoCount);
    TIMED(C_submit_ns, next_(queue, count, submits, fence));
    return result;
}
static VkResult VKAPI_CALL queue_submit2(VkQueue queue, uint32_t count, const VkSubmitInfo2 *submits, VkFence fence) {
    NEXT(queue, QueueSubmit2);
    return submit2(next_, queue, count, submits, fence);
}
static VkResult VKAPI_CALL queue_submit2_khr(VkQueue queue, uint32_t count, const VkSubmitInfo2 *submits, VkFence fence) {
    NEXT(queue, QueueSubmit2KHR);
    return submit2(next_, queue, count, submits, fence);
}

/* ---- frame pacing ---- */

#define PACE_MAX_FPS 240
#define PACE_CHECK_NS 100000000ull
static int pace_base, pace_fps;
static const char *pace_file;
static uint64_t pace_next_ns, pace_checked_ns;
static struct timespec pace_mtime;
static pthread_mutex_t pace_lock = PTHREAD_MUTEX_INITIALIZER;

static int pace_rate(const char *text, int fallback) {
    char *end;
    long value = text ? strtol(text, &end, 10) : -1;
    if (!text || end == text || value < 0 || value > PACE_MAX_FPS) return fallback;
    return (int)value;
}

/* The rate in force: the file's when it holds one, else DROIDDECK_PACE_FPS. Called under pace_lock. */
static int pace_current(uint64_t now) {
    if (!pace_file || now - pace_checked_ns < PACE_CHECK_NS) return pace_fps;
    pace_checked_ns = now;
    struct stat st;
    if (stat(pace_file, &st)) { pace_fps = pace_base; return pace_fps; }
    if (st.st_mtim.tv_sec == pace_mtime.tv_sec && st.st_mtim.tv_nsec == pace_mtime.tv_nsec) return pace_fps;
    FILE *f = fopen(pace_file, "re");
    if (!f) return pace_fps;
    char text[16] = {0};
    size_t n = fread(text, 1, sizeof text - 1, f);
    fclose(f);
    text[n] = 0;
    pace_mtime = st.st_mtim;
    pace_fps = pace_rate(text, pace_base);
    return pace_fps;
}

static void pace(void) {
    if (!pace_base) return;
    pthread_mutex_lock(&pace_lock);
    uint64_t now = now_ns();
    int fps = pace_current(now);
    if (fps > 0) {
        uint64_t interval = 1000000000ull / (uint64_t)fps, start = now;
        if (pace_next_ns && now < pace_next_ns) {
            struct timespec until = {(time_t)(pace_next_ns / 1000000000ull), (long)(pace_next_ns % 1000000000ull)};
            while (clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME, &until, NULL) == EINTR) {}
            start = pace_next_ns;
            add(C_pace_ns, start - now);
        } else if (pace_next_ns && now - pace_next_ns < interval) {
            start = pace_next_ns;  /* a little late: keep the cadence rather than drift */
        }
        pace_next_ns = start + interval;
    } else {
        pace_next_ns = 0;
    }
    pthread_mutex_unlock(&pace_lock);
}

static VkResult VKAPI_CALL queue_present(VkQueue queue, const VkPresentInfoKHR *info) {
    NEXT(queue, QueuePresentKHR);
    VkResult result;
    TIMED(C_present_ns, next_(queue, info));
    frame_done();
    pace();
    return result;
}

static VkResult VKAPI_CALL acquire(VkDevice device, VkSwapchainKHR swapchain, uint64_t timeout, VkSemaphore semaphore,
                                   VkFence fence, uint32_t *index) {
    NEXT(device, AcquireNextImageKHR);
    VkResult result;
    TIMED(C_acquire_ns, next_(device, swapchain, timeout, semaphore, fence, index));
    return result;
}
static VkResult VKAPI_CALL acquire2(VkDevice device, const VkAcquireNextImageInfoKHR *info, uint32_t *index) {
    NEXT(device, AcquireNextImage2KHR);
    VkResult result;
    TIMED(C_acquire_ns, next_(device, info, index));
    return result;
}

static VkResult VKAPI_CALL wait_fences(VkDevice device, uint32_t count, const VkFence *fences, VkBool32 all,
                                       uint64_t timeout) {
    NEXT(device, WaitForFences);
    VkResult result;
    TIMED(C_fence_ns, next_(device, count, fences, all, timeout));
    return result;
}
static VkResult VKAPI_CALL queue_wait_idle(VkQueue queue) {
    NEXT(queue, QueueWaitIdle);
    VkResult result;
    TIMED(C_idle_ns, next_(queue));
    return result;
}
static VkResult VKAPI_CALL device_wait_idle(VkDevice device) {
    NEXT(device, DeviceWaitIdle);
    VkResult result;
    TIMED(C_idle_ns, next_(device));
    return result;
}

static VkResult VKAPI_CALL graphics_pipelines(VkDevice device, VkPipelineCache cache, uint32_t count,
                                              const VkGraphicsPipelineCreateInfo *infos,
                                              const VkAllocationCallbacks *allocator, VkPipeline *pipelines) {
    NEXT(device, CreateGraphicsPipelines);
    VkResult result;
    add(C_pipelines, count);
    TIMED(C_pipeline_ns, next_(device, cache, count, infos, allocator, pipelines));
    return result;
}
static VkResult VKAPI_CALL compute_pipelines(VkDevice device, VkPipelineCache cache, uint32_t count,
                                             const VkComputePipelineCreateInfo *infos,
                                             const VkAllocationCallbacks *allocator, VkPipeline *pipelines) {
    NEXT(device, CreateComputePipelines);
    VkResult result;
    add(C_pipelines, count);
    TIMED(C_pipeline_ns, next_(device, cache, count, infos, allocator, pipelines));
    return result;
}
static VkResult VKAPI_CALL allocate_memory(VkDevice device, const VkMemoryAllocateInfo *info,
                                           const VkAllocationCallbacks *allocator, VkDeviceMemory *memory) {
    NEXT(device, AllocateMemory);
    bump(C_allocations);
    if (refuse_block(info)) {
        __atomic_add_fetch(&blocks_refused, 1, __ATOMIC_RELAXED);
        return VK_ERROR_OUT_OF_DEVICE_MEMORY;
    }
    VkResult result = next_(device, info, allocator, memory);
    if (result != VK_SUCCESS) return result;
    uint64_t bytes = info->allocationSize;
    int host = info->memoryTypeIndex < 32 && d_ && (d_->host_visible_types >> info->memoryTypeIndex & 1);
    pthread_mutex_lock(&mem_lock);
    if (table_put(&memory_table, (uint64_t)*memory, bytes, (uint64_t)host)) {
        live_bytes += bytes;
        if (host) host_bytes += bytes;
        if (live_bytes > peak_bytes) peak_bytes = live_bytes;
        live_allocations++;
    } else {
        untracked_allocations++;
    }
    pthread_mutex_unlock(&mem_lock);
    return result;
}
static void VKAPI_CALL free_memory(VkDevice device, VkDeviceMemory memory, const VkAllocationCallbacks *allocator) {
    NEXT(device, FreeMemory);
    struct entry gone;
    pthread_mutex_lock(&mem_lock);
    if (table_take(&memory_table, (uint64_t)memory, &gone)) {
        live_bytes -= gone.bytes;
        if (gone.extra) host_bytes -= gone.bytes;
        live_allocations--;
    }
    pthread_mutex_unlock(&mem_lock);
    if (next_) next_(device, memory, allocator);
}

/* Bytes per texel of the format the libhybris compat layer decodes each BC format into (bc_image.c);
 * BC1 RGB and BC6H count their four-channel form. 0 for anything that is not BC. */
static unsigned bc_decoded_texel(VkFormat format) {
    switch (format) {
    case VK_FORMAT_BC4_UNORM_BLOCK: case VK_FORMAT_BC4_SNORM_BLOCK: return 2;
    case VK_FORMAT_BC6H_UFLOAT_BLOCK: case VK_FORMAT_BC6H_SFLOAT_BLOCK: return 8;
    default: return format >= VK_FORMAT_BC1_RGB_UNORM_BLOCK && format <= VK_FORMAT_BC7_SRGB_BLOCK ? 4 : 0;
    }
}
static VkResult VKAPI_CALL create_image(VkDevice device, const VkImageCreateInfo *info,
                                        const VkAllocationCallbacks *allocator, VkImage *image) {
    NEXT(device, CreateImage);
    VkResult result = next_(device, info, allocator, image);
    unsigned texel = bc_decoded_texel(info->format);
    if (result != VK_SUCCESS || !texel) return result;
    unsigned block = info->format <= VK_FORMAT_BC1_RGBA_SRGB_BLOCK || info->format == VK_FORMAT_BC4_UNORM_BLOCK ||
        info->format == VK_FORMAT_BC4_SNORM_BLOCK ? 8 : 16;
    uint64_t compressed = 0, decoded = 0;
    for (uint32_t mip = 0; mip < info->mipLevels && mip < 32; mip++) {
        uint64_t w = info->extent.width >> mip, h = info->extent.height >> mip;
        if (!w) w = 1;
        if (!h) h = 1;
        compressed += (w + 3) / 4 * ((h + 3) / 4) * block * info->arrayLayers;
        decoded += w * h * texel * info->arrayLayers;
    }
    pthread_mutex_lock(&mem_lock);
    if (table_put(&image_table, (uint64_t)*image, compressed, decoded)) {
        bc_bytes += compressed;
        bc_decoded_bytes += decoded;
        bc_images++;
    }
    pthread_mutex_unlock(&mem_lock);
    return result;
}
static void VKAPI_CALL destroy_image(VkDevice device, VkImage image, const VkAllocationCallbacks *allocator) {
    NEXT(device, DestroyImage);
    struct entry gone;
    pthread_mutex_lock(&mem_lock);
    if (table_take(&image_table, (uint64_t)image, &gone)) {
        bc_bytes -= gone.bytes;
        bc_decoded_bytes -= gone.extra;
        bc_images--;
    }
    unbind((uint64_t)image);
    pthread_mutex_unlock(&mem_lock);
    if (next_) next_(device, image, allocator);
}
static void VKAPI_CALL destroy_buffer(VkDevice device, VkBuffer buffer, const VkAllocationCallbacks *allocator) {
    NEXT(device, DestroyBuffer);
    pthread_mutex_lock(&mem_lock);
    unbind((uint64_t)buffer);
    pthread_mutex_unlock(&mem_lock);
    if (next_) next_(device, buffer, allocator);
}

/* Binding records the object's memory requirement; the lookup runs before the bind, outside the lock. */
static void bound(const struct device_data *d, uint64_t handle, int is_image) {
    if (!d || !handle) return;
    VkMemoryRequirements need = {0};
    if (is_image && d->GetImageMemoryRequirements) d->GetImageMemoryRequirements(d->handle, (VkImage)handle, &need);
    else if (!is_image && d->GetBufferMemoryRequirements)
        d->GetBufferMemoryRequirements(d->handle, (VkBuffer)handle, &need);
    else return;
    pthread_mutex_lock(&mem_lock);
    unbind(handle);
    if (table_put(&bound_table, handle, need.size, is_image ? 1 : 2)) {
        if (is_image) { bound_image_bytes += need.size; bound_images++; }
        else { bound_buffer_bytes += need.size; bound_buffers++; }
    } else {
        untracked_allocations++;
    }
    pthread_mutex_unlock(&mem_lock);
}
static VkResult VKAPI_CALL bind_image(VkDevice device, VkImage image, VkDeviceMemory memory, VkDeviceSize offset) {
    NEXT(device, BindImageMemory);
    VkResult result = next_(device, image, memory, offset);
    if (result == VK_SUCCESS) bound(d_, (uint64_t)image, 1);
    return result;
}
static VkResult bind_images(const struct device_data *d, PFN_vkBindImageMemory2 next_, VkDevice device, uint32_t n,
                            const VkBindImageMemoryInfo *infos) {
    VkResult result = next_(device, n, infos);
    if (result == VK_SUCCESS) for (uint32_t i = 0; i < n; i++) bound(d, (uint64_t)infos[i].image, 1);
    return result;
}
static VkResult VKAPI_CALL bind_image2(VkDevice device, uint32_t n, const VkBindImageMemoryInfo *infos) {
    NEXT(device, BindImageMemory2);
    return bind_images(d_, next_, device, n, infos);
}
static VkResult VKAPI_CALL bind_image2_khr(VkDevice device, uint32_t n, const VkBindImageMemoryInfo *infos) {
    NEXT(device, BindImageMemory2KHR);
    return bind_images(d_, next_, device, n, infos);
}
static VkResult VKAPI_CALL bind_buffer(VkDevice device, VkBuffer buffer, VkDeviceMemory memory, VkDeviceSize offset) {
    NEXT(device, BindBufferMemory);
    VkResult result = next_(device, buffer, memory, offset);
    if (result == VK_SUCCESS) bound(d_, (uint64_t)buffer, 0);
    return result;
}
static VkResult bind_buffers(const struct device_data *d, PFN_vkBindBufferMemory2 next_, VkDevice device, uint32_t n,
                             const VkBindBufferMemoryInfo *infos) {
    VkResult result = next_(device, n, infos);
    if (result == VK_SUCCESS) for (uint32_t i = 0; i < n; i++) bound(d, (uint64_t)infos[i].buffer, 0);
    return result;
}
static VkResult VKAPI_CALL bind_buffer2(VkDevice device, uint32_t n, const VkBindBufferMemoryInfo *infos) {
    NEXT(device, BindBufferMemory2);
    return bind_buffers(d_, next_, device, n, infos);
}
static VkResult VKAPI_CALL bind_buffer2_khr(VkDevice device, uint32_t n, const VkBindBufferMemoryInfo *infos) {
    NEXT(device, BindBufferMemory2KHR);
    return bind_buffers(d_, next_, device, n, infos);
}

/* ---- counted commands ---- */

static void VKAPI_CALL cmd_draw(VkCommandBuffer cb, uint32_t a, uint32_t b, uint32_t c, uint32_t d) {
    NEXT(cb, CmdDraw); bump(C_draws); next_(cb, a, b, c, d);
}
static void VKAPI_CALL cmd_draw_indexed(VkCommandBuffer cb, uint32_t a, uint32_t b, uint32_t c, int32_t d, uint32_t e) {
    NEXT(cb, CmdDrawIndexed); bump(C_draws); next_(cb, a, b, c, d, e);
}
static void VKAPI_CALL cmd_draw_indirect(VkCommandBuffer cb, VkBuffer buffer, VkDeviceSize offset, uint32_t n,
                                         uint32_t stride) {
    NEXT(cb, CmdDrawIndirect); bump(C_indirect_draws); next_(cb, buffer, offset, n, stride);
}
static void VKAPI_CALL cmd_draw_indexed_indirect(VkCommandBuffer cb, VkBuffer buffer, VkDeviceSize offset, uint32_t n,
                                                 uint32_t stride) {
    NEXT(cb, CmdDrawIndexedIndirect); bump(C_indirect_draws); next_(cb, buffer, offset, n, stride);
}
static void VKAPI_CALL cmd_draw_indirect_count(VkCommandBuffer cb, VkBuffer buffer, VkDeviceSize offset,
                                               VkBuffer counts, VkDeviceSize count_offset, uint32_t n, uint32_t stride) {
    NEXT(cb, CmdDrawIndirectCount); bump(C_indirect_draws); next_(cb, buffer, offset, counts, count_offset, n, stride);
}
static void VKAPI_CALL cmd_draw_indexed_indirect_count(VkCommandBuffer cb, VkBuffer buffer, VkDeviceSize offset,
                                                       VkBuffer counts, VkDeviceSize count_offset, uint32_t n,
                                                       uint32_t stride) {
    NEXT(cb, CmdDrawIndexedIndirectCount); bump(C_indirect_draws);
    next_(cb, buffer, offset, counts, count_offset, n, stride);
}
static void VKAPI_CALL cmd_dispatch(VkCommandBuffer cb, uint32_t x, uint32_t y, uint32_t z) {
    NEXT(cb, CmdDispatch); bump(C_dispatches); next_(cb, x, y, z);
}
static void VKAPI_CALL cmd_dispatch_indirect(VkCommandBuffer cb, VkBuffer buffer, VkDeviceSize offset) {
    NEXT(cb, CmdDispatchIndirect); bump(C_dispatches); next_(cb, buffer, offset);
}
static void VKAPI_CALL cmd_bind_pipeline(VkCommandBuffer cb, VkPipelineBindPoint point, VkPipeline pipeline) {
    NEXT(cb, CmdBindPipeline); bump(C_pipeline_binds); next_(cb, point, pipeline);
}
static void VKAPI_CALL cmd_bind_sets(VkCommandBuffer cb, VkPipelineBindPoint point, VkPipelineLayout layout,
                                     uint32_t first, uint32_t count, const VkDescriptorSet *sets, uint32_t dynamic_count,
                                     const uint32_t *dynamic) {
    NEXT(cb, CmdBindDescriptorSets); add(C_set_binds, count);
    next_(cb, point, layout, first, count, sets, dynamic_count, dynamic);
}
static void VKAPI_CALL cmd_push_constants(VkCommandBuffer cb, VkPipelineLayout layout, VkShaderStageFlags stages,
                                          uint32_t offset, uint32_t size, const void *values) {
    NEXT(cb, CmdPushConstants); bump(C_push_constants); next_(cb, layout, stages, offset, size, values);
}
static int pass_ops(VkRenderPass pass) {
    pthread_mutex_lock(&mem_lock);
    int ops = (int)table_get(&render_pass_table, (uint64_t)pass);
    pthread_mutex_unlock(&mem_lock);
    return ops;
}
static void file_begin(const VkRenderPassBeginInfo *info) {
    if (!info || reports_off) return;
    int ops = pass_ops(info->renderPass);
    file_pass(info->renderArea.extent.width, info->renderArea.extent.height, ops / 16, ops % 16);
}
static void file_rendering(const VkRenderingInfo *info) {
    if (!info || reports_off) return;
    const VkRenderingAttachmentInfo *a = info->colorAttachmentCount && info->pColorAttachments
        ? &info->pColorAttachments[0] : info->pDepthAttachment;
    file_pass(info->renderArea.extent.width, info->renderArea.extent.height, a ? load_code(a->loadOp) : 0,
              a ? store_code(a->storeOp) : 0);
}
static void VKAPI_CALL cmd_begin_pass(VkCommandBuffer cb, const VkRenderPassBeginInfo *info, VkSubpassContents contents) {
    NEXT(cb, CmdBeginRenderPass); bump(C_passes); file_begin(info); next_(cb, info, contents);
}
static void VKAPI_CALL cmd_begin_pass2(VkCommandBuffer cb, const VkRenderPassBeginInfo *info,
                                       const VkSubpassBeginInfo *subpass) {
    NEXT(cb, CmdBeginRenderPass2); bump(C_passes); file_begin(info); next_(cb, info, subpass);
}
static void VKAPI_CALL cmd_begin_pass2_khr(VkCommandBuffer cb, const VkRenderPassBeginInfo *info,
                                           const VkSubpassBeginInfo *subpass) {
    NEXT(cb, CmdBeginRenderPass2KHR); bump(C_passes); file_begin(info); next_(cb, info, subpass);
}
static void VKAPI_CALL cmd_begin_rendering(VkCommandBuffer cb, const VkRenderingInfo *info) {
    NEXT(cb, CmdBeginRendering); bump(C_passes); file_rendering(info); next_(cb, info);
}
static void VKAPI_CALL cmd_begin_rendering_khr(VkCommandBuffer cb, const VkRenderingInfo *info) {
    NEXT(cb, CmdBeginRenderingKHR); bump(C_passes); file_rendering(info); next_(cb, info);
}
/* Render passes: remember the first attachment's load and store ops, so a begun pass can be filed by them. The
 * first colour attachment of subpass 0 when there is one, else attachment 0. */
static void remember_pass(VkRenderPass pass, uint32_t count, uint32_t first, VkAttachmentLoadOp load, VkAttachmentStoreOp store) {
    if (!pass || first >= count) return;
    pthread_mutex_lock(&mem_lock);
    table_put(&render_pass_table, (uint64_t)pass, 0, (uint64_t)(load_code(load) * 16 + store_code(store)));
    pthread_mutex_unlock(&mem_lock);
}
static VkResult VKAPI_CALL create_render_pass(VkDevice device, const VkRenderPassCreateInfo *info,
                                              const VkAllocationCallbacks *allocator, VkRenderPass *pass) {
    NEXT(device, CreateRenderPass);
    if (!next_) return VK_ERROR_INITIALIZATION_FAILED;
    VkResult result = next_(device, info, allocator, pass);
    if (result == VK_SUCCESS && info && info->attachmentCount) {
        uint32_t first = info->subpassCount && info->pSubpasses[0].colorAttachmentCount &&
                         info->pSubpasses[0].pColorAttachments[0].attachment != VK_ATTACHMENT_UNUSED
            ? info->pSubpasses[0].pColorAttachments[0].attachment : 0;
        if (first < info->attachmentCount)
            remember_pass(*pass, info->attachmentCount, first, info->pAttachments[first].loadOp, info->pAttachments[first].storeOp);
    }
    return result;
}
static void remember_pass2(VkRenderPass pass, const VkRenderPassCreateInfo2 *info) {
    if (!info || !info->attachmentCount) return;
    uint32_t first = info->subpassCount && info->pSubpasses[0].colorAttachmentCount &&
                     info->pSubpasses[0].pColorAttachments[0].attachment != VK_ATTACHMENT_UNUSED
        ? info->pSubpasses[0].pColorAttachments[0].attachment : 0;
    if (first < info->attachmentCount)
        remember_pass(pass, info->attachmentCount, first, info->pAttachments[first].loadOp, info->pAttachments[first].storeOp);
}
static VkResult VKAPI_CALL create_render_pass2(VkDevice device, const VkRenderPassCreateInfo2 *info,
                                               const VkAllocationCallbacks *allocator, VkRenderPass *pass) {
    NEXT(device, CreateRenderPass2);
    if (!next_) return VK_ERROR_INITIALIZATION_FAILED;
    VkResult result = next_(device, info, allocator, pass);
    if (result == VK_SUCCESS) remember_pass2(*pass, info);
    return result;
}
static VkResult VKAPI_CALL create_render_pass2_khr(VkDevice device, const VkRenderPassCreateInfo2 *info,
                                                   const VkAllocationCallbacks *allocator, VkRenderPass *pass) {
    NEXT(device, CreateRenderPass2KHR);
    if (!next_) return VK_ERROR_INITIALIZATION_FAILED;
    VkResult result = next_(device, info, allocator, pass);
    if (result == VK_SUCCESS) remember_pass2(*pass, info);
    return result;
}
static void VKAPI_CALL destroy_render_pass(VkDevice device, VkRenderPass pass, const VkAllocationCallbacks *allocator) {
    NEXT(device, DestroyRenderPass);
    if (pass) {
        struct entry gone;
        pthread_mutex_lock(&mem_lock);
        table_take(&render_pass_table, (uint64_t)pass, &gone);
        pthread_mutex_unlock(&mem_lock);
    }
    if (next_) next_(device, pass, allocator);
}
static void VKAPI_CALL cmd_barrier(VkCommandBuffer cb, VkPipelineStageFlags src, VkPipelineStageFlags dst,
                                   VkDependencyFlags flags, uint32_t nm, const VkMemoryBarrier *m, uint32_t nb,
                                   const VkBufferMemoryBarrier *b, uint32_t ni, const VkImageMemoryBarrier *i) {
    NEXT(cb, CmdPipelineBarrier); bump(C_barriers); next_(cb, src, dst, flags, nm, m, nb, b, ni, i);
}
static void VKAPI_CALL cmd_barrier2(VkCommandBuffer cb, const VkDependencyInfo *info) {
    NEXT(cb, CmdPipelineBarrier2); bump(C_barriers); next_(cb, info);
}
static void VKAPI_CALL cmd_barrier2_khr(VkCommandBuffer cb, const VkDependencyInfo *info) {
    NEXT(cb, CmdPipelineBarrier2KHR); bump(C_barriers); next_(cb, info);
}
static void VKAPI_CALL cmd_copy_buffer(VkCommandBuffer cb, VkBuffer src, VkBuffer dst, uint32_t n,
                                       const VkBufferCopy *regions) {
    NEXT(cb, CmdCopyBuffer); bump(C_copies); next_(cb, src, dst, n, regions);
}
static void VKAPI_CALL cmd_copy_buffer_to_image(VkCommandBuffer cb, VkBuffer src, VkImage dst, VkImageLayout layout,
                                                uint32_t n, const VkBufferImageCopy *regions) {
    NEXT(cb, CmdCopyBufferToImage); bump(C_copies); next_(cb, src, dst, layout, n, regions);
}
static void VKAPI_CALL cmd_copy_image(VkCommandBuffer cb, VkImage src, VkImageLayout src_layout, VkImage dst,
                                      VkImageLayout dst_layout, uint32_t n, const VkImageCopy *regions) {
    NEXT(cb, CmdCopyImage); bump(C_copies); next_(cb, src, src_layout, dst, dst_layout, n, regions);
}
static void VKAPI_CALL cmd_blit_image(VkCommandBuffer cb, VkImage src, VkImageLayout src_layout, VkImage dst,
                                      VkImageLayout dst_layout, uint32_t n, const VkImageBlit *regions, VkFilter filter) {
    NEXT(cb, CmdBlitImage); bump(C_copies); next_(cb, src, src_layout, dst, dst_layout, n, regions, filter);
}

/* ---- proc addresses ---- */

static const struct hook { const char *name; PFN_vkVoidFunction function; } device_hooks[] = {
    {"vkDestroyDevice", (PFN_vkVoidFunction)destroy_device},
    {"vkQueueSubmit", (PFN_vkVoidFunction)queue_submit},
    {"vkQueueSubmit2", (PFN_vkVoidFunction)queue_submit2},
    {"vkQueueSubmit2KHR", (PFN_vkVoidFunction)queue_submit2_khr},
    {"vkQueuePresentKHR", (PFN_vkVoidFunction)queue_present},
    {"vkAcquireNextImageKHR", (PFN_vkVoidFunction)acquire},
    {"vkAcquireNextImage2KHR", (PFN_vkVoidFunction)acquire2},
    {"vkWaitForFences", (PFN_vkVoidFunction)wait_fences},
    {"vkQueueWaitIdle", (PFN_vkVoidFunction)queue_wait_idle},
    {"vkDeviceWaitIdle", (PFN_vkVoidFunction)device_wait_idle},
    {"vkCreateGraphicsPipelines", (PFN_vkVoidFunction)graphics_pipelines},
    {"vkCreateComputePipelines", (PFN_vkVoidFunction)compute_pipelines},
    {"vkAllocateMemory", (PFN_vkVoidFunction)allocate_memory},
    {"vkFreeMemory", (PFN_vkVoidFunction)free_memory},
    {"vkCreateImage", (PFN_vkVoidFunction)create_image},
    {"vkDestroyImage", (PFN_vkVoidFunction)destroy_image},
    {"vkDestroyBuffer", (PFN_vkVoidFunction)destroy_buffer},
    {"vkBindImageMemory", (PFN_vkVoidFunction)bind_image},
    {"vkBindImageMemory2", (PFN_vkVoidFunction)bind_image2},
    {"vkBindImageMemory2KHR", (PFN_vkVoidFunction)bind_image2_khr},
    {"vkBindBufferMemory", (PFN_vkVoidFunction)bind_buffer},
    {"vkBindBufferMemory2", (PFN_vkVoidFunction)bind_buffer2},
    {"vkBindBufferMemory2KHR", (PFN_vkVoidFunction)bind_buffer2_khr},
    {"vkCmdDraw", (PFN_vkVoidFunction)cmd_draw},
    {"vkCmdDrawIndexed", (PFN_vkVoidFunction)cmd_draw_indexed},
    {"vkCmdDrawIndirect", (PFN_vkVoidFunction)cmd_draw_indirect},
    {"vkCmdDrawIndexedIndirect", (PFN_vkVoidFunction)cmd_draw_indexed_indirect},
    {"vkCmdDrawIndirectCount", (PFN_vkVoidFunction)cmd_draw_indirect_count},
    {"vkCmdDrawIndexedIndirectCount", (PFN_vkVoidFunction)cmd_draw_indexed_indirect_count},
    {"vkCmdDispatch", (PFN_vkVoidFunction)cmd_dispatch},
    {"vkCmdDispatchIndirect", (PFN_vkVoidFunction)cmd_dispatch_indirect},
    {"vkCmdBindPipeline", (PFN_vkVoidFunction)cmd_bind_pipeline},
    {"vkCmdBindDescriptorSets", (PFN_vkVoidFunction)cmd_bind_sets},
    {"vkCmdPushConstants", (PFN_vkVoidFunction)cmd_push_constants},
    {"vkCmdBeginRenderPass", (PFN_vkVoidFunction)cmd_begin_pass},
    {"vkCmdBeginRenderPass2", (PFN_vkVoidFunction)cmd_begin_pass2},
    {"vkCmdBeginRenderPass2KHR", (PFN_vkVoidFunction)cmd_begin_pass2_khr},
    {"vkCmdBeginRendering", (PFN_vkVoidFunction)cmd_begin_rendering},
    {"vkCmdBeginRenderingKHR", (PFN_vkVoidFunction)cmd_begin_rendering_khr},
    {"vkCmdPipelineBarrier", (PFN_vkVoidFunction)cmd_barrier},
    {"vkCmdPipelineBarrier2", (PFN_vkVoidFunction)cmd_barrier2},
    {"vkCmdPipelineBarrier2KHR", (PFN_vkVoidFunction)cmd_barrier2_khr},
    {"vkCmdCopyBuffer", (PFN_vkVoidFunction)cmd_copy_buffer},
    {"vkCmdCopyBufferToImage", (PFN_vkVoidFunction)cmd_copy_buffer_to_image},
    {"vkCmdCopyImage", (PFN_vkVoidFunction)cmd_copy_image},
    {"vkCmdBlitImage", (PFN_vkVoidFunction)cmd_blit_image},
    {"vkCreateRenderPass", (PFN_vkVoidFunction)create_render_pass},
    {"vkCreateRenderPass2", (PFN_vkVoidFunction)create_render_pass2},
    {"vkCreateRenderPass2KHR", (PFN_vkVoidFunction)create_render_pass2_khr},
    {"vkDestroyRenderPass", (PFN_vkVoidFunction)destroy_render_pass},
};

static PFN_vkVoidFunction device_hook(const char *name) {
    for (size_t i = 0; i < sizeof device_hooks / sizeof *device_hooks; i++)
        if (!strcmp(name, device_hooks[i].name)) return device_hooks[i].function;
    return NULL;
}

/* The layer refers to its own entry points through these static functions: a reference to the exported
 * names would bind to libvulkan's trampolines when libvulkan is already loaded, and recurse into the loader. */
static PFN_vkVoidFunction VKAPI_CALL get_device_proc(VkDevice device, const char *name) {
    if (!strcmp(name, "vkGetDeviceProcAddr")) return (PFN_vkVoidFunction)get_device_proc;
    const struct device_data *data = device ? device_of(device) : NULL;
    if (!data || !data->next) return NULL;
    PFN_vkVoidFunction below = data->next(device, name);
    /* Only wrap what the chain below provides, so the app sees the same set of functions. */
    if (!below) return NULL;
    PFN_vkVoidFunction hook = device_hook(name);
    return hook ? hook : below;
}

static PFN_vkVoidFunction VKAPI_CALL get_instance_proc(VkInstance instance, const char *name) {
    if (!strcmp(name, "vkGetInstanceProcAddr")) return (PFN_vkVoidFunction)get_instance_proc;
    if (!strcmp(name, "vkCreateInstance")) return (PFN_vkVoidFunction)create_instance;
    if (!strcmp(name, "vkDestroyInstance")) return (PFN_vkVoidFunction)destroy_instance;
    if (!strcmp(name, "vkCreateDevice")) return (PFN_vkVoidFunction)create_device;
    if (!strcmp(name, "vkGetDeviceProcAddr")) return (PFN_vkVoidFunction)get_device_proc;
    const struct instance_data *data = instance ? instance_of(instance) : NULL;
    PFN_vkVoidFunction below = data && data->next ? data->next(instance, name) : NULL;
    if (!below) return NULL;
    PFN_vkVoidFunction hook = device_hook(name);
    return hook ? hook : below;
}

EXPORT VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetInstanceProcAddr(VkInstance instance, const char *name) {
    return get_instance_proc(instance, name);
}
EXPORT VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetDeviceProcAddr(VkDevice device, const char *name) {
    return get_device_proc(device, name);
}

EXPORT VKAPI_ATTR VkResult VKAPI_CALL vkNegotiateLoaderLayerInterfaceVersion(VkNegotiateLayerInterface *negotiate) {
    if (!negotiate || negotiate->loaderLayerInterfaceVersion < 2) return VK_ERROR_INITIALIZATION_FAILED;
    const char *seconds = getenv("DROIDDECK_VKCOUNT_SECONDS");
    if (seconds && atof(seconds) > 0) window_ns = (uint64_t)(atof(seconds) * 1e9);
    else if (seconds && !strcmp(seconds, "0")) reports_off = 1;
    pace_base = pace_rate(getenv("DROIDDECK_PACE_FPS"), 0);
    pace_fps = pace_base;
    const char *pace_path = getenv("DROIDDECK_PACE_FILE");
    if (pace_base && pace_path && *pace_path) pace_file = pace_path;
    const char *block = getenv("DROIDDECK_VK_BLOCK_MB");
    if (block && atoi(block) > 0 && atoi(block) < 1024) block_cap = (uint64_t)atoi(block) << 20;
    const char *file = getenv("DROIDDECK_VKCOUNT_FILE");
    if (file && *file && !extra) extra = fopen(file, "ae");
    negotiate->loaderLayerInterfaceVersion = 2;
    negotiate->pfnGetInstanceProcAddr = get_instance_proc;
    negotiate->pfnGetDeviceProcAddr = get_device_proc;
    negotiate->pfnGetPhysicalDeviceProcAddr = NULL;
    return VK_SUCCESS;
}

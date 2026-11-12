/* Compiled with the complete, unmodified production translation unit. The
 * compositor's take_shm/take_pixel functions are inserted verbatim by Python. */
#include "vk_present.c"
#include <assert.h>

struct vk_api g_vk;
static unsigned operations, acquires, submits, markers, waits, idle_calls, resets;
static unsigned destroys, releases, lost_notifications, presented_notifications;
static VkResult idle_answer, fence_answer, acquire_answer, present_answer;
static unsigned fail_acquire_at;
static int generated;

/* Every work-issuing callback is an oracle: no GPU operation after a terminal
 * result. Object destruction is counted separately for genuine device loss. */
static void live(void) { assert(g_dev_state == 1); ++operations; }
static VkResult acquire(VkDevice d, VkSwapchainKHR s, uint64_t t, VkSemaphore a, VkFence f, uint32_t *i) {
    live(); ++acquires; *i = 0;
    return acquires == fail_acquire_at ? acquire_answer : VK_SUCCESS;
}
static VkResult idle(VkDevice d) { live(); ++idle_calls; return idle_answer; }
static VkResult wait_fences(VkDevice d, uint32_t n, const VkFence *f, VkBool32 all, uint64_t t) {
    live(); ++waits; return fence_answer;
}
static VkSemaphore last_signal, last_wait;
static VkFence last_fence;
static VkResult submit(VkQueue q, uint32_t n, const VkSubmitInfo *s, VkFence f) {
    live(); ++submits; if (!s->commandBufferCount) ++markers;
    last_signal = s->signalSemaphoreCount ? s->pSignalSemaphores[0] : VK_NULL_HANDLE;
    last_wait = s->waitSemaphoreCount ? s->pWaitSemaphores[0] : VK_NULL_HANDLE;
    last_fence = f;
    return VK_SUCCESS;
}
static VkResult present(VkQueue q, const VkPresentInfoKHR *p) { live(); return present_answer; }
static VkResult reset_cmd(VkCommandBuffer c, VkCommandBufferResetFlags f) { live(); ++resets; return VK_SUCCESS; }
static VkResult reset_fence(VkDevice d, uint32_t n, const VkFence *f) { live(); ++resets; return VK_SUCCESS; }
static VkResult begin_cmd(VkCommandBuffer c, const VkCommandBufferBeginInfo *b) { live(); return VK_SUCCESS; }
static VkResult end_cmd(VkCommandBuffer c) { live(); return VK_SUCCESS; }
static void barrier(VkCommandBuffer c, VkPipelineStageFlags s, VkPipelineStageFlags d, VkDependencyFlags f,
                    uint32_t nm, const VkMemoryBarrier *m, uint32_t nb, const VkBufferMemoryBarrier *b,
                    uint32_t ni, const VkImageMemoryBarrier *i) { live(); }
static void clear(VkCommandBuffer c, VkImage i, VkImageLayout l, const VkClearColorValue *v,
                  uint32_t n, const VkImageSubresourceRange *r) { live(); }
static void blit(VkCommandBuffer c, VkImage s, VkImageLayout sl, VkImage d, VkImageLayout dl,
                 uint32_t n, const VkImageBlit *b, VkFilter f) { live(); }
static void destroy_swap(VkDevice d, VkSwapchainKHR s, const VkAllocationCallbacks *a) { ++destroys; }
static void destroy_surface(VkInstance i, VkSurfaceKHR s, const VkAllocationCallbacks *a) { ++destroys; }
static void destroy_sem(VkDevice d, VkSemaphore s, const VkAllocationCallbacks *a) { live(); ++destroys; }
static VkResult create_sem(VkDevice d, const VkSemaphoreCreateInfo *c, const VkAllocationCallbacks *a, VkSemaphore *s) {
    live(); *s = (VkSemaphore)(uintptr_t)1; return VK_SUCCESS;
}
static void destroy_image(VkDevice d, VkImage i, const VkAllocationCallbacks *a) { ++destroys; }
static uintptr_t next_handle = 100;
static unsigned slot_objects;
static VkResult alloc_cmds(VkDevice d, const VkCommandBufferAllocateInfo *i, VkCommandBuffer *c) {
    live(); ++slot_objects; *c = (VkCommandBuffer)next_handle++; return VK_SUCCESS;
}
static VkResult create_fence(VkDevice d, const VkFenceCreateInfo *c, const VkAllocationCallbacks *a, VkFence *f) {
    live(); ++slot_objects; *f = (VkFence)next_handle++; return VK_SUCCESS;
}
static VkResult create_slot_sem(VkDevice d, const VkSemaphoreCreateInfo *c, const VkAllocationCallbacks *a,
                                VkSemaphore *s) {
    live(); ++slot_objects; *s = (VkSemaphore)next_handle++; return VK_SUCCESS;
}
static void destroy_fence(VkDevice d, VkFence f, const VkAllocationCallbacks *a) { ++destroys; }
static void free_cmds(VkDevice d, VkCommandPool p, uint32_t n, const VkCommandBuffer *c) { ++destroys; }
static VkResult export_answer;
static unsigned exports, imports;
static VkResult get_sem_fd(VkDevice d, const VkSemaphoreGetFdInfoKHR *i, int *fd) {
    live(); ++exports;
    assert(i->handleType == VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT && i->semaphore == last_signal);
    if (export_answer != VK_SUCCESS) return export_answer;
    int p[2]; assert(pipe(p) == 0); close(p[1]); *fd = p[0]; return VK_SUCCESS;
}
static VkResult import_sem_fd(VkDevice d, const VkImportSemaphoreFdInfoKHR *i) {
    live(); ++imports; close(i->fd); return VK_SUCCESS;
}
static void free_memory(VkDevice d, VkDeviceMemory m, const VkAllocationCallbacks *a) { ++destroys; }
static void unmap(VkDevice d, VkDeviceMemory m) { ++destroys; }

/* External modules are not the subject of this fault injection. Their declared
 * signatures remain checked by the production headers. */
int __android_log_print(int p, const char *t, const char *f, ...) { return 0; }
void droiddeck_log(const char *t, const char *f, ...) {}
void droiddeck_on_first_frame(void) {}
int32_t ANativeWindow_getWidth(ANativeWindow *w) { return 64; }
int32_t ANativeWindow_getHeight(ANativeWindow *w) { return 64; }
void ANativeWindow_release(ANativeWindow *w) {}
void sc_layer_window_gone(void) {}
int sc_layer_present_pass(const struct vkp_draw *d, int n, int w, int h) { return 0; }
void sc_layer_hide(void) {}
int sc_layer_base_black(int w, int h) { return 0; }
void sc_layer_hide_base(void) {}
void sc_layer_hide_overlay(void) {}
int vk_loader_open(const char *a, const char *b, const char *c) { assert(0); return -1; }
void vk_loader_load_instance(VkInstance i) { assert(0); }
void vk_loader_load_device(VkDevice d) { assert(0); }
PFN_vkGetInstanceProcAddr vk_loader_gipa(void) { return NULL; }
void vkp_effects_bind_device(VkDevice d, VkPhysicalDevice p, const VkPhysicalDeviceMemoryProperties *m) {}
int vkp_effects_active(void) { return 0; }
int vkp_effects_upscaling(void) { return 0; }
int vkp_effects_postprocessing(void) { return 0; }
VkFilter vkp_effects_blit_filter(void) { return VK_FILTER_NEAREST; }
void vkp_effects_set_formats(VkFormat a, VkFormat b) {}
VkImage vkp_effects_run(VkCommandBuffer c, VkImage i, int w, int h, int ow, int oh, int *rw, int *rh) {
    *rw = w; *rh = h; return i;
}
const void *vkp_framegen_device_features(VkInstance i, PFN_vkGetInstanceProcAddr p, VkPhysicalDevice d) { return NULL; }
void vkp_framegen_device_ready(VkDevice d, VkQueue q, uint32_t f, int e) {}
void vkp_framegen_device_lost(void) { ++lost_notifications; }
int vkp_framegen_active(void) { return generated != 0; }
int vkp_framegen_extra_images(void) { return generated; }
int vkp_framegen_format_ok(VkFormat f) { return 0; }
int vkp_framegen_run(VkCommandBuffer c, VkImage s, VkImageView v, int w, int h,
                     VkFormat f, VkImage images[VKP_FG_MAX_GENERATIONS]) {
    for (int i = 0; i < generated; ++i) images[i] = s;
    return generated;
}
void vkp_framegen_presented(int n) { ++presented_notifications; }
void vkp_gpu_busy_device(VkPhysicalDevice p, VkDevice d, uint32_t f) {}
void vkp_gpu_busy_device_lost(void) {}
void hdrc_bind_device(VkDevice d, const VkPhysicalDeviceMemoryProperties *m) {}
void hdrc_forget_image(VkImage i) {}
int hdrc_encode(VkCommandBuffer c, VkImage a, VkImage b, VkFormat f, int w, int h, const struct hdrc_params *p) { return -1; }
void blendp_bind_device(VkDevice d) {}
void blendp_forget_image(VkImage i) {}
void vkp_effects_forget_image(VkImage i) {}
void blendp_begin_frame(void) {}
int blendp_draw(VkCommandBuffer c, VkImage s, VkFormat sf, int sw, int sh, int sg,
                VkImage d, VkFormat df, int dw, int dh, const VkImageBlit *b) { return -1; }
int droiddeck_color_requested(void) { return 0; }
float droiddeck_color_sdr_white(void) { return 203.0f; }

enum surface_role { ROLE_NONE, ROLE_TOPLEVEL };
struct damage_list { int n; };
struct surface {
    struct vkp_image *shm_img;
    struct wl_resource *resource;
    int buf_w, buf_h, buf_alpha, has_content;
    /* Read by take_shm's game-frame detection; with no damage it stays off here. */
    int src_set; float src[4];
    struct damage_list damage_buf, damage_local;
    struct surface *parent; enum surface_role role; int mapped;
};
static void surface_size(const struct surface *s, int *w, int *h) { *w = s->buf_w; *h = s->buf_h; }
static void scene_size(int *w, int *h) { *w = *h = 0; }
static int64_t damage_area(const struct damage_list *d, int bound_w, int bound_h) { return 0; }
static int64_t now_ns(void) { return 0; }
static struct surface *g_hud_surface, *g_hud_shm;
static int64_t g_hud_shm_ns, g_hud_last_ns;
static int g_hud_new;
static void droiddeck_on_game_frame(void) {}
struct client_info { int asked_feedback, shm_frames; };
struct wl_shm_buffer { int w, h, stride; const void *data; };
#define WL_SHM_FORMAT_ARGB8888 0
static unsigned g_stat_shm;
static int wl_shm_buffer_get_width(struct wl_shm_buffer *b) { return b->w; }
static int wl_shm_buffer_get_height(struct wl_shm_buffer *b) { return b->h; }
static int wl_shm_buffer_get_stride(struct wl_shm_buffer *b) { return b->stride; }
static int wl_shm_buffer_get_format(struct wl_shm_buffer *b) { return WL_SHM_FORMAT_ARGB8888; }
static const void *wl_shm_buffer_get_data(struct wl_shm_buffer *b) { return b->data; }
static void wl_shm_buffer_begin_access(struct wl_shm_buffer *b) {}
static void wl_shm_buffer_end_access(struct wl_shm_buffer *b) {}
struct wl_resource { int dmabuf; };
static struct wl_resource *expected_release;
static void wl_buffer_send_release(struct wl_resource *b) { assert(b && b == expected_release); ++releases; }
static struct wl_client *wl_resource_get_client(struct wl_resource *r) { return NULL; }
static struct client_info *client_info_of(struct wl_client *c) { return NULL; }
struct dmabuf_buffer { struct vkp_image *img; };
static struct dmabuf_buffer dmabuf_fixture;
static struct dmabuf_buffer *get_dmabuf(struct wl_resource *buffer) {
    assert(buffer);
    return buffer->dmabuf ? &dmabuf_fixture : NULL;
}
/* COMPOSITOR_CALLERS */

static void setup(int extra) {
    g_vk = (struct vk_api){.AcquireNextImageKHR = acquire, .DeviceWaitIdle = idle,
        .WaitForFences = wait_fences, .QueueSubmit = submit, .QueuePresentKHR = present,
        .ResetCommandBuffer = reset_cmd, .ResetFences = reset_fence, .BeginCommandBuffer = begin_cmd,
        .EndCommandBuffer = end_cmd, .CmdPipelineBarrier = barrier, .CmdClearColorImage = clear,
        .CmdBlitImage = blit, .DestroySwapchainKHR = destroy_swap, .DestroySurfaceKHR = destroy_surface,
        .DestroySemaphore = destroy_sem, .CreateSemaphore = create_sem,
        .DestroyImage = destroy_image, .FreeMemory = free_memory, .UnmapMemory = unmap};
    idle_answer = fence_answer = acquire_answer = present_answer = VK_SUCCESS;
    generated = g_swap_extra = extra;
    g_dev_state = 1;
    g_dev = (VkDevice)(uintptr_t)1; g_inst = (VkInstance)(uintptr_t)1;
    g_window = (ANativeWindow *)(uintptr_t)1; g_queue = (VkQueue)(uintptr_t)1;
    g_swapchain = (VkSwapchainKHR)(uintptr_t)2; g_surface = (VkSurfaceKHR)(uintptr_t)3;
    g_images = calloc(1, sizeof(*g_images)); assert(g_images);
    *g_images = (VkImage)(uintptr_t)4; g_nimg = 1;
    g_extent = g_caps_extent = (VkExtent2D){64, 64};
    g_surface_checked_ns = INT64_MAX; /* no simulated geometry change */
    g_scene.img = (VkImage)(uintptr_t)5; g_scene.mem = (VkDeviceMemory)(uintptr_t)6;
    g_scene.w = g_scene.h = 64;
    for (int k = 0; k < MAX_PRESENTS; ++k) {
        g_cmds[k] = (VkCommandBuffer)(uintptr_t)(10 + k);
        g_acqs[k] = (VkSemaphore)(uintptr_t)(20 + k);
        g_rnds[k] = (VkSemaphore)(uintptr_t)(30 + k);
    }
    g_fence = (VkFence)(uintptr_t)40;
}

/* Probe the public GPU entry points after a terminal result. This includes an
 * active off-screen pass and consumed release fds, not just the render guard. */
static void assert_stopped(void) {
    unsigned before = operations, before_destroys = destroys;
    struct vkp_image src = {.image = (VkImage)(uintptr_t)7, .w = 1, .h = 1};
    struct vkp_image dst = {.image = (VkImage)(uintptr_t)8, .w = 1, .h = 1, .blit_dst = 1};
    uint32_t pixel = 0; uint64_t mod = 0;
    assert(vkp_render(64, 64, NULL, 0) == -1);
    assert(vkp_render_plain(64, 64) == -1);
    assert(vkp_render_hdr(64, 64, NULL, 0, NULL, NULL) == -1);
    assert(vkp_base_black(64, 64) == -1);
    assert(vkp_update_map(64, 64) == -1);
    assert(vkp_ready() == -1);
    assert(vkp_image_create_shm(1, 1) == NULL);
    assert(vkp_image_import_dmabuf(-1, 0, VKP_MOD_LINEAR, 1, 1, 4, 0, 0) == NULL);
    assert(vkp_dmabuf_modifiers(0, &mod, 1) == 0);
    assert(vkp_image_readback(&src, &pixel, 1) == -1);
    assert(vkp_pass_begin(64, 64, NULL, 0, NULL, NULL) == -1);
    int fd[2]; assert(pipe(fd) == 0);
    int done = 7;
    assert(vkp_blit_image(&src, &dst, fd[0], &done) == -1 && done == -1);
    errno = 0; assert(close(fd[0]) == -1 && errno == EBADF); close(fd[1]);
    g_pass.active = 1;
    assert(pipe(fd) == 0);
    assert(vkp_pass_copy_to(&dst, fd[0]) == -1 && !g_pass.active);
    errno = 0; assert(close(fd[0]) == -1 && errno == EBADF); close(fd[1]);
    g_pass.active = 1; vkp_pass_abort(); assert(!g_pass.active);
    assert(timed_wait(g_fence, UINT64_MAX) != VK_SUCCESS);
    assert(operations == before && destroys == before_destroys);
}

static void enable_layer_fence(void) {
    g_vk.AllocateCommandBuffers = alloc_cmds; g_vk.CreateFence = create_fence; g_vk.CreateSemaphore = create_slot_sem;
    g_vk.DestroyFence = destroy_fence; g_vk.FreeCommandBuffers = free_cmds;
    g_get_sem_fd = get_sem_fd; g_import_sem_fd = import_sem_fd;
    g_wait_sem = (VkSemaphore)(uintptr_t)41;
    g_layer_fence = 1; export_answer = VK_SUCCESS;
}

static struct vkp_image *layer_slot(uintptr_t handle) {
    struct vkp_image *img = calloc(1, sizeof(*img)); assert(img);
    *img = (struct vkp_image){.image = (VkImage)handle, .w = 4, .h = 4, .dmabuf = 1, .blit_dst = 1};
    return img;
}

/* A layer blit returns with its completion as a sync fd; the slot's fence is
 * waited for only before the slot is reused or the client's buffer goes back. */
static void layer_fence(void) {
    setup(0); enable_layer_fence();
    struct vkp_image src = {.image = (VkImage)(uintptr_t)70, .w = 4, .h = 4, .dmabuf = 1};
    struct vkp_image *a = layer_slot(71), *b = layer_slot(72);
    int done = -1, release[2];
    assert(pipe(release) == 0); close(release[1]);
    assert(vkp_blit_image(&src, a, release[0], &done) == 0);
    assert(done >= 0 && waits == 0 && submits == 1 && exports == 1 && imports == 1);
    assert(slot_objects == 4 && last_signal == a->blit_done && last_wait == a->blit_wait && last_fence == a->blit_fence);
    assert(a->blit_serial == 1 && src.gpu_use == 1);
    close(done);
    assert(vkp_blit_image(&src, a, -1, &done) == 0 && done >= 0 && waits == 1 && submits == 2);
    assert(slot_objects == 4 && a->blit_serial == 2);
    close(done);
    assert(vkp_blit_image(&src, b, -1, &done) == 0 && done >= 0 && waits == 1 && b->blit_serial == 3);
    close(done);
    struct wl_resource dmabuf = {.dmabuf = 1};
    struct vkp_image *client = calloc(1, sizeof(*client)); assert(client);
    *client = src;
    dmabuf_fixture.img = client; expected_release = &dmabuf;
    send_buffer_release(&dmabuf);
    assert(waits == 3 && releases == 1 && !a->blit_serial && !b->blit_serial);
    send_buffer_release(&dmabuf);
    assert(waits == 3 && releases == 2);
    unsigned before = destroys;
    vkp_image_destroy(a);
    assert(waits == 3 && destroys == before + 5); /* cmd, fence, two semaphores, image */
    /* Without done_fd the blit is waited for on the CPU, as before. */
    assert(vkp_blit_image(&src, b, -1, NULL) == 0 && waits == 4 && last_fence == g_fence && !last_signal);
    /* An export failure finishes this blit on the CPU and turns the feature off. */
    export_answer = VK_ERROR_OUT_OF_HOST_MEMORY;
    assert(vkp_blit_image(&src, b, -1, &done) == 0 && done == -1 && waits == 5 && !g_layer_fence);
    assert(!b->blit_serial);
    assert(vkp_blit_image(&src, b, -1, &done) == 0 && done == -1 && waits == 6 && last_fence == g_fence);
    free(client); vkp_image_destroy(b); free(g_images);
}

static void layer_fence_terminal(VkResult result) {
    setup(0); enable_layer_fence();
    struct vkp_image src = {.image = (VkImage)(uintptr_t)70, .w = 4, .h = 4, .dmabuf = 1};
    struct vkp_image *a = layer_slot(71);
    int done = -1;
    assert(vkp_blit_image(&src, a, -1, &done) == 0 && done >= 0 && waits == 0);
    close(done);
    fence_answer = result;
    struct wl_resource dmabuf = {.dmabuf = 1};
    dmabuf_fixture.img = &src; expected_release = &dmabuf;
    send_buffer_release(&dmabuf);
    assert(waits == 1);
    if (result == VK_ERROR_DEVICE_LOST) {
        assert(vkp_device_lost() && !a->blit_serial && releases == 1);
    } else {
        assert(g_dev_state == -3 && a->blit_serial && releases == 0); /* the read is unconfirmed */
        unsigned before = destroys;
        vkp_image_destroy(a);
        assert(destroys == before && waits == 1);
        free(g_images);
    }
    assert_stopped();
}

static void acquire_failure(int later, VkResult result) {
    setup(later ? 1 : 0);
    fail_acquire_at = later ? 2 : 1; acquire_answer = result;
    idle_answer = VK_ERROR_OUT_OF_HOST_MEMORY;
    VkImage *retained = g_images;
    assert(vkp_render(64, 64, NULL, 0) == -1);
    assert(g_dev_state == -3 && vkp_completion_unknown() && !vkp_device_lost());
    assert(acquires == fail_acquire_at && idle_calls == 1);
    assert(submits == (unsigned)later && markers == 0 && waits == 0);
    assert(destroys == 0 && g_images == retained && g_nimg == 1);
    assert(g_swapchain && g_surface && g_fence && g_scene.img && g_scene.mem);
    assert_stopped();
    free(g_images); /* test-owned fake resources only */
}

static void upload_timeout(int single_pixel) {
    setup(0);
    uint8_t bytes[48], saved[48], data[16];
    memset(bytes, 0xa5, sizeof(bytes)); memset(data, 0x3c, sizeof(data));
    struct vkp_image *img = calloc(1, sizeof(*img)); assert(img);
    *img = (struct vkp_image){.image = (VkImage)(uintptr_t)7, .mem = (VkDeviceMemory)(uintptr_t)8,
        .map = bytes, .offset = 3, .row_pitch = 16,
        .w = single_pixel ? 1 : 2, .h = single_pixel ? 1 : 2};
    struct surface s = {.shm_img = img};
    struct wl_shm_buffer b = {.w = img->w, .h = img->h, .stride = img->w * 4, .data = data};
    struct wl_resource resource = {0};
    expected_release = &resource;
    if (single_pixel) take_pixel(&s, data, &resource); else take_shm(&s, &b, &resource);
    assert(bytes[3] == 0x3c && bytes[0] == 0xa5 && releases == 1);
    memcpy(saved, bytes, sizeof(saved));
    struct vkp_image dst = {.image = (VkImage)(uintptr_t)9, .blit_dst = 1, .w = img->w, .h = img->h};
    fence_answer = VK_TIMEOUT;
    assert(vkp_blit_image(img, &dst, -1, NULL) == -1);
    assert(g_dev_state == -3 && submits == 1 && waits == 1);
    memset(data, 0x7e, sizeof(data));
    if (single_pixel) take_pixel(&s, data, &resource); else take_shm(&s, &b, &resource);
    assert(s.shm_img == img && memcmp(saved, bytes, sizeof(saved)) == 0 && releases == 2);
    struct wl_resource dmabuf = {.dmabuf = 1};
    expected_release = &dmabuf;
    assert(buffer_release_blocked(&dmabuf));
    send_buffer_release(&dmabuf);
    assert(releases == 2);
    vkp_image_destroy(img);
    assert(destroys == 0 && img->map == bytes && img->image && img->mem);
    assert_stopped();
    g_dev_state = 1;
    assert(!buffer_release_blocked(&dmabuf));
    send_buffer_release(&dmabuf);
    assert(releases == 3);
    free(img); free(g_images); /* fake resources only, not production cleanup */
}

int main(int argc, char **argv) {
    assert(argc == 2);
    const char *name = argv[1];
    if (!strcmp(name, "first-out-of-date")) acquire_failure(0, VK_ERROR_OUT_OF_DATE_KHR);
    else if (!strcmp(name, "first-surface-lost")) acquire_failure(0, VK_ERROR_SURFACE_LOST_KHR);
    else if (!strcmp(name, "later-out-of-date")) acquire_failure(1, VK_ERROR_OUT_OF_DATE_KHR);
    else if (!strcmp(name, "later-surface-lost")) acquire_failure(1, VK_ERROR_SURFACE_LOST_KHR);
    else if (!strcmp(name, "shm-timeout")) upload_timeout(0);
    else if (!strcmp(name, "pixel-timeout")) upload_timeout(1);
    else if (!strcmp(name, "layer-fence")) layer_fence();
    else if (!strcmp(name, "layer-fence-device-lost")) layer_fence_terminal(VK_ERROR_DEVICE_LOST);
    else if (!strcmp(name, "layer-fence-timeout")) layer_fence_terminal(VK_TIMEOUT);
    else if (!strcmp(name, "pass-terminal")) { setup(0); g_dev_state = -3; assert_stopped(); free(g_images); }
    else if (!strcmp(name, "normal") || !strcmp(name, "framegen")) {
        int extra = !strcmp(name, "framegen"); setup(extra);
        assert(vkp_render(64, 64, NULL, 0) == 0 && g_dev_state == 1);
        assert(acquires == (unsigned)(1 + extra) && submits == (unsigned)(1 + extra));
        assert(waits == 1 && markers == 0 && idle_calls == 0 && destroys == 0);
        assert(presented_notifications == (unsigned)extra); free(g_images);
    } else if (!strcmp(name, "later-timeout")) {
        setup(1); fail_acquire_at = 2; acquire_answer = VK_TIMEOUT;
        assert(vkp_render(64, 64, NULL, 0) == -1 && g_dev_state == 1);
        assert(acquires == 2 && submits == 2 && markers == 1 && waits == 1); free(g_images);
    } else if (!strcmp(name, "acquire-device-lost") || !strcmp(name, "present-device-lost")) {
        setup(1);
        if (!strcmp(name, "acquire-device-lost")) { fail_acquire_at = 2; acquire_answer = VK_ERROR_DEVICE_LOST; }
        else present_answer = VK_ERROR_DEVICE_LOST;
        assert(vkp_render(64, 64, NULL, 0) == -1 && vkp_device_lost());
        assert(!vkp_completion_unknown());
        assert(g_dev_state == -2 && lost_notifications == 1 && markers == 0 && waits == 0);
        assert(!g_images && !g_swapchain && !g_surface && destroys == 2);
        assert_stopped();
    } else assert(0 && "unknown scenario");
    return 0;
}

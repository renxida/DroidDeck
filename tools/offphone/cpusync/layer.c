/*
 * VK_LAYER_OFFPHONE_cpu_sync: test-rig only. Never shipped in the APK.
 *
 * The off-phone rig runs the session on CI Android, which has no GPU; the guest uses Mesa's
 * CPU driver (lavapipe). gamescope refuses a device without VK_KHR_external_semaphore_fd, which
 * lavapipe does not have. This layer adds it the way tools/venus/mesa-venus-vtest.patch does for
 * Venus: a sync file is exported as -1 ("already signaled") once the device has gone idle, and an
 * imported one is waited for on the CPU, then the semaphore is signaled by an empty submit.
 *
 * Everything passes straight through when the driver has the extension itself.
 */
#include <vulkan/vulkan.h>
#include <vulkan/vk_layer.h>
#include <poll.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define EXPORT __attribute__((visibility("default")))
#define EXT_NAME VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME

static void *key_of(const void *handle) { return *(void **)handle; }

struct inst {
    void *key;
    VkInstance handle;
    PFN_vkGetInstanceProcAddr gipa;
    PFN_vkDestroyInstance DestroyInstance;
    PFN_vkEnumerateDeviceExtensionProperties EnumerateDeviceExtensionProperties;
    PFN_vkGetPhysicalDeviceExternalSemaphoreProperties GetPhysicalDeviceExternalSemaphoreProperties;
    struct inst *next;
};

struct queue { VkQueue handle; struct queue *next; };

struct dev {
    void *key;
    VkDevice handle;
    bool emulate; /* the driver lacks the extension; the layer provides it */
    PFN_vkGetDeviceProcAddr gdpa;
    PFN_vkDestroyDevice DestroyDevice;
    PFN_vkGetDeviceQueue GetDeviceQueue;
    PFN_vkGetDeviceQueue2 GetDeviceQueue2;
    PFN_vkQueueSubmit QueueSubmit;
    PFN_vkQueueSubmit2 QueueSubmit2;
    PFN_vkQueuePresentKHR QueuePresentKHR;
    PFN_vkQueueWaitIdle QueueWaitIdle;
    PFN_vkDeviceWaitIdle DeviceWaitIdle;
    PFN_vkCreateSemaphore CreateSemaphore;
    struct queue *queues;
    pthread_mutex_t submit; /* the layer's own submits share queues with the application's */
    struct dev *next;
};

static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static struct inst *insts;
static struct dev *devs;

static void say(const char *fmt, const char *arg)
{
    if (getenv("OFFPHONE_CPU_SYNC_QUIET"))
        return;
    fprintf(stderr, "offphone-cpu-sync: ");
    fprintf(stderr, fmt, arg);
    fputc('\n', stderr);
}

static struct inst *inst_of(const void *handle)
{
    void *key = key_of(handle);
    pthread_mutex_lock(&lock);
    struct inst *i = insts;
    while (i && i->key != key)
        i = i->next;
    pthread_mutex_unlock(&lock);
    return i;
}

static struct dev *dev_of(const void *handle)
{
    void *key = key_of(handle);
    pthread_mutex_lock(&lock);
    struct dev *d = devs;
    while (d && d->key != key)
        d = d->next;
    pthread_mutex_unlock(&lock);
    return d;
}

static bool driver_has(struct inst *i, VkPhysicalDevice pd)
{
    uint32_t n = 0;
    if (i->EnumerateDeviceExtensionProperties(pd, NULL, &n, NULL) != VK_SUCCESS)
        return false;
    VkExtensionProperties *all = calloc(n ? n : 1, sizeof(*all));
    i->EnumerateDeviceExtensionProperties(pd, NULL, &n, all);
    bool has = false;
    for (uint32_t k = 0; k < n; k++)
        if (!strcmp(all[k].extensionName, EXT_NAME))
            has = true;
    free(all);
    return has;
}

/* ---- instance level ---- */

static VKAPI_ATTR VkResult VKAPI_CALL CreateInstance(const VkInstanceCreateInfo *info,
                                                     const VkAllocationCallbacks *alloc, VkInstance *out)
{
    VkLayerInstanceCreateInfo *link = (VkLayerInstanceCreateInfo *)info->pNext;
    while (link && !(link->sType == VK_STRUCTURE_TYPE_LOADER_INSTANCE_CREATE_INFO && link->function == VK_LAYER_LINK_INFO))
        link = (VkLayerInstanceCreateInfo *)link->pNext;
    if (!link)
        return VK_ERROR_INITIALIZATION_FAILED;
    PFN_vkGetInstanceProcAddr gipa = link->u.pLayerInfo->pfnNextGetInstanceProcAddr;
    link->u.pLayerInfo = link->u.pLayerInfo->pNext;
    VkResult r = ((PFN_vkCreateInstance)gipa(VK_NULL_HANDLE, "vkCreateInstance"))(info, alloc, out);
    if (r != VK_SUCCESS)
        return r;
    struct inst *i = calloc(1, sizeof(*i));
    i->key = key_of(*out);
    i->handle = *out;
    i->gipa = gipa;
    i->DestroyInstance = (PFN_vkDestroyInstance)gipa(*out, "vkDestroyInstance");
    i->EnumerateDeviceExtensionProperties =
        (PFN_vkEnumerateDeviceExtensionProperties)gipa(*out, "vkEnumerateDeviceExtensionProperties");
    i->GetPhysicalDeviceExternalSemaphoreProperties = (PFN_vkGetPhysicalDeviceExternalSemaphoreProperties)
        gipa(*out, "vkGetPhysicalDeviceExternalSemaphoreProperties");
    if (!i->GetPhysicalDeviceExternalSemaphoreProperties)
        i->GetPhysicalDeviceExternalSemaphoreProperties = (PFN_vkGetPhysicalDeviceExternalSemaphoreProperties)
            gipa(*out, "vkGetPhysicalDeviceExternalSemaphorePropertiesKHR");
    pthread_mutex_lock(&lock);
    i->next = insts;
    insts = i;
    pthread_mutex_unlock(&lock);
    return VK_SUCCESS;
}

static VKAPI_ATTR void VKAPI_CALL DestroyInstance(VkInstance instance, const VkAllocationCallbacks *alloc)
{
    void *key = key_of(instance);
    pthread_mutex_lock(&lock);
    struct inst **p = &insts;
    while (*p && (*p)->key != key)
        p = &(*p)->next;
    struct inst *i = *p;
    if (i)
        *p = i->next;
    pthread_mutex_unlock(&lock);
    if (i) {
        i->DestroyInstance(instance, alloc);
        free(i);
    }
}

static VKAPI_ATTR VkResult VKAPI_CALL EnumerateDeviceExtensionProperties(VkPhysicalDevice pd, const char *layer,
                                                                         uint32_t *count, VkExtensionProperties *props)
{
    struct inst *i = inst_of(pd);
    if (layer && strcmp(layer, "VK_LAYER_OFFPHONE_cpu_sync"))
        return i->EnumerateDeviceExtensionProperties(pd, layer, count, props);
    if (layer) { /* the layer's own list */
        if (!props) { *count = 1; return VK_SUCCESS; }
        if (*count < 1) return VK_INCOMPLETE;
        memset(props, 0, sizeof(*props));
        strcpy(props->extensionName, EXT_NAME);
        props->specVersion = 1;
        *count = 1;
        return VK_SUCCESS;
    }
    if (driver_has(i, pd))
        return i->EnumerateDeviceExtensionProperties(pd, NULL, count, props);
    uint32_t own = 0;
    VkResult r = i->EnumerateDeviceExtensionProperties(pd, NULL, &own, NULL);
    if (r != VK_SUCCESS)
        return r;
    if (!props) { *count = own + 1; return VK_SUCCESS; }
    VkExtensionProperties *all = calloc(own + 1, sizeof(*all));
    i->EnumerateDeviceExtensionProperties(pd, NULL, &own, all);
    strcpy(all[own].extensionName, EXT_NAME);
    all[own].specVersion = 1;
    uint32_t n = *count < own + 1 ? *count : own + 1;
    memcpy(props, all, n * sizeof(*all));
    free(all);
    *count = n;
    return n < own + 1 ? VK_INCOMPLETE : VK_SUCCESS;
}

static VKAPI_ATTR void VKAPI_CALL GetPhysicalDeviceExternalSemaphoreProperties(
    VkPhysicalDevice pd, const VkPhysicalDeviceExternalSemaphoreInfo *info, VkExternalSemaphoreProperties *out)
{
    struct inst *i = inst_of(pd);
    if (i->GetPhysicalDeviceExternalSemaphoreProperties)
        i->GetPhysicalDeviceExternalSemaphoreProperties(pd, info, out);
    else
        out->exportFromImportedHandleTypes = out->compatibleHandleTypes = out->externalSemaphoreFeatures = 0;
    if (driver_has(i, pd) || info->handleType != VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT)
        return;
    out->exportFromImportedHandleTypes = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT;
    out->compatibleHandleTypes = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT;
    out->externalSemaphoreFeatures =
        VK_EXTERNAL_SEMAPHORE_FEATURE_EXPORTABLE_BIT | VK_EXTERNAL_SEMAPHORE_FEATURE_IMPORTABLE_BIT;
}

/* ---- device level ---- */

static VKAPI_ATTR VkResult VKAPI_CALL CreateDevice(VkPhysicalDevice pd, const VkDeviceCreateInfo *info,
                                                   const VkAllocationCallbacks *alloc, VkDevice *out)
{
    VkLayerDeviceCreateInfo *link = (VkLayerDeviceCreateInfo *)info->pNext;
    while (link && !(link->sType == VK_STRUCTURE_TYPE_LOADER_DEVICE_CREATE_INFO && link->function == VK_LAYER_LINK_INFO))
        link = (VkLayerDeviceCreateInfo *)link->pNext;
    if (!link)
        return VK_ERROR_INITIALIZATION_FAILED;
    PFN_vkGetInstanceProcAddr gipa = link->u.pLayerInfo->pfnNextGetInstanceProcAddr;
    PFN_vkGetDeviceProcAddr gdpa = link->u.pLayerInfo->pfnNextGetDeviceProcAddr;
    link->u.pLayerInfo = link->u.pLayerInfo->pNext;
    struct inst *i = inst_of(pd);
    bool emulate = !driver_has(i, pd);

    VkDeviceCreateInfo copy = *info;
    const char **names = calloc(info->enabledExtensionCount + 1, sizeof(*names));
    copy.enabledExtensionCount = 0;
    for (uint32_t k = 0; k < info->enabledExtensionCount; k++)
        if (!emulate || strcmp(info->ppEnabledExtensionNames[k], EXT_NAME))
            names[copy.enabledExtensionCount++] = info->ppEnabledExtensionNames[k];
    copy.ppEnabledExtensionNames = names;
    VkResult r = ((PFN_vkCreateDevice)gipa(i->handle, "vkCreateDevice"))(pd, &copy, alloc, out);
    free(names);
    if (r != VK_SUCCESS)
        return r;

    struct dev *d = calloc(1, sizeof(*d));
    d->key = key_of(*out);
    d->handle = *out;
    d->emulate = emulate;
    d->gdpa = gdpa;
#define LOAD(name) d->name = (PFN_vk##name)gdpa(*out, "vk" #name);
    LOAD(DestroyDevice)
    LOAD(GetDeviceQueue)
    LOAD(GetDeviceQueue2)
    LOAD(QueueSubmit)
    LOAD(QueueSubmit2)
    LOAD(QueuePresentKHR)
    LOAD(QueueWaitIdle)
    LOAD(DeviceWaitIdle)
    LOAD(CreateSemaphore)
#undef LOAD
    if (!d->QueueSubmit2)
        d->QueueSubmit2 = (PFN_vkQueueSubmit2)gdpa(*out, "vkQueueSubmit2KHR");
    pthread_mutex_init(&d->submit, NULL);
    pthread_mutex_lock(&lock);
    d->next = devs;
    devs = d;
    pthread_mutex_unlock(&lock);
    if (emulate)
        say("%s emulated with CPU waits (test rig only)", EXT_NAME);
    return VK_SUCCESS;
}

static VKAPI_ATTR void VKAPI_CALL DestroyDevice(VkDevice device, const VkAllocationCallbacks *alloc)
{
    void *key = key_of(device);
    pthread_mutex_lock(&lock);
    struct dev **p = &devs;
    while (*p && (*p)->key != key)
        p = &(*p)->next;
    struct dev *d = *p;
    if (d)
        *p = d->next;
    pthread_mutex_unlock(&lock);
    if (!d)
        return;
    d->DestroyDevice(device, alloc);
    while (d->queues) {
        struct queue *q = d->queues;
        d->queues = q->next;
        free(q);
    }
    pthread_mutex_destroy(&d->submit);
    free(d);
}

static void remember(struct dev *d, VkQueue queue)
{
    pthread_mutex_lock(&d->submit);
    struct queue *q = d->queues;
    while (q && q->handle != queue)
        q = q->next;
    if (!q) {
        q = calloc(1, sizeof(*q));
        q->handle = queue;
        q->next = d->queues;
        d->queues = q;
    }
    pthread_mutex_unlock(&d->submit);
}

static VKAPI_ATTR void VKAPI_CALL GetDeviceQueue(VkDevice device, uint32_t family, uint32_t index, VkQueue *out)
{
    struct dev *d = dev_of(device);
    d->GetDeviceQueue(device, family, index, out);
    if (*out)
        remember(d, *out);
}

static VKAPI_ATTR void VKAPI_CALL GetDeviceQueue2(VkDevice device, const VkDeviceQueueInfo2 *info, VkQueue *out)
{
    struct dev *d = dev_of(device);
    d->GetDeviceQueue2(device, info, out);
    if (*out)
        remember(d, *out);
}

static VKAPI_ATTR VkResult VKAPI_CALL QueueSubmit(VkQueue queue, uint32_t n, const VkSubmitInfo *s, VkFence fence)
{
    struct dev *d = dev_of(queue);
    pthread_mutex_lock(&d->submit);
    VkResult r = d->QueueSubmit(queue, n, s, fence);
    pthread_mutex_unlock(&d->submit);
    return r;
}

static VKAPI_ATTR VkResult VKAPI_CALL QueueSubmit2(VkQueue queue, uint32_t n, const VkSubmitInfo2 *s, VkFence fence)
{
    struct dev *d = dev_of(queue);
    pthread_mutex_lock(&d->submit);
    VkResult r = d->QueueSubmit2(queue, n, s, fence);
    pthread_mutex_unlock(&d->submit);
    return r;
}

static VKAPI_ATTR VkResult VKAPI_CALL QueuePresentKHR(VkQueue queue, const VkPresentInfoKHR *info)
{
    struct dev *d = dev_of(queue);
    pthread_mutex_lock(&d->submit);
    VkResult r = d->QueuePresentKHR(queue, info);
    pthread_mutex_unlock(&d->submit);
    return r;
}

static VKAPI_ATTR VkResult VKAPI_CALL CreateSemaphore(VkDevice device, const VkSemaphoreCreateInfo *info,
                                                      const VkAllocationCallbacks *alloc, VkSemaphore *out)
{
    struct dev *d = dev_of(device);
    if (!d->emulate)
        return d->CreateSemaphore(device, info, alloc, out);
    /* The driver cannot export anything: the export request leaves the chain, the semaphore type
     * stays (gamescope makes timeline semaphores too). */
    VkSemaphoreCreateInfo copy = *info;
    VkSemaphoreTypeCreateInfo type;
    copy.pNext = NULL;
    for (const VkBaseInStructure *p = info->pNext; p; p = p->pNext)
        if (p->sType == VK_STRUCTURE_TYPE_SEMAPHORE_TYPE_CREATE_INFO) {
            type = *(const VkSemaphoreTypeCreateInfo *)p;
            type.pNext = NULL;
            copy.pNext = &type;
        }
    return d->CreateSemaphore(device, &copy, alloc, out);
}

/* The semaphore's pending signal is consumed (sync-file export resets a binary semaphore) and the
 * device drained, so -1, "already signaled", is the truth. */
static VKAPI_ATTR VkResult VKAPI_CALL GetSemaphoreFdKHR(VkDevice device, const VkSemaphoreGetFdInfoKHR *info, int *fd)
{
    struct dev *d = dev_of(device);
    if (info->handleType != VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT)
        return VK_ERROR_INVALID_EXTERNAL_HANDLE;
    pthread_mutex_lock(&d->submit);
    VkResult r = VK_SUCCESS;
    if (d->queues) {
        VkPipelineStageFlags stage = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
        VkSubmitInfo wait = { .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO, .waitSemaphoreCount = 1,
                              .pWaitSemaphores = &info->semaphore, .pWaitDstStageMask = &stage };
        r = d->QueueSubmit(d->queues->handle, 1, &wait, VK_NULL_HANDLE);
    }
    pthread_mutex_unlock(&d->submit);
    d->DeviceWaitIdle(device);
    *fd = -1;
    return r;
}

/* An imported sync file is waited for here, on the CPU; then the semaphore is signaled. */
static VKAPI_ATTR VkResult VKAPI_CALL ImportSemaphoreFdKHR(VkDevice device, const VkImportSemaphoreFdInfoKHR *info)
{
    struct dev *d = dev_of(device);
    if (info->handleType != VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT)
        return VK_ERROR_INVALID_EXTERNAL_HANDLE;
    if (info->fd >= 0) {
        struct pollfd p = { .fd = info->fd, .events = POLLIN };
        while (poll(&p, 1, -1) < 0)
            ;
        close(info->fd);
    }
    if (!d->queues)
        return VK_SUCCESS;
    pthread_mutex_lock(&d->submit);
    VkSubmitInfo signal = { .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO, .signalSemaphoreCount = 1,
                            .pSignalSemaphores = &info->semaphore };
    VkResult r = d->QueueSubmit(d->queues->handle, 1, &signal, VK_NULL_HANDLE);
    pthread_mutex_unlock(&d->submit);
    return r;
}

/* ---- dispatch ---- */

static VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL GetDeviceProcAddr(VkDevice device, const char *name);

#define HOOK(fn) if (!strcmp(name, "vk" #fn)) return (PFN_vkVoidFunction)fn;

static VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL GetDeviceProcAddr(VkDevice device, const char *name)
{
    HOOK(GetDeviceProcAddr)
    HOOK(DestroyDevice)
    HOOK(GetDeviceQueue)
    HOOK(GetDeviceQueue2)
    HOOK(QueueSubmit)
    HOOK(QueuePresentKHR)
    HOOK(CreateSemaphore)
    struct dev *d = dev_of(device);
    if (!strcmp(name, "vkQueueSubmit2") || !strcmp(name, "vkQueueSubmit2KHR"))
        return d->QueueSubmit2 ? (PFN_vkVoidFunction)QueueSubmit2 : NULL;
    if (d->emulate) {
        HOOK(GetSemaphoreFdKHR)
        HOOK(ImportSemaphoreFdKHR)
    }
    return d->gdpa(device, name);
}

static VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL GetInstanceProcAddr(VkInstance instance, const char *name)
{
    if (!strcmp(name, "vkGetInstanceProcAddr"))
        return (PFN_vkVoidFunction)GetInstanceProcAddr;
    HOOK(CreateInstance)
    HOOK(DestroyInstance)
    HOOK(CreateDevice)
    HOOK(EnumerateDeviceExtensionProperties)
    HOOK(GetPhysicalDeviceExternalSemaphoreProperties)
    if (!strcmp(name, "vkGetPhysicalDeviceExternalSemaphorePropertiesKHR"))
        return (PFN_vkVoidFunction)GetPhysicalDeviceExternalSemaphoreProperties;
    if (!strcmp(name, "vkGetDeviceProcAddr"))
        return (PFN_vkVoidFunction)GetDeviceProcAddr;
    /* Device functions asked for through the instance. */
    if (!strcmp(name, "vkGetSemaphoreFdKHR"))
        return (PFN_vkVoidFunction)GetSemaphoreFdKHR;
    if (!strcmp(name, "vkImportSemaphoreFdKHR"))
        return (PFN_vkVoidFunction)ImportSemaphoreFdKHR;
    HOOK(DestroyDevice)
    HOOK(GetDeviceQueue)
    HOOK(GetDeviceQueue2)
    HOOK(QueueSubmit)
    HOOK(QueuePresentKHR)
    HOOK(CreateSemaphore)
    if (!instance)
        return NULL;
    struct inst *i = inst_of(instance);
    return i ? i->gipa(instance, name) : NULL;
}

EXPORT VKAPI_ATTR VkResult VKAPI_CALL vkNegotiateLoaderLayerInterfaceVersion(VkNegotiateLayerInterface *v)
{
    if (v->loaderLayerInterfaceVersion > 2)
        v->loaderLayerInterfaceVersion = 2;
    v->pfnGetInstanceProcAddr = GetInstanceProcAddr;
    v->pfnGetDeviceProcAddr = GetDeviceProcAddr;
    v->pfnGetPhysicalDeviceProcAddr = NULL;
    return VK_SUCCESS;
}

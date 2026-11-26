/*
 * VK_LAYER_DROIDDECK_pcachesim: a devloop-only stand-in for a driver whose VkPipelineCache keeps pipelines.
 *
 * Lavapipe's pipeline cache never grows (vkGetPipelineCacheData always reports its header alone), so on the
 * devloop the engine's pipeline-cache saving can't be seen working. This layer makes the cache look like a
 * real driver's to the app: every pipeline created adds 1 KiB to what vkGetPipelineCacheData reports (zero
 * bytes), and data handed to vkCreatePipelineCache is counted and then dropped (the driver below gets an
 * empty cache, so it never sees made-up data). On stderr, prefixed "droiddeck-pcachesim:", it says how much
 * it was handed at create and how big the cache was at each data call.
 *
 * Build: cc -shared -fPIC -O2 -I <vulkan include> -o libVkLayer_droiddeck_pcachesim.so pcachesim_layer.c
 * Use:   VK_ADD_LAYER_PATH=<dir with the .so and VkLayer_droiddeck_pcachesim.json>
 *        VK_INSTANCE_LAYERS=VK_LAYER_DROIDDECK_pcachesim
 * Not shipped: nothing on the phone loads it.
 */
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <vulkan/vk_layer.h>
#include <vulkan/vulkan.h>

#define EXPORT __attribute__((visibility("default")))
#define HEADER 32u
#define PER_PIPELINE 1024u

static PFN_vkGetInstanceProcAddr next_gipa;
static PFN_vkGetDeviceProcAddr next_gdpa;
static PFN_vkCreatePipelineCache next_create_cache;
static PFN_vkCreateGraphicsPipelines next_graphics;
static PFN_vkCreateComputePipelines next_compute;
static size_t loaded, made;
static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;

static VkResult VKAPI_CALL create_cache(VkDevice device, const VkPipelineCacheCreateInfo *info,
                                        const VkAllocationCallbacks *allocator, VkPipelineCache *cache) {
    VkPipelineCacheCreateInfo empty = *info;
    pthread_mutex_lock(&lock);
    loaded = info->initialDataSize > HEADER ? info->initialDataSize - HEADER : 0;
    pthread_mutex_unlock(&lock);
    fprintf(stderr, "droiddeck-pcachesim: cache created with %zu bytes of data\n", info->initialDataSize);
    empty.initialDataSize = 0;
    empty.pInitialData = NULL;
    return next_create_cache(device, &empty, allocator, cache);
}

static VkResult VKAPI_CALL get_data(VkDevice device, VkPipelineCache cache, size_t *size, void *data) {
    (void)device;
    (void)cache;
    pthread_mutex_lock(&lock);
    const size_t total = HEADER + loaded + made * PER_PIPELINE;
    pthread_mutex_unlock(&lock);
    if (!data) {
        *size = total;
        return VK_SUCCESS;
    }
    const size_t n = *size < total ? *size : total;
    memset(data, 0, n);
    *size = n;
    fprintf(stderr, "droiddeck-pcachesim: cache data %zu bytes written out\n", n);
    return n < total ? VK_INCOMPLETE : VK_SUCCESS;
}

static VkResult VKAPI_CALL graphics(VkDevice device, VkPipelineCache cache, uint32_t count,
                                    const VkGraphicsPipelineCreateInfo *infos, const VkAllocationCallbacks *allocator,
                                    VkPipeline *pipelines) {
    VkResult r = next_graphics(device, cache, count, infos, allocator, pipelines);
    pthread_mutex_lock(&lock);
    made += count;
    pthread_mutex_unlock(&lock);
    return r;
}

static VkResult VKAPI_CALL compute(VkDevice device, VkPipelineCache cache, uint32_t count,
                                   const VkComputePipelineCreateInfo *infos, const VkAllocationCallbacks *allocator,
                                   VkPipeline *pipelines) {
    VkResult r = next_compute(device, cache, count, infos, allocator, pipelines);
    pthread_mutex_lock(&lock);
    made += count;
    pthread_mutex_unlock(&lock);
    return r;
}

static VkResult VKAPI_CALL create_instance(const VkInstanceCreateInfo *info, const VkAllocationCallbacks *allocator,
                                           VkInstance *instance) {
    VkLayerInstanceCreateInfo *chain = (VkLayerInstanceCreateInfo *)info->pNext;
    while (chain && !(chain->sType == VK_STRUCTURE_TYPE_LOADER_INSTANCE_CREATE_INFO && chain->function == VK_LAYER_LINK_INFO))
        chain = (VkLayerInstanceCreateInfo *)chain->pNext;
    if (!chain || !chain->u.pLayerInfo) return VK_ERROR_INITIALIZATION_FAILED;
    next_gipa = chain->u.pLayerInfo->pfnNextGetInstanceProcAddr;
    chain->u.pLayerInfo = chain->u.pLayerInfo->pNext;
    PFN_vkCreateInstance create = (PFN_vkCreateInstance)next_gipa(NULL, "vkCreateInstance");
    return create ? create(info, allocator, instance) : VK_ERROR_INITIALIZATION_FAILED;
}

static VkResult VKAPI_CALL create_device(VkPhysicalDevice physical, const VkDeviceCreateInfo *info,
                                         const VkAllocationCallbacks *allocator, VkDevice *device) {
    VkLayerDeviceCreateInfo *chain = (VkLayerDeviceCreateInfo *)info->pNext;
    while (chain && !(chain->sType == VK_STRUCTURE_TYPE_LOADER_DEVICE_CREATE_INFO && chain->function == VK_LAYER_LINK_INFO))
        chain = (VkLayerDeviceCreateInfo *)chain->pNext;
    if (!chain || !chain->u.pLayerInfo) return VK_ERROR_INITIALIZATION_FAILED;
    PFN_vkGetInstanceProcAddr gipa = chain->u.pLayerInfo->pfnNextGetInstanceProcAddr;
    next_gdpa = chain->u.pLayerInfo->pfnNextGetDeviceProcAddr;
    chain->u.pLayerInfo = chain->u.pLayerInfo->pNext;
    PFN_vkCreateDevice create = (PFN_vkCreateDevice)gipa(NULL, "vkCreateDevice");
    if (!create) return VK_ERROR_INITIALIZATION_FAILED;
    VkResult r = create(physical, info, allocator, device);
    if (r != VK_SUCCESS) return r;
    next_create_cache = (PFN_vkCreatePipelineCache)next_gdpa(*device, "vkCreatePipelineCache");
    next_graphics = (PFN_vkCreateGraphicsPipelines)next_gdpa(*device, "vkCreateGraphicsPipelines");
    next_compute = (PFN_vkCreateComputePipelines)next_gdpa(*device, "vkCreateComputePipelines");
    return r;
}

static PFN_vkVoidFunction VKAPI_CALL get_device_proc(VkDevice device, const char *name) {
    if (!strcmp(name, "vkGetDeviceProcAddr")) return (PFN_vkVoidFunction)get_device_proc;
    if (!strcmp(name, "vkCreatePipelineCache")) return (PFN_vkVoidFunction)create_cache;
    if (!strcmp(name, "vkGetPipelineCacheData")) return (PFN_vkVoidFunction)get_data;
    if (!strcmp(name, "vkCreateGraphicsPipelines")) return (PFN_vkVoidFunction)graphics;
    if (!strcmp(name, "vkCreateComputePipelines")) return (PFN_vkVoidFunction)compute;
    return next_gdpa ? next_gdpa(device, name) : NULL;
}

static PFN_vkVoidFunction VKAPI_CALL get_instance_proc(VkInstance instance, const char *name) {
    if (!strcmp(name, "vkGetInstanceProcAddr")) return (PFN_vkVoidFunction)get_instance_proc;
    if (!strcmp(name, "vkCreateInstance")) return (PFN_vkVoidFunction)create_instance;
    if (!strcmp(name, "vkCreateDevice")) return (PFN_vkVoidFunction)create_device;
    if (!strcmp(name, "vkGetDeviceProcAddr")) return (PFN_vkVoidFunction)get_device_proc;
    return next_gipa ? next_gipa(instance, name) : NULL;
}

EXPORT VKAPI_ATTR VkResult VKAPI_CALL vkNegotiateLoaderLayerInterfaceVersion(VkNegotiateLayerInterface *negotiate) {
    if (!negotiate || negotiate->loaderLayerInterfaceVersion < 2) return VK_ERROR_INITIALIZATION_FAILED;
    negotiate->loaderLayerInterfaceVersion = 2;
    negotiate->pfnGetInstanceProcAddr = get_instance_proc;
    negotiate->pfnGetDeviceProcAddr = get_device_proc;
    negotiate->pfnGetPhysicalDeviceProcAddr = NULL;
    return VK_SUCCESS;
}

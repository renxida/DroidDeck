/* Drives VK_LAYER_DROIDDECK_vkcount on lavapipe: 60 frames on a headless swapchain, each with one
 * submit of one command buffer holding five buffer copies and a barrier. It holds 8 MiB of device memory
 * after freeing a 4 MiB allocation, and one 1024x1024 BC3 image (1 MiB compressed, 4 MiB decoded) after
 * destroying a BC1 one, and a 1 MiB RGBA image bound into that memory. See test_vkcount_layer.py. */
#include <vulkan/vulkan.h>
#include <stdio.h>
#include <stdlib.h>
#define CK(x) do { VkResult r_ = (x); if (r_) { printf("FAIL %s = %d\n", #x, r_); exit(1); } } while (0)
int main(void) {
    const char *iext[] = {"VK_KHR_surface", "VK_EXT_headless_surface"};
    VkInstanceCreateInfo ici = {VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO, .enabledExtensionCount = 2, .ppEnabledExtensionNames = iext};
    VkInstance inst; CK(vkCreateInstance(&ici, NULL, &inst));
    uint32_t n = 1; VkPhysicalDevice pd; vkEnumeratePhysicalDevices(inst, &n, &pd);
    VkHeadlessSurfaceCreateInfoEXT hs = {VK_STRUCTURE_TYPE_HEADLESS_SURFACE_CREATE_INFO_EXT};
    PFN_vkCreateHeadlessSurfaceEXT chs = (void *)vkGetInstanceProcAddr(inst, "vkCreateHeadlessSurfaceEXT");
    VkSurfaceKHR surf; CK(chs(inst, &hs, NULL, &surf));
    float p = 1; VkDeviceQueueCreateInfo q = {VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO, .queueCount = 1, .pQueuePriorities = &p};
    const char *dext[] = {"VK_KHR_swapchain"};
    VkDeviceCreateInfo dci = {VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO, .queueCreateInfoCount = 1, .pQueueCreateInfos = &q, .enabledExtensionCount = 1, .ppEnabledExtensionNames = dext};
    VkDevice dev; CK(vkCreateDevice(pd, &dci, NULL, &dev));
    VkQueue queue; vkGetDeviceQueue(dev, 0, 0, &queue);
    VkSwapchainCreateInfoKHR sci = {VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR, .surface = surf, .minImageCount = 3,
        .imageFormat = VK_FORMAT_B8G8R8A8_UNORM, .imageColorSpace = VK_COLOR_SPACE_SRGB_NONLINEAR_KHR, .imageExtent = {64, 64},
        .imageArrayLayers = 1, .imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
        .preTransform = VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR, .compositeAlpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR, .presentMode = VK_PRESENT_MODE_FIFO_KHR};
    VkSwapchainKHR sc; CK(vkCreateSwapchainKHR(dev, &sci, NULL, &sc));
    VkCommandPoolCreateInfo pci = {VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO, .flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT};
    VkCommandPool pool; CK(vkCreateCommandPool(dev, &pci, NULL, &pool));
    VkCommandBufferAllocateInfo cai = {VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO, .commandPool = pool, .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY, .commandBufferCount = 1};
    VkCommandBuffer cb; CK(vkAllocateCommandBuffers(dev, &cai, &cb));
    VkBufferCreateInfo bci = {VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO, .size = 4096, .usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT};
    VkBuffer b1, b2; CK(vkCreateBuffer(dev, &bci, NULL, &b1)); CK(vkCreateBuffer(dev, &bci, NULL, &b2));
    VkMemoryRequirements mr; vkGetBufferMemoryRequirements(dev, b1, &mr);
    VkMemoryAllocateInfo mai = {VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, .allocationSize = 4 << 20, .memoryTypeIndex = 0};
    VkDeviceMemory gone; CK(vkAllocateMemory(dev, &mai, NULL, &gone));
    mai.allocationSize = 8 << 20;
    VkDeviceMemory mem; CK(vkAllocateMemory(dev, &mai, NULL, &mem));
    vkFreeMemory(dev, gone, NULL);
    VkImageCreateInfo ii = {VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO, .imageType = VK_IMAGE_TYPE_2D,
        .format = VK_FORMAT_BC1_RGBA_UNORM_BLOCK, .extent = {512, 512, 1}, .mipLevels = 10, .arrayLayers = 1,
        .samples = VK_SAMPLE_COUNT_1_BIT, .tiling = VK_IMAGE_TILING_OPTIMAL,
        .usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT};
    VkImage bc1; CK(vkCreateImage(dev, &ii, NULL, &bc1));
    vkDestroyImage(dev, bc1, NULL);
    ii.format = VK_FORMAT_BC3_UNORM_BLOCK; ii.extent = (VkExtent3D){1024, 1024, 1}; ii.mipLevels = 1;
    VkImage bc3; CK(vkCreateImage(dev, &ii, NULL, &bc3));
    ii.format = VK_FORMAT_R8G8B8A8_UNORM; ii.extent = (VkExtent3D){512, 512, 1};
    VkImage rgba; CK(vkCreateImage(dev, &ii, NULL, &rgba));
    CK(vkBindImageMemory(dev, rgba, mem, 1 << 20));
    vkBindBufferMemory(dev, b1, mem, 0); vkBindBufferMemory(dev, b2, mem, 4096);
    VkFenceCreateInfo fci = {VK_STRUCTURE_TYPE_FENCE_CREATE_INFO}; VkFence fence, af; vkCreateFence(dev, &fci, NULL, &fence); vkCreateFence(dev, &fci, NULL, &af);
    for (int f = 0; f < 60; f++) {
        uint32_t idx; CK(vkAcquireNextImageKHR(dev, sc, UINT64_MAX, VK_NULL_HANDLE, af, &idx));
        vkWaitForFences(dev, 1, &af, 1, UINT64_MAX); vkResetFences(dev, 1, &af);
        VkCommandBufferBeginInfo bi = {VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
        vkBeginCommandBuffer(cb, &bi);
        VkBufferCopy rg = {0, 0, 4096};
        for (int k = 0; k < 5; k++) vkCmdCopyBuffer(cb, b1, b2, 1, &rg);
        vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, NULL, 0, NULL, 0, NULL);
        vkEndCommandBuffer(cb);
        VkSubmitInfo si = {VK_STRUCTURE_TYPE_SUBMIT_INFO, .commandBufferCount = 1, .pCommandBuffers = &cb};
        CK(vkQueueSubmit(queue, 1, &si, fence)); vkWaitForFences(dev, 1, &fence, 1, UINT64_MAX); vkResetFences(dev, 1, &fence);
        VkPresentInfoKHR pi = {VK_STRUCTURE_TYPE_PRESENT_INFO_KHR, .swapchainCount = 1, .pSwapchains = &sc, .pImageIndices = &idx};
        CK(vkQueuePresentKHR(queue, &pi));
    }
    vkDeviceWaitIdle(dev);
    (void)bc3; (void)rgba; /* left alive, like mem, so the final line at vkDestroyDevice still counts it */
    vkDestroySwapchainKHR(dev, sc, NULL); vkDestroyDevice(dev, NULL); vkDestroyInstance(inst, NULL);
    puts("ok");
}

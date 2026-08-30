#include "andy_mirror_internal.h"
#include "overlay_shaders.h"

#include <X11/Xutil.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <vulkan/vulkan.h>

#define ANDY_VK_MAX_IMAGES 8

struct AndyVkSwapchain {
    Window window;
    VkSurfaceKHR surface;
    VkSwapchainKHR swapchain;
    VkFormat format;
    VkExtent2D extent;
    uint32_t image_count;
    VkImage images[ANDY_VK_MAX_IMAGES];
    VkImageView views[ANDY_VK_MAX_IMAGES];
    VkFramebuffer framebuffers[ANDY_VK_MAX_IMAGES];
    VkCommandBuffer cmd;
    VkSemaphore image_available;
    VkSemaphore render_finished;
    VkFence inflight;
    int pending_w;
    int pending_h;
    bool dirty;
    int tex_w;
    int tex_h;
    bool tex_bgra;
    VkImage y_image;
    VkImage uv_image;
    VkImageView y_view;
    VkImageView uv_view;
    VkDeviceMemory y_mem;
    VkDeviceMemory uv_mem;
    VkBuffer staging;
    VkDeviceMemory staging_mem;
    void *staging_mapped;
    size_t staging_size;
    VkBuffer ubo;
    VkDeviceMemory ubo_mem;
    void *ubo_mapped;
    VkDescriptorSet dset;
};

static struct {
    VkInstance instance;
    VkPhysicalDevice phys;
    VkDevice device;
    VkQueue queue;
    uint32_t queue_family;
    VkCommandPool cmd_pool;
    VkRenderPass render_pass;
    VkPipeline pipeline;
    VkPipelineLayout pipeline_layout;
    VkDescriptorSetLayout dset_layout;
    VkDescriptorPool dset_pool;
    VkSampler sampler;
    VkImage dummy_uv;
    VkImageView dummy_uv_view;
    VkDeviceMemory dummy_uv_mem;
    pthread_mutex_t lock;
    bool ready;
} vk = {.lock = PTHREAD_MUTEX_INITIALIZER};

static uint32_t find_memory_type(uint32_t filter, VkMemoryPropertyFlags props) {
    VkPhysicalDeviceMemoryProperties mem;
    vkGetPhysicalDeviceMemoryProperties(vk.phys, &mem);
    for (uint32_t i = 0; i < mem.memoryTypeCount; ++i) {
        if ((filter & (1u << i)) && (mem.memoryTypes[i].propertyFlags & props) == props) return i;
    }
    return UINT32_MAX;
}

static void destroy_image(VkImage *image, VkImageView *view, VkDeviceMemory *memory) {
    if (*view) vkDestroyImageView(vk.device, *view, NULL);
    if (*image) vkDestroyImage(vk.device, *image, NULL);
    if (*memory) vkFreeMemory(vk.device, *memory, NULL);
    *view = VK_NULL_HANDLE;
    *image = VK_NULL_HANDLE;
    *memory = VK_NULL_HANDLE;
}

static bool create_image(VkFormat format, uint32_t width, uint32_t height, VkImageUsageFlags usage, VkImage *image,
                         VkDeviceMemory *memory, VkImageView *view, VkImageAspectFlags aspect) {
    VkImageCreateInfo info = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO,
        .imageType = VK_IMAGE_TYPE_2D,
        .format = format,
        .extent = {width, height, 1},
        .mipLevels = 1,
        .arrayLayers = 1,
        .samples = VK_SAMPLE_COUNT_1_BIT,
        .tiling = VK_IMAGE_TILING_OPTIMAL,
        .usage = usage,
        .sharingMode = VK_SHARING_MODE_EXCLUSIVE,
        .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED,
    };
    if (vkCreateImage(vk.device, &info, NULL, image) != VK_SUCCESS) return false;
    VkMemoryRequirements req;
    vkGetImageMemoryRequirements(vk.device, *image, &req);
    uint32_t type = find_memory_type(req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (type == UINT32_MAX) {
        type = find_memory_type(req.memoryTypeBits, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT);
    }
    VkMemoryAllocateInfo alloc = {
        .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
        .allocationSize = req.size,
        .memoryTypeIndex = type,
    };
    if (type == UINT32_MAX || vkAllocateMemory(vk.device, &alloc, NULL, memory) != VK_SUCCESS ||
        vkBindImageMemory(vk.device, *image, *memory, 0) != VK_SUCCESS) {
        destroy_image(image, view, memory);
        return false;
    }
    VkImageViewCreateInfo view_info = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,
        .image = *image,
        .viewType = VK_IMAGE_VIEW_TYPE_2D,
        .format = format,
        .subresourceRange = {aspect, 0, 1, 0, 1},
    };
    if (vkCreateImageView(vk.device, &view_info, NULL, view) != VK_SUCCESS) {
        destroy_image(image, view, memory);
        return false;
    }
    return true;
}

static bool create_buffer(VkDeviceSize size, VkBufferUsageFlags usage, VkMemoryPropertyFlags props, VkBuffer *buffer,
                          VkDeviceMemory *memory, void **mapped) {
    VkBufferCreateInfo info = {
        .sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO,
        .size = size,
        .usage = usage,
        .sharingMode = VK_SHARING_MODE_EXCLUSIVE,
    };
    if (vkCreateBuffer(vk.device, &info, NULL, buffer) != VK_SUCCESS) return false;
    VkMemoryRequirements req;
    vkGetBufferMemoryRequirements(vk.device, *buffer, &req);
    uint32_t type = find_memory_type(req.memoryTypeBits, props);
    VkMemoryAllocateInfo alloc = {
        .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
        .allocationSize = req.size,
        .memoryTypeIndex = type,
    };
    if (type == UINT32_MAX || vkAllocateMemory(vk.device, &alloc, NULL, memory) != VK_SUCCESS ||
        vkBindBufferMemory(vk.device, *buffer, *memory, 0) != VK_SUCCESS) {
        if (*buffer) vkDestroyBuffer(vk.device, *buffer, NULL);
        if (*memory) vkFreeMemory(vk.device, *memory, NULL);
        *buffer = VK_NULL_HANDLE;
        *memory = VK_NULL_HANDLE;
        return false;
    }
    if (mapped) {
        if (vkMapMemory(vk.device, *memory, 0, req.size, 0, mapped) != VK_SUCCESS) return false;
    }
    return true;
}

static void image_barrier(VkCommandBuffer cmd, VkImage image, VkImageLayout old_layout, VkImageLayout new_layout,
                          VkAccessFlags src_access, VkAccessFlags dst_access, VkPipelineStageFlags src_stage,
                          VkPipelineStageFlags dst_stage) {
    VkImageMemoryBarrier barrier = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
        .srcAccessMask = src_access,
        .dstAccessMask = dst_access,
        .oldLayout = old_layout,
        .newLayout = new_layout,
        .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .image = image,
        .subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1},
    };
    vkCmdPipelineBarrier(cmd, src_stage, dst_stage, 0, 0, NULL, 0, NULL, 1, &barrier);
}

static bool create_dummy_uv(void) {
    if (!create_image(VK_FORMAT_R8G8_UNORM, 1, 1,
                      VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, &vk.dummy_uv, &vk.dummy_uv_mem,
                      &vk.dummy_uv_view, VK_IMAGE_ASPECT_COLOR_BIT)) {
        return false;
    }
    VkBuffer staging = VK_NULL_HANDLE;
    VkDeviceMemory staging_mem = VK_NULL_HANDLE;
    void *mapped = NULL;
    if (!create_buffer(2, VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                       VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, &staging,
                       &staging_mem, &mapped)) {
        return false;
    }
    uint8_t chroma[2] = {128, 128};
    memcpy(mapped, chroma, 2);
    VkCommandBufferAllocateInfo alloc = {
        .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
        .commandPool = vk.cmd_pool,
        .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY,
        .commandBufferCount = 1,
    };
    VkCommandBuffer cmd;
    vkAllocateCommandBuffers(vk.device, &alloc, &cmd);
    VkCommandBufferBeginInfo begin = {.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
                                      .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT};
    vkBeginCommandBuffer(cmd, &begin);
    image_barrier(cmd, vk.dummy_uv, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 0,
                  VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
    VkBufferImageCopy copy = {
        .imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1},
        .imageExtent = {1, 1, 1},
    };
    vkCmdCopyBufferToImage(cmd, staging, vk.dummy_uv, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &copy);
    image_barrier(cmd, vk.dummy_uv, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                  VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                  VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT);
    vkEndCommandBuffer(cmd);
    VkSubmitInfo submit = {.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO, .commandBufferCount = 1, .pCommandBuffers = &cmd};
    vkQueueSubmit(vk.queue, 1, &submit, VK_NULL_HANDLE);
    vkQueueWaitIdle(vk.queue);
    vkFreeCommandBuffers(vk.device, vk.cmd_pool, 1, &cmd);
    vkDestroyBuffer(vk.device, staging, NULL);
    vkFreeMemory(vk.device, staging_mem, NULL);
    return true;
}

static bool create_pipeline(void) {
    VkShaderModuleCreateInfo vert_info = {
        .sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO,
        .codeSize = overlay_vert_spv_len,
        .pCode = overlay_vert_spv,
    };
    VkShaderModuleCreateInfo frag_info = {
        .sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO,
        .codeSize = overlay_frag_spv_len,
        .pCode = overlay_frag_spv,
    };
    VkShaderModule vert = VK_NULL_HANDLE;
    VkShaderModule frag = VK_NULL_HANDLE;
    if (vkCreateShaderModule(vk.device, &vert_info, NULL, &vert) != VK_SUCCESS ||
        vkCreateShaderModule(vk.device, &frag_info, NULL, &frag) != VK_SUCCESS) {
        if (vert) vkDestroyShaderModule(vk.device, vert, NULL);
        if (frag) vkDestroyShaderModule(vk.device, frag, NULL);
        return false;
    }
    VkPipelineShaderStageCreateInfo stages[2] = {
        {.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
         .stage = VK_SHADER_STAGE_VERTEX_BIT,
         .module = vert,
         .pName = "main"},
        {.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
         .stage = VK_SHADER_STAGE_FRAGMENT_BIT,
         .module = frag,
         .pName = "main"},
    };
    VkPipelineVertexInputStateCreateInfo vertex = {.sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO};
    VkPipelineInputAssemblyStateCreateInfo input = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO,
        .topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP,
    };
    VkPipelineViewportStateCreateInfo viewport = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO,
        .viewportCount = 1,
        .scissorCount = 1,
    };
    VkPipelineRasterizationStateCreateInfo raster = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO,
        .polygonMode = VK_POLYGON_MODE_FILL,
        .cullMode = VK_CULL_MODE_NONE,
        .frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE,
        .lineWidth = 1.0f,
    };
    VkPipelineMultisampleStateCreateInfo ms = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO,
        .rasterizationSamples = VK_SAMPLE_COUNT_1_BIT,
    };
    VkPipelineColorBlendAttachmentState blend_attach = {
        .colorWriteMask = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT |
                          VK_COLOR_COMPONENT_A_BIT,
    };
    VkPipelineColorBlendStateCreateInfo blend = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO,
        .attachmentCount = 1,
        .pAttachments = &blend_attach,
    };
    VkDynamicState dynamics[] = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR};
    VkPipelineDynamicStateCreateInfo dynamic = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO,
        .dynamicStateCount = 2,
        .pDynamicStates = dynamics,
    };
    VkGraphicsPipelineCreateInfo pipe = {
        .sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO,
        .stageCount = 2,
        .pStages = stages,
        .pVertexInputState = &vertex,
        .pInputAssemblyState = &input,
        .pViewportState = &viewport,
        .pRasterizationState = &raster,
        .pMultisampleState = &ms,
        .pColorBlendState = &blend,
        .pDynamicState = &dynamic,
        .layout = vk.pipeline_layout,
        .renderPass = vk.render_pass,
    };
    VkResult result = vkCreateGraphicsPipelines(vk.device, VK_NULL_HANDLE, 1, &pipe, NULL, &vk.pipeline);
    vkDestroyShaderModule(vk.device, vert, NULL);
    vkDestroyShaderModule(vk.device, frag, NULL);
    return result == VK_SUCCESS;
}

static bool pick_device(void) {
    uint32_t count = 0;
    vkEnumeratePhysicalDevices(vk.instance, &count, NULL);
    if (!count) return false;
    VkPhysicalDevice *devices = (VkPhysicalDevice *) calloc(count, sizeof(VkPhysicalDevice));
    if (!devices) return false;
    vkEnumeratePhysicalDevices(vk.instance, &count, devices);
    VkPhysicalDevice chosen = VK_NULL_HANDLE;
    uint32_t family = UINT32_MAX;
    int best_score = -1;
    for (uint32_t i = 0; i < count; ++i) {
        VkPhysicalDeviceProperties props;
        vkGetPhysicalDeviceProperties(devices[i], &props);
        uint32_t fam_count = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(devices[i], &fam_count, NULL);
        VkQueueFamilyProperties *fams = (VkQueueFamilyProperties *) calloc(fam_count, sizeof(VkQueueFamilyProperties));
        vkGetPhysicalDeviceQueueFamilyProperties(devices[i], &fam_count, fams);
        Visual *visual = DefaultVisual(andy_x11_display(), DefaultScreen(andy_x11_display()));
        const VisualID visual_id = visual ? XVisualIDFromVisual(visual) : 0;
        for (uint32_t f = 0; f < fam_count; ++f) {
            if (!(fams[f].queueFlags & VK_QUEUE_GRAPHICS_BIT)) continue;
            VkBool32 present = vkGetPhysicalDeviceXlibPresentationSupportKHR(
                devices[i], f, andy_x11_display(), visual_id);
            if (!present) continue;
            int score = 0;
            if (props.deviceType == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU) score += 1000;
            if (strstr(props.deviceName, "NVIDIA") || strstr(props.deviceName, "nvidia")) score += 100;
            if (score > best_score) {
                best_score = score;
                chosen = devices[i];
                family = f;
            }
        }
        free(fams);
    }
    free(devices);
    if (!chosen) return false;
    vk.phys = chosen;
    vk.queue_family = family;
    return true;
}

bool andy_vk_init(void) {
    pthread_mutex_lock(&vk.lock);
    if (vk.ready) {
        pthread_mutex_unlock(&vk.lock);
        return true;
    }
    if (!andy_x11_init() || !andy_x11_display()) {
        pthread_mutex_unlock(&vk.lock);
        return false;
    }
    const char *exts[] = {VK_KHR_SURFACE_EXTENSION_NAME, VK_KHR_XLIB_SURFACE_EXTENSION_NAME};
    VkApplicationInfo app = {
        .sType = VK_STRUCTURE_TYPE_APPLICATION_INFO,
        .pApplicationName = "Andy Live",
        .apiVersion = VK_API_VERSION_1_1,
    };
    VkInstanceCreateInfo inst = {
        .sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,
        .pApplicationInfo = &app,
        .enabledExtensionCount = 2,
        .ppEnabledExtensionNames = exts,
    };
    if (vkCreateInstance(&inst, NULL, &vk.instance) != VK_SUCCESS || !pick_device()) {
        pthread_mutex_unlock(&vk.lock);
        return false;
    }
    float priority = 1.0f;
    VkDeviceQueueCreateInfo qinfo = {
        .sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,
        .queueFamilyIndex = vk.queue_family,
        .queueCount = 1,
        .pQueuePriorities = &priority,
    };
    const char *dev_exts[] = {VK_KHR_SWAPCHAIN_EXTENSION_NAME};
    VkDeviceCreateInfo dinfo = {
        .sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,
        .queueCreateInfoCount = 1,
        .pQueueCreateInfos = &qinfo,
        .enabledExtensionCount = 1,
        .ppEnabledExtensionNames = dev_exts,
    };
    if (vkCreateDevice(vk.phys, &dinfo, NULL, &vk.device) != VK_SUCCESS) {
        pthread_mutex_unlock(&vk.lock);
        return false;
    }
    vkGetDeviceQueue(vk.device, vk.queue_family, 0, &vk.queue);
    VkCommandPoolCreateInfo pool = {
        .sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,
        .flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT,
        .queueFamilyIndex = vk.queue_family,
    };
    VkAttachmentDescription color = {
        .format = VK_FORMAT_B8G8R8A8_UNORM,
        .samples = VK_SAMPLE_COUNT_1_BIT,
        .loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR,
        .storeOp = VK_ATTACHMENT_STORE_OP_STORE,
        .stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE,
        .stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE,
        .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED,
        .finalLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
    };
    VkAttachmentReference color_ref = {.attachment = 0, .layout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
    VkSubpassDescription subpass = {
        .pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS,
        .colorAttachmentCount = 1,
        .pColorAttachments = &color_ref,
    };
    VkSubpassDependency dep = {
        .srcSubpass = VK_SUBPASS_EXTERNAL,
        .dstSubpass = 0,
        .srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
        .dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
        .dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
    };
    VkRenderPassCreateInfo rp = {
        .sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO,
        .attachmentCount = 1,
        .pAttachments = &color,
        .subpassCount = 1,
        .pSubpasses = &subpass,
        .dependencyCount = 1,
        .pDependencies = &dep,
    };
    VkDescriptorSetLayoutBinding bindings[3] = {
        {.binding = 0,
         .descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER,
         .descriptorCount = 1,
         .stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT},
        {.binding = 1,
         .descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
         .descriptorCount = 1,
         .stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT},
        {.binding = 2,
         .descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
         .descriptorCount = 1,
         .stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT},
    };
    VkDescriptorSetLayoutCreateInfo sl = {
        .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO,
        .bindingCount = 3,
        .pBindings = bindings,
    };
    VkPipelineLayoutCreateInfo pl = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO,
        .setLayoutCount = 1,
    };
    VkDescriptorPoolSize pool_sizes[2] = {
        {.type = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, .descriptorCount = ANDY_MAX_PRESENTERS},
        {.type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, .descriptorCount = ANDY_MAX_PRESENTERS * 2},
    };
    VkDescriptorPoolCreateInfo dp = {
        .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO,
        .flags = VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT,
        .maxSets = ANDY_MAX_PRESENTERS,
        .poolSizeCount = 2,
        .pPoolSizes = pool_sizes,
    };
    VkSamplerCreateInfo sampler = {
        .sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO,
        .magFilter = VK_FILTER_LINEAR,
        .minFilter = VK_FILTER_LINEAR,
        .addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
        .addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
        .addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
    };
    if (vkCreateCommandPool(vk.device, &pool, NULL, &vk.cmd_pool) != VK_SUCCESS ||
        vkCreateRenderPass(vk.device, &rp, NULL, &vk.render_pass) != VK_SUCCESS ||
        vkCreateDescriptorSetLayout(vk.device, &sl, NULL, &vk.dset_layout) != VK_SUCCESS) {
        pthread_mutex_unlock(&vk.lock);
        return false;
    }
    pl.pSetLayouts = &vk.dset_layout;
    if (vkCreatePipelineLayout(vk.device, &pl, NULL, &vk.pipeline_layout) != VK_SUCCESS ||
        vkCreateDescriptorPool(vk.device, &dp, NULL, &vk.dset_pool) != VK_SUCCESS ||
        vkCreateSampler(vk.device, &sampler, NULL, &vk.sampler) != VK_SUCCESS || !create_pipeline() ||
        !create_dummy_uv()) {
        pthread_mutex_unlock(&vk.lock);
        return false;
    }
    vk.ready = true;
    pthread_mutex_unlock(&vk.lock);
    return true;
}

static void destroy_swapchain_images(AndyVkSwapchain *sc) {
    for (uint32_t i = 0; i < sc->image_count; ++i) {
        if (sc->framebuffers[i]) vkDestroyFramebuffer(vk.device, sc->framebuffers[i], NULL);
        if (sc->views[i]) vkDestroyImageView(vk.device, sc->views[i], NULL);
        sc->framebuffers[i] = VK_NULL_HANDLE;
        sc->views[i] = VK_NULL_HANDLE;
    }
    if (sc->swapchain) vkDestroySwapchainKHR(vk.device, sc->swapchain, NULL);
    sc->swapchain = VK_NULL_HANDLE;
    sc->image_count = 0;
}

static bool recreate_swapchain(AndyVkSwapchain *sc) {
    uint32_t w = (uint32_t) (sc->pending_w > 0 ? sc->pending_w : 1);
    uint32_t h = (uint32_t) (sc->pending_h > 0 ? sc->pending_h : 1);
    vkDeviceWaitIdle(vk.device);
    destroy_swapchain_images(sc);
    VkSurfaceCapabilitiesKHR caps;
    vkGetPhysicalDeviceSurfaceCapabilitiesKHR(vk.phys, sc->surface, &caps);
    if (caps.currentExtent.width != UINT32_MAX) {
        w = caps.currentExtent.width;
        h = caps.currentExtent.height;
    } else {
        if (w < caps.minImageExtent.width) w = caps.minImageExtent.width;
        if (h < caps.minImageExtent.height) h = caps.minImageExtent.height;
        if (w > caps.maxImageExtent.width) w = caps.maxImageExtent.width;
        if (h > caps.maxImageExtent.height) h = caps.maxImageExtent.height;
    }
    if (w == 0 || h == 0) return false;
    uint32_t format_count = 0;
    vkGetPhysicalDeviceSurfaceFormatsKHR(vk.phys, sc->surface, &format_count, NULL);
    VkSurfaceFormatKHR *formats = (VkSurfaceFormatKHR *) calloc(format_count, sizeof(VkSurfaceFormatKHR));
    vkGetPhysicalDeviceSurfaceFormatsKHR(vk.phys, sc->surface, &format_count, formats);
    VkFormat format = VK_FORMAT_B8G8R8A8_UNORM;
    for (uint32_t i = 0; i < format_count; ++i) {
        if (formats[i].format == VK_FORMAT_B8G8R8A8_UNORM) {
            format = formats[i].format;
            break;
        }
    }
    free(formats);
    uint32_t image_count = caps.minImageCount + 1;
    if (caps.maxImageCount && image_count > caps.maxImageCount) image_count = caps.maxImageCount;
    VkSwapchainCreateInfoKHR info = {
        .sType = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR,
        .surface = sc->surface,
        .minImageCount = image_count,
        .imageFormat = format,
        .imageColorSpace = VK_COLOR_SPACE_SRGB_NONLINEAR_KHR,
        .imageExtent = {w, h},
        .imageArrayLayers = 1,
        .imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT,
        .imageSharingMode = VK_SHARING_MODE_EXCLUSIVE,
        .preTransform = caps.currentTransform,
        .compositeAlpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR,
        .presentMode = VK_PRESENT_MODE_FIFO_KHR,
        .clipped = VK_TRUE,
    };
    if (vkCreateSwapchainKHR(vk.device, &info, NULL, &sc->swapchain) != VK_SUCCESS) return false;
    vkGetSwapchainImagesKHR(vk.device, sc->swapchain, &sc->image_count, NULL);
    if (sc->image_count > ANDY_VK_MAX_IMAGES) sc->image_count = ANDY_VK_MAX_IMAGES;
    vkGetSwapchainImagesKHR(vk.device, sc->swapchain, &sc->image_count, sc->images);
    sc->format = format;
    sc->extent = (VkExtent2D){w, h};
    for (uint32_t i = 0; i < sc->image_count; ++i) {
        VkImageViewCreateInfo view = {
            .sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,
            .image = sc->images[i],
            .viewType = VK_IMAGE_VIEW_TYPE_2D,
            .format = format,
            .subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1},
        };
        VkFramebufferCreateInfo fb = {
            .sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO,
            .renderPass = vk.render_pass,
            .attachmentCount = 1,
            .width = w,
            .height = h,
            .layers = 1,
        };
        if (vkCreateImageView(vk.device, &view, NULL, &sc->views[i]) != VK_SUCCESS) return false;
        fb.pAttachments = &sc->views[i];
        if (vkCreateFramebuffer(vk.device, &fb, NULL, &sc->framebuffers[i]) != VK_SUCCESS) return false;
    }
    sc->dirty = false;
    return true;
}

static void destroy_video_textures(AndyVkSwapchain *sc) {
    destroy_image(&sc->y_image, &sc->y_view, &sc->y_mem);
    destroy_image(&sc->uv_image, &sc->uv_view, &sc->uv_mem);
    sc->tex_w = 0;
    sc->tex_h = 0;
}

static bool ensure_video_textures(AndyVkSwapchain *sc, const AndyFrameStore *frame) {
    if (sc->tex_w == frame->width && sc->tex_h == frame->height && sc->tex_bgra == frame->is_bgra && sc->y_image) {
        return true;
    }
    vkDeviceWaitIdle(vk.device);
    destroy_video_textures(sc);
    sc->tex_bgra = frame->is_bgra;
    const VkFormat y_format = frame->is_bgra ? VK_FORMAT_B8G8R8A8_UNORM : VK_FORMAT_R8_UNORM;
    if (!create_image(y_format, (uint32_t) frame->width, (uint32_t) frame->height,
                      VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, &sc->y_image, &sc->y_mem,
                      &sc->y_view, VK_IMAGE_ASPECT_COLOR_BIT)) {
        return false;
    }
    if (!frame->is_bgra) {
        if (!create_image(VK_FORMAT_R8G8_UNORM, (uint32_t) (frame->width / 2), (uint32_t) (frame->height / 2),
                          VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, &sc->uv_image, &sc->uv_mem,
                          &sc->uv_view, VK_IMAGE_ASPECT_COLOR_BIT)) {
            return false;
        }
    }
    sc->tex_w = frame->width;
    sc->tex_h = frame->height;
    return true;
}

static bool ensure_staging(AndyVkSwapchain *sc, size_t size) {
    if (sc->staging_size >= size && sc->staging_mapped) return true;
    if (sc->staging) vkDestroyBuffer(vk.device, sc->staging, NULL);
    if (sc->staging_mem) {
        vkUnmapMemory(vk.device, sc->staging_mem);
        vkFreeMemory(vk.device, sc->staging_mem, NULL);
    }
    sc->staging = VK_NULL_HANDLE;
    sc->staging_mem = VK_NULL_HANDLE;
    sc->staging_mapped = NULL;
    sc->staging_size = 0;
    if (!create_buffer(size, VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                       VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, &sc->staging,
                       &sc->staging_mem, &sc->staging_mapped)) {
        return false;
    }
    sc->staging_size = size;
    return true;
}

static void write_descriptors(AndyVkSwapchain *sc) {
    VkDescriptorBufferInfo ubo = {.buffer = sc->ubo, .offset = 0, .range = sizeof(AndyMirrorOverlay)};
    VkDescriptorImageInfo y_info = {
        .sampler = vk.sampler,
        .imageView = sc->y_view,
        .imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
    };
    VkDescriptorImageInfo uv_info = {
        .sampler = vk.sampler,
        .imageView = sc->uv_view ? sc->uv_view : vk.dummy_uv_view,
        .imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
    };
    VkWriteDescriptorSet writes[3] = {
        {.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
         .dstSet = sc->dset,
         .dstBinding = 0,
         .descriptorCount = 1,
         .descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER,
         .pBufferInfo = &ubo},
        {.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
         .dstSet = sc->dset,
         .dstBinding = 1,
         .descriptorCount = 1,
         .descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
         .pImageInfo = &y_info},
        {.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
         .dstSet = sc->dset,
         .dstBinding = 2,
         .descriptorCount = 1,
         .descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
         .pImageInfo = &uv_info},
    };
    vkUpdateDescriptorSets(vk.device, 3, writes, 0, NULL);
}

AndyVkSwapchain *andy_vk_attach(Window window) {
    if (!window || !andy_vk_init()) return NULL;
    pthread_mutex_lock(&vk.lock);
    AndyVkSwapchain *sc = (AndyVkSwapchain *) calloc(1, sizeof(AndyVkSwapchain));
    if (!sc) {
        pthread_mutex_unlock(&vk.lock);
        return NULL;
    }
    sc->window = window;
    sc->pending_w = 390;
    sc->pending_h = 844;
    VkXlibSurfaceCreateInfoKHR surface = {
        .sType = VK_STRUCTURE_TYPE_XLIB_SURFACE_CREATE_INFO_KHR,
        .dpy = andy_x11_display(),
        .window = window,
    };
    VkSemaphoreCreateInfo sem = {.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
    VkFenceCreateInfo fence = {.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO, .flags = VK_FENCE_CREATE_SIGNALED_BIT};
    VkCommandBufferAllocateInfo cmd = {
        .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
        .commandPool = vk.cmd_pool,
        .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY,
        .commandBufferCount = 1,
    };
    VkDescriptorSetAllocateInfo ds = {
        .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO,
        .descriptorPool = vk.dset_pool,
        .descriptorSetCount = 1,
        .pSetLayouts = &vk.dset_layout,
    };
    if (vkCreateXlibSurfaceKHR(vk.instance, &surface, NULL, &sc->surface) != VK_SUCCESS ||
        vkAllocateCommandBuffers(vk.device, &cmd, &sc->cmd) != VK_SUCCESS ||
        vkCreateSemaphore(vk.device, &sem, NULL, &sc->image_available) != VK_SUCCESS ||
        vkCreateSemaphore(vk.device, &sem, NULL, &sc->render_finished) != VK_SUCCESS ||
        vkCreateFence(vk.device, &fence, NULL, &sc->inflight) != VK_SUCCESS ||
        !create_buffer(sizeof(AndyMirrorOverlay), VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,
                       VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, &sc->ubo,
                       &sc->ubo_mem, &sc->ubo_mapped) ||
        vkAllocateDescriptorSets(vk.device, &ds, &sc->dset) != VK_SUCCESS) {
        pthread_mutex_unlock(&vk.lock);
        andy_vk_detach(sc);
        return NULL;
    }
    sc->dirty = true;
    pthread_mutex_unlock(&vk.lock);
    return sc;
}

void andy_vk_detach(AndyVkSwapchain *sc) {
    if (!sc) return;
    pthread_mutex_lock(&vk.lock);
    if (vk.device) vkDeviceWaitIdle(vk.device);
    destroy_swapchain_images(sc);
    destroy_video_textures(sc);
    if (sc->cmd) vkFreeCommandBuffers(vk.device, vk.cmd_pool, 1, &sc->cmd);
    if (sc->image_available) vkDestroySemaphore(vk.device, sc->image_available, NULL);
    if (sc->render_finished) vkDestroySemaphore(vk.device, sc->render_finished, NULL);
    if (sc->inflight) vkDestroyFence(vk.device, sc->inflight, NULL);
    if (sc->dset) vkFreeDescriptorSets(vk.device, vk.dset_pool, 1, &sc->dset);
    if (sc->ubo) vkDestroyBuffer(vk.device, sc->ubo, NULL);
    if (sc->ubo_mem) {
        vkUnmapMemory(vk.device, sc->ubo_mem);
        vkFreeMemory(vk.device, sc->ubo_mem, NULL);
    }
    if (sc->staging) vkDestroyBuffer(vk.device, sc->staging, NULL);
    if (sc->staging_mem) {
        vkUnmapMemory(vk.device, sc->staging_mem);
        vkFreeMemory(vk.device, sc->staging_mem, NULL);
    }
    if (sc->surface) vkDestroySurfaceKHR(vk.instance, sc->surface, NULL);
    pthread_mutex_unlock(&vk.lock);
    free(sc);
}

void andy_vk_note_resize(AndyVkSwapchain *sc, int width, int height) {
    if (!sc) return;
    if (width < 1) width = 1;
    if (height < 1) height = 1;
    if (sc->pending_w == width && sc->pending_h == height) return;
    sc->pending_w = width;
    sc->pending_h = height;
    sc->dirty = true;
}

static void copy_plane_to_image(VkCommandBuffer cmd, VkBuffer staging, VkDeviceSize offset, VkImage image,
                                uint32_t width, uint32_t height, uint32_t row_pitch) {
    image_barrier(cmd, image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 0,
                  VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
    VkBufferImageCopy copy = {
        .bufferOffset = offset,
        .bufferRowLength = row_pitch,
        .bufferImageHeight = height,
        .imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1},
        .imageExtent = {width, height, 1},
    };
    vkCmdCopyBufferToImage(cmd, staging, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &copy);
    image_barrier(cmd, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                  VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                  VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT);
}

bool andy_vk_present(AndyVkSwapchain *sc, const AndyFrameStore *frame, const AndyMirrorOverlay *overlay) {
    if (!sc || !frame || !frame->plane0 || !overlay || !andy_vk_init()) return false;
    pthread_mutex_lock(&vk.lock);
    if (sc->dirty || !sc->swapchain) {
        if (!recreate_swapchain(sc)) {
            pthread_mutex_unlock(&vk.lock);
            return false;
        }
    }
    if (!ensure_video_textures(sc, frame)) {
        pthread_mutex_unlock(&vk.lock);
        return false;
    }
    const size_t y_bytes = frame->is_bgra ? frame->stride0 * (size_t) frame->height
                                          : frame->stride0 * (size_t) frame->height;
    const size_t uv_bytes = frame->is_bgra ? 0 : frame->stride1 * (size_t) (frame->height / 2);
    if (!ensure_staging(sc, y_bytes + uv_bytes)) {
        pthread_mutex_unlock(&vk.lock);
        return false;
    }
    memcpy(sc->staging_mapped, frame->plane0, y_bytes);
    if (!frame->is_bgra && frame->plane1) memcpy((uint8_t *) sc->staging_mapped + y_bytes, frame->plane1, uv_bytes);
    AndyMirrorOverlay uniforms = *overlay;
    uniforms.format_flags[0] = frame->is_bgra ? 1.0f : 0.0f;
    uniforms.format_flags[1] = (!frame->is_bgra && frame->full_range_yuv) ? 1.0f : 0.0f;
    memcpy(sc->ubo_mapped, &uniforms, sizeof(uniforms));
    write_descriptors(sc);

    vkWaitForFences(vk.device, 1, &sc->inflight, VK_TRUE, UINT64_MAX);
    uint32_t image_index = 0;
    VkResult acquired = vkAcquireNextImageKHR(vk.device, sc->swapchain, UINT64_MAX, sc->image_available, VK_NULL_HANDLE,
                                              &image_index);
    if (acquired == VK_ERROR_OUT_OF_DATE_KHR || acquired == VK_SUBOPTIMAL_KHR) {
        sc->dirty = true;
        pthread_mutex_unlock(&vk.lock);
        return false;
    }
    if (acquired != VK_SUCCESS) {
        pthread_mutex_unlock(&vk.lock);
        return false;
    }
    vkResetFences(vk.device, 1, &sc->inflight);
    vkResetCommandBuffer(sc->cmd, 0);
    VkCommandBufferBeginInfo begin = {.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    vkBeginCommandBuffer(sc->cmd, &begin);
    if (frame->is_bgra) {
        copy_plane_to_image(sc->cmd, sc->staging, 0, sc->y_image, (uint32_t) frame->width, (uint32_t) frame->height,
                            (uint32_t) frame->width);
    } else {
        copy_plane_to_image(sc->cmd, sc->staging, 0, sc->y_image, (uint32_t) frame->width, (uint32_t) frame->height,
                            (uint32_t) frame->width);
        copy_plane_to_image(sc->cmd, sc->staging, y_bytes, sc->uv_image, (uint32_t) (frame->width / 2),
                            (uint32_t) (frame->height / 2), (uint32_t) (frame->width / 2));
    }
    VkClearValue clear = {.color = {{0.f, 0.f, 0.f, 1.f}}};
    VkRenderPassBeginInfo rp = {
        .sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO,
        .renderPass = vk.render_pass,
        .framebuffer = sc->framebuffers[image_index],
        .renderArea = {{0, 0}, sc->extent},
        .clearValueCount = 1,
        .pClearValues = &clear,
    };
    vkCmdBeginRenderPass(sc->cmd, &rp, VK_SUBPASS_CONTENTS_INLINE);
    vkCmdBindPipeline(sc->cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, vk.pipeline);
    VkViewport viewport = {0.f, 0.f, (float) sc->extent.width, (float) sc->extent.height, 0.f, 1.f};
    VkRect2D scissor = {{0, 0}, sc->extent};
    vkCmdSetViewport(sc->cmd, 0, 1, &viewport);
    vkCmdSetScissor(sc->cmd, 0, 1, &scissor);
    vkCmdBindDescriptorSets(sc->cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, vk.pipeline_layout, 0, 1, &sc->dset, 0, NULL);
    vkCmdDraw(sc->cmd, 4, 1, 0, 0);
    vkCmdEndRenderPass(sc->cmd);
    vkEndCommandBuffer(sc->cmd);
    VkPipelineStageFlags wait_stage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    VkSubmitInfo submit = {
        .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
        .waitSemaphoreCount = 1,
        .pWaitSemaphores = &sc->image_available,
        .pWaitDstStageMask = &wait_stage,
        .commandBufferCount = 1,
        .pCommandBuffers = &sc->cmd,
        .signalSemaphoreCount = 1,
        .pSignalSemaphores = &sc->render_finished,
    };
    if (vkQueueSubmit(vk.queue, 1, &submit, sc->inflight) != VK_SUCCESS) {
        pthread_mutex_unlock(&vk.lock);
        return false;
    }
    VkPresentInfoKHR present = {
        .sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR,
        .waitSemaphoreCount = 1,
        .pWaitSemaphores = &sc->render_finished,
        .swapchainCount = 1,
        .pSwapchains = &sc->swapchain,
        .pImageIndices = &image_index,
    };
    VkResult presented = vkQueuePresentKHR(vk.queue, &present);
    if (presented == VK_ERROR_OUT_OF_DATE_KHR || presented == VK_SUBOPTIMAL_KHR) sc->dirty = true;
    pthread_mutex_unlock(&vk.lock);
    return presented == VK_SUCCESS || presented == VK_SUBOPTIMAL_KHR;
}

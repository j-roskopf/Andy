#include <jni.h>
#include <math.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#import <AppKit/AppKit.h>
#import <Metal/Metal.h>
#import <MetalKit/MetalKit.h>
#import <QuartzCore/CAMetalLayer.h>
#import <QuartzCore/CATransaction.h>
#import <VideoToolbox/VideoToolbox.h>
#import <CoreVideo/CVMetalTextureCache.h>
#include <mach/mach_time.h>

#include "andy_mirror_hub.h"

#define ANDY_MAX_DECODERS 8
#define ANDY_MAX_PRESENTERS 16
#define ANDY_MAX_PRESENTERS_PER_DECODER 8

typedef struct {
    float grid[4];
    float ruler[4];
    float highlight[4];
    float grid_color[4];
    float ruler_color[4];
    float picker[4];
    float source_size[4];
    float format_flags[4];
} AndyMirrorOverlay;

typedef struct {
    bool active;
    int64_t id;
    VTDecompressionSessionRef decoder;
    bool decoder_is_hardware;
    CMVideoFormatDescriptionRef format;
    uint8_t *sps;
    size_t sps_size;
    uint8_t *pps;
    size_t pps_size;
    uint64_t frames_presented;
    uint64_t dropped_frames;
    uint64_t pending_input_ticks;
    uint64_t transport_ingress_ticks;
    double input_to_present_millis[120];
    size_t input_to_present_count;
    double packet_to_present_millis[120];
    size_t packet_to_present_count;
    double transport_to_present_millis[120];
    size_t transport_to_present_count;
    bool latency_probe_enabled;
    float probe_left;
    float probe_top;
    float probe_width;
    float probe_height;
    double probe_luminance;
    bool probe_has_baseline;
    uint64_t probe_transitions;
    bool ios_source_active;
    CVPixelBufferRef latest_pixels;
    pthread_mutex_t latest_pixels_lock;
    pthread_mutex_t decoder_lock;
    pthread_mutex_t stats_lock;
    int64_t presenter_ids[ANDY_MAX_PRESENTERS_PER_DECODER];
    int presenter_count;
} GpuDecoder;

typedef struct {
    bool active;
    int64_t id;
    int64_t decoder_id;
    __strong NSWindow *window;
    __strong MTKView *view;
    __strong NSView *content;
    CAMetalLayer *layer;
    AndyMirrorOverlay overlay;
    bool overlay_open;
    bool visible;
    bool fill_host;
    int content_width;
    int content_height;
    jint pending_x;
    jint pending_y;
    jint pending_w;
    jint pending_h;
    jdouble pending_scale;
    jint pending_parent_window;
    bool geometry_scheduled;
} GpuPresenter;

static struct {
    id<MTLDevice> device;
    id<MTLCommandQueue> queue;
    id<MTLRenderPipelineState> pipeline;
    CVMetalTextureCacheRef texture_cache;
    id<MTLTexture> dummy_uv_texture;
    pthread_mutex_t render_lock;
    pthread_mutex_t init_lock;
    bool initialized;
} shared_metal = {0};

static GpuDecoder decoders[ANDY_MAX_DECODERS];
static GpuPresenter presenters[ANDY_MAX_PRESENTERS];
static pthread_mutex_t hub_lock = PTHREAD_MUTEX_INITIALIZER;
static int64_t next_id = 1;
static int64_t ios_decoder_id = ANDY_HUB_INVALID_ID;

static NSString *const shader_source = @
    "#include <metal_stdlib>\n"
    "using namespace metal;\n"
    "struct VertexOut { float4 position [[position]]; float2 uv; };\n"
    "vertex VertexOut vertex_main(uint id [[vertex_id]]) {\n"
    "  float2 positions[4] = {float2(-1,-1), float2(1,-1), float2(-1,1), float2(1,1)};\n"
    "  float2 uvs[4] = {float2(0,1), float2(1,1), float2(0,0), float2(1,0)};\n"
    "  VertexOut out; out.position=float4(positions[id],0,1); out.uv=uvs[id]; return out;\n"
    "}\n"
    "struct Overlay { float4 grid; float4 ruler; float4 highlight; float4 grid_color; float4 ruler_color; float4 picker; float4 source_size; float4 format_flags; };\n"
    "float3 sampled_rgb(texture2d<float> y_tex, texture2d<float> uv_tex, sampler sample, float2 coord, bool is_bgra, bool full_range_yuv) {\n"
    "  if (is_bgra) { return y_tex.sample(sample, coord).rgb; }\n"
    "  float y_sample = y_tex.sample(sample, coord).r;\n"
    "  float y = full_range_yuv ? y_sample : (1.1643 * (y_sample - 0.0625));\n"
    "  float2 uv = uv_tex.sample(sample, coord).rg - float2(0.5, 0.5);\n"
    "  return float3(y + 1.5958 * uv.y, y - 0.39173 * uv.x - 0.81290 * uv.y, y + 2.017 * uv.x);\n"
    "}\n"
    "fragment half4 fragment_main(VertexOut in [[stage_in]], texture2d<float> y_tex [[texture(0)]], texture2d<float> uv_tex [[texture(1)]], constant Overlay &overlay [[buffer(0)]]) {\n"
    "  constexpr sampler linear_sampler(address::clamp_to_edge, filter::linear);\n"
    "  constexpr sampler nearest_sampler(address::clamp_to_edge, filter::nearest);\n"
    "  bool is_bgra = overlay.format_flags.x > 0.5;\n"
    "  bool full_range_yuv = overlay.format_flags.y > 0.5;\n"
    "  float3 rgb = sampled_rgb(y_tex, uv_tex, linear_sampler, in.uv, is_bgra, full_range_yuv);\n"
    "  if (overlay.ruler.x > 0.5) {\n"
    "    float x_width = max(fwidth(in.uv.x), 0.001);\n"
    "    float y_width = max(fwidth(in.uv.y), 0.001);\n"
    "    float vertical = 1.0 - smoothstep(x_width * 0.6, x_width * 1.6, abs(in.uv.x - overlay.ruler.y));\n"
    "    float horizontal = 1.0 - smoothstep(y_width * 0.6, y_width * 1.6, abs(in.uv.y - overlay.ruler.z));\n"
    "    rgb = mix(rgb, overlay.ruler_color.rgb, overlay.ruler_color.a * max(vertical, horizontal));\n"
    "  }\n"
    "  if (overlay.highlight.z > overlay.highlight.x && overlay.highlight.w > overlay.highlight.y) {\n"
    "    bool inside = in.uv.x >= overlay.highlight.x && in.uv.x <= overlay.highlight.z && in.uv.y >= overlay.highlight.y && in.uv.y <= overlay.highlight.w;\n"
    "    float edge = min(min(abs(in.uv.x - overlay.highlight.x) * y_tex.get_width(), abs(in.uv.x - overlay.highlight.z) * y_tex.get_width()), min(abs(in.uv.y - overlay.highlight.y) * y_tex.get_height(), abs(in.uv.y - overlay.highlight.w) * y_tex.get_height()));\n"
    "    if (inside && edge < 2.0) rgb = float3(0.85, 0.44, 0.29);\n"
    "  }\n"
    "  if (overlay.picker.x > 0.5 && overlay.picker.w > 0.5) {\n"
    "    float2 delta = in.uv - overlay.picker.yz;\n"
    "    float aspect = float(y_tex.get_width()) / max(1.0, float(y_tex.get_height()));\n"
    "    float lens_distance = length(float2(delta.x * aspect, delta.y));\n"
    "    const float lens_radius = 0.092;\n"
    "    if (lens_distance <= lens_radius) {\n"
    "      if (lens_distance >= lens_radius - 0.004) rgb = float3(0.85, 0.44, 0.29);\n"
    "      else {\n"
    "        float2 magnified_uv = overlay.picker.yz + delta / 5.0;\n"
    "        rgb = sampled_rgb(y_tex, uv_tex, nearest_sampler, magnified_uv, is_bgra, full_range_yuv);\n"
    "      }\n"
    "    }\n"
    "  }\n"
    "  return half4(half3(rgb), 1);\n"
    "}\n";

static void run_on_main(void (^block)(void)) {
    if ([NSThread isMainThread]) {
        block();
    } else {
        dispatch_sync(dispatch_get_main_queue(), block);
    }
}

static int64_t allocate_id(void) {
    return next_id++;
}

static GpuDecoder *find_decoder(int64_t id) {
    for (int i = 0; i < ANDY_MAX_DECODERS; i++) {
        if (decoders[i].active && decoders[i].id == id) return &decoders[i];
    }
    return NULL;
}

static GpuPresenter *find_presenter(int64_t id) {
    for (int i = 0; i < ANDY_MAX_PRESENTERS; i++) {
        if (presenters[i].active && presenters[i].id == id) return &presenters[i];
    }
    return NULL;
}

static bool pixel_buffer_is_bgra(CVPixelBufferRef pixels) {
    OSType format = CVPixelBufferGetPixelFormatType(pixels);
    return format == kCVPixelFormatType_32BGRA || format == (OSType) 'BGRX' ||
        format == kCVPixelFormatType_32RGBA || format == kCVPixelFormatType_32ARGB ||
        format == kCVPixelFormatType_ARGB2101010LEPacked;
}

static MTLPixelFormat pixel_buffer_metal_format(CVPixelBufferRef pixels) {
    return CVPixelBufferGetPixelFormatType(pixels) == kCVPixelFormatType_32RGBA
        ? MTLPixelFormatRGBA8Unorm : MTLPixelFormatBGRA8Unorm;
}

static bool pixel_buffer_is_yuv_full_range(CVPixelBufferRef pixels) {
    return CVPixelBufferGetPixelFormatType(pixels) == kCVPixelFormatType_420YpCbCr8BiPlanarFullRange;
}

static void pixel_buffer_dimensions(CVPixelBufferRef pixels, size_t *width, size_t *height) {
    if (CVPixelBufferGetPlaneCount(pixels) == 0) {
        *width = CVPixelBufferGetWidth(pixels);
        *height = CVPixelBufferGetHeight(pixels);
    } else {
        *width = CVPixelBufferGetWidthOfPlane(pixels, 0);
        *height = CVPixelBufferGetHeightOfPlane(pixels, 0);
    }
}

static id<MTLTexture> ensure_dummy_uv_texture(void) {
    if (shared_metal.dummy_uv_texture) return shared_metal.dummy_uv_texture;
    MTLTextureDescriptor *descriptor = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRG8Unorm
                                                                                         width:1 height:1 mipmapped:NO];
    descriptor.usage = MTLTextureUsageShaderRead;
    id<MTLTexture> texture = [shared_metal.device newTextureWithDescriptor:descriptor];
    if (texture) {
        uint8_t pixel[2] = {128, 128};
        [texture replaceRegion:MTLRegionMake2D(0, 0, 1, 1) mipmapLevel:0 withBytes:pixel bytesPerRow:2];
        shared_metal.dummy_uv_texture = texture;
    }
    return shared_metal.dummy_uv_texture;
}

static bool ensure_shared_metal(void) {
    pthread_mutex_lock(&hub_lock);
    if (shared_metal.initialized) {
        pthread_mutex_unlock(&hub_lock);
        return shared_metal.device != nil;
    }
    pthread_mutex_init(&shared_metal.render_lock, NULL);
    shared_metal.device = MTLCreateSystemDefaultDevice();
    if (!shared_metal.device) {
        pthread_mutex_unlock(&hub_lock);
        return false;
    }
    shared_metal.queue = [shared_metal.device newCommandQueue];
    NSError *error = nil;
    id<MTLLibrary> library = [shared_metal.device newLibraryWithSource:shader_source options:nil error:&error];
    if (!library) {
        pthread_mutex_unlock(&hub_lock);
        return false;
    }
    MTLRenderPipelineDescriptor *descriptor = [[MTLRenderPipelineDescriptor alloc] init];
    descriptor.vertexFunction = [library newFunctionWithName:@"vertex_main"];
    descriptor.fragmentFunction = [library newFunctionWithName:@"fragment_main"];
    descriptor.colorAttachments[0].pixelFormat = MTLPixelFormatBGRA8Unorm;
    shared_metal.pipeline = [shared_metal.device newRenderPipelineStateWithDescriptor:descriptor error:&error];
    if (!shared_metal.pipeline ||
        CVMetalTextureCacheCreate(kCFAllocatorDefault, NULL, shared_metal.device, NULL, &shared_metal.texture_cache) != kCVReturnSuccess) {
        pthread_mutex_unlock(&hub_lock);
        return false;
    }
    shared_metal.initialized = true;
    pthread_mutex_unlock(&hub_lock);
    return true;
}

static void destroy_decoder_locked(GpuDecoder *decoder) {
    if (!decoder) return;
    if (decoder->decoder) {
        VTDecompressionSessionWaitForAsynchronousFrames(decoder->decoder);
        VTDecompressionSessionInvalidate(decoder->decoder);
        CFRelease(decoder->decoder);
        decoder->decoder = NULL;
    }
    if (decoder->format) {
        CFRelease(decoder->format);
        decoder->format = NULL;
    }
    decoder->decoder_is_hardware = false;
    free(decoder->sps);
    free(decoder->pps);
    decoder->sps = NULL;
    decoder->pps = NULL;
    decoder->sps_size = 0;
    decoder->pps_size = 0;
    pthread_mutex_lock(&decoder->latest_pixels_lock);
    if (decoder->latest_pixels) {
        CVPixelBufferRelease(decoder->latest_pixels);
        decoder->latest_pixels = NULL;
    }
    pthread_mutex_unlock(&decoder->latest_pixels_lock);
}

static bool configure_decoder_locked(GpuDecoder *decoder) {
    if (decoder->decoder || !decoder->sps || !decoder->pps) return decoder->decoder != NULL;
    const uint8_t *parameter_sets[] = {decoder->sps, decoder->pps};
    const size_t parameter_set_sizes[] = {decoder->sps_size, decoder->pps_size};
    if (CMVideoFormatDescriptionCreateFromH264ParameterSets(kCFAllocatorDefault, 2, parameter_sets,
            parameter_set_sizes, 4, &decoder->format) != noErr) {
        return false;
    }
    NSDictionary *specification = @{
        (__bridge NSString *) kVTVideoDecoderSpecification_EnableHardwareAcceleratedVideoDecoder: @YES,
        (__bridge NSString *) kVTVideoDecoderSpecification_RequireHardwareAcceleratedVideoDecoder: @YES,
    };
    NSDictionary *attributes = @{
        (__bridge NSString *) kCVPixelBufferPixelFormatTypeKey: @(kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange),
        (__bridge NSString *) kCVPixelBufferMetalCompatibilityKey: @YES,
    };
    VTDecompressionOutputCallbackRecord callback = {
        .decompressionOutputCallback = NULL,
        .decompressionOutputRefCon = decoder,
    };
    if (VTDecompressionSessionCreate(kCFAllocatorDefault, decoder->format, (__bridge CFDictionaryRef) specification,
            (__bridge CFDictionaryRef) attributes, &callback, &decoder->decoder) != noErr) {
        destroy_decoder_locked(decoder);
        return false;
    }
    CFTypeRef hardware = NULL;
    OSStatus hardware_status = VTSessionCopyProperty(decoder->decoder,
        kVTDecompressionPropertyKey_UsingHardwareAcceleratedVideoDecoder, kCFAllocatorDefault, &hardware);
    decoder->decoder_is_hardware = hardware_status == noErr && hardware &&
        CFGetTypeID(hardware) == CFBooleanGetTypeID() && CFBooleanGetValue((CFBooleanRef) hardware);
    if (hardware) CFRelease(hardware);
    if (!decoder->decoder_is_hardware) {
        destroy_decoder_locked(decoder);
        return false;
    }
    VTSessionSetProperty(decoder->decoder, kVTDecompressionPropertyKey_RealTime, kCFBooleanTrue);
    return true;
}

static void record_input_to_present(GpuDecoder *decoder) {
    pthread_mutex_lock(&decoder->stats_lock);
    if (!decoder->pending_input_ticks) {
        pthread_mutex_unlock(&decoder->stats_lock);
        return;
    }
    mach_timebase_info_data_t timebase = {0};
    mach_timebase_info(&timebase);
    const double elapsed_millis = (double) (mach_continuous_time() - decoder->pending_input_ticks) *
        timebase.numer / timebase.denom / 1000000.0;
    if (decoder->input_to_present_count < 120) {
        decoder->input_to_present_millis[decoder->input_to_present_count++] = elapsed_millis;
    } else {
        memmove(decoder->input_to_present_millis, decoder->input_to_present_millis + 1, sizeof(double) * 119);
        decoder->input_to_present_millis[119] = elapsed_millis;
    }
    decoder->pending_input_ticks = 0;
    pthread_mutex_unlock(&decoder->stats_lock);
}

static void record_packet_to_present(GpuDecoder *decoder, uint64_t packet_ticks) {
    if (!packet_ticks) return;
    mach_timebase_info_data_t timebase = {0};
    mach_timebase_info(&timebase);
    const double elapsed_millis = (double) (mach_continuous_time() - packet_ticks) *
        timebase.numer / timebase.denom / 1000000.0;
    pthread_mutex_lock(&decoder->stats_lock);
    if (decoder->packet_to_present_count < 120) {
        decoder->packet_to_present_millis[decoder->packet_to_present_count++] = elapsed_millis;
    } else {
        memmove(decoder->packet_to_present_millis, decoder->packet_to_present_millis + 1, sizeof(double) * 119);
        decoder->packet_to_present_millis[119] = elapsed_millis;
    }
    pthread_mutex_unlock(&decoder->stats_lock);
}

static void record_transport_to_present(GpuDecoder *decoder, uint64_t transport_ticks) {
    if (!transport_ticks) return;
    mach_timebase_info_data_t timebase = {0};
    mach_timebase_info(&timebase);
    const double elapsed_millis = (double) (mach_continuous_time() - transport_ticks) *
        timebase.numer / timebase.denom / 1000000.0;
    pthread_mutex_lock(&decoder->stats_lock);
    if (decoder->transport_to_present_count < 120) {
        decoder->transport_to_present_millis[decoder->transport_to_present_count++] = elapsed_millis;
    } else {
        memmove(decoder->transport_to_present_millis, decoder->transport_to_present_millis + 1, sizeof(double) * 119);
        decoder->transport_to_present_millis[119] = elapsed_millis;
    }
    pthread_mutex_unlock(&decoder->stats_lock);
}

static void render_to_presenter(GpuPresenter *presenter, GpuDecoder *decoder, CVPixelBufferRef pixels,
                                bool input_changed_probe, uint64_t packet_ticks, uint64_t transport_ticks,
                                bool record_presentation_metrics);
static void destroy_presenter_locked(GpuPresenter *presenter);

static void fan_out_render(GpuDecoder *decoder, CVPixelBufferRef pixels, bool input_changed_probe,
                           uint64_t packet_ticks, uint64_t transport_ticks, bool record_presentation_metrics) {
    for (int i = 0; i < decoder->presenter_count; i++) {
        GpuPresenter *presenter = find_presenter(decoder->presenter_ids[i]);
        if (presenter) {
            render_to_presenter(presenter, decoder, pixels, input_changed_probe, packet_ticks, transport_ticks,
                                record_presentation_metrics);
        }
    }
}

static void decoded_frame_callback(void *decompression_output_refcon, void *source_frame_refcon,
                                   OSStatus status, VTDecodeInfoFlags info_flags, CVImageBufferRef image_buffer,
                                   CMTime presentation_time_stamp, CMTime presentation_duration) {
    (void) info_flags;
    (void) presentation_time_stamp;
    (void) presentation_duration;
    GpuDecoder *decoder = (GpuDecoder *) decompression_output_refcon;
    if (!decoder || status != noErr || !image_buffer) {
        if (decoder) {
            pthread_mutex_lock(&decoder->stats_lock);
            decoder->dropped_frames++;
            pthread_mutex_unlock(&decoder->stats_lock);
        }
        return;
    }
    const uint64_t packet_ticks = (uint64_t) (uintptr_t) source_frame_refcon;
    CVPixelBufferRef pixels = (CVPixelBufferRef) image_buffer;
    andy_hub_remember_latest_pixels(decoder->id, pixels);
    pthread_mutex_lock(&decoder->stats_lock);
    const uint64_t transport_ticks = decoder->transport_ingress_ticks;
    pthread_mutex_unlock(&decoder->stats_lock);
    const bool probe = andy_hub_latency_probe_changed(decoder->id, pixels);
    fan_out_render(decoder, pixels, probe, packet_ticks, transport_ticks, true);
}

static void render_to_presenter(GpuPresenter *presenter, GpuDecoder *decoder, CVPixelBufferRef pixels,
                                bool input_changed_probe, uint64_t packet_ticks, uint64_t transport_ticks,
                                bool record_presentation_metrics) {
    if (!presenter->overlay_open || !presenter->visible || !presenter->layer) return;
    pthread_mutex_lock(&shared_metal.render_lock);
    // Re-check under the lock: close_presenter_window may have released the layer while this
    // thread waited for render_lock.
    if (!presenter->overlay_open || !presenter->layer) {
        pthread_mutex_unlock(&shared_metal.render_lock);
        return;
    }
    @autoreleasepool {
        size_t width = 0;
        size_t height = 0;
        pixel_buffer_dimensions(pixels, &width, &height);
        if (!width || !height || !shared_metal.texture_cache || !shared_metal.pipeline) {
            pthread_mutex_unlock(&shared_metal.render_lock);
            return;
        }
        const bool is_bgra = pixel_buffer_is_bgra(pixels);
        AndyMirrorOverlay frame_overlay = presenter->overlay;
        frame_overlay.format_flags[0] = is_bgra ? 1.0f : 0.0f;
        frame_overlay.format_flags[1] = (!is_bgra && pixel_buffer_is_yuv_full_range(pixels)) ? 1.0f : 0.0f;
        id<CAMetalDrawable> drawable = [presenter->layer nextDrawable];
        if (!drawable) {
            pthread_mutex_lock(&decoder->stats_lock);
            decoder->dropped_frames++;
            pthread_mutex_unlock(&decoder->stats_lock);
            pthread_mutex_unlock(&shared_metal.render_lock);
            return;
        }
        CVMetalTextureRef y_ref = NULL;
        CVMetalTextureRef uv_ref = NULL;
        CVReturn y_result;
        CVReturn uv_result;
        if (is_bgra) {
            y_result = CVMetalTextureCacheCreateTextureFromImage(kCFAllocatorDefault, shared_metal.texture_cache, pixels,
                NULL, pixel_buffer_metal_format(pixels), width, height, 0, &y_ref);
            uv_result = kCVReturnSuccess;
        } else {
            y_result = CVMetalTextureCacheCreateTextureFromImage(kCFAllocatorDefault, shared_metal.texture_cache, pixels,
                NULL, MTLPixelFormatR8Unorm, width, height, 0, &y_ref);
            uv_result = CVMetalTextureCacheCreateTextureFromImage(kCFAllocatorDefault, shared_metal.texture_cache, pixels,
                NULL, MTLPixelFormatRG8Unorm, width / 2, height / 2, 1, &uv_ref);
        }
        if (y_result != kCVReturnSuccess || (!is_bgra && uv_result != kCVReturnSuccess)) {
            if (y_ref) CFRelease(y_ref);
            if (uv_ref) CFRelease(uv_ref);
            pthread_mutex_lock(&decoder->stats_lock);
            decoder->dropped_frames++;
            pthread_mutex_unlock(&decoder->stats_lock);
            pthread_mutex_unlock(&shared_metal.render_lock);
            return;
        }
        id<MTLTexture> uv_texture = is_bgra ? ensure_dummy_uv_texture() : CVMetalTextureGetTexture(uv_ref);
        id<MTLCommandBuffer> command = [shared_metal.queue commandBuffer];
        id<MTLBuffer> overlay = [shared_metal.device newBufferWithBytes:&frame_overlay
                                                                   length:sizeof(AndyMirrorOverlay)
                                                                  options:MTLResourceStorageModeShared];
        MTLRenderPassDescriptor *pass = [MTLRenderPassDescriptor renderPassDescriptor];
        pass.colorAttachments[0].texture = drawable.texture;
        pass.colorAttachments[0].loadAction = MTLLoadActionClear;
        pass.colorAttachments[0].storeAction = MTLStoreActionStore;
        pass.colorAttachments[0].clearColor = MTLClearColorMake(0, 0, 0, 1);
        id<MTLRenderCommandEncoder> encoder = [command renderCommandEncoderWithDescriptor:pass];
        [encoder setRenderPipelineState:shared_metal.pipeline];
        [encoder setFragmentBuffer:overlay offset:0 atIndex:0];
        [encoder setFragmentTexture:CVMetalTextureGetTexture(y_ref) atIndex:0];
        [encoder setFragmentTexture:uv_texture atIndex:1];
        [encoder drawPrimitives:MTLPrimitiveTypeTriangleStrip vertexStart:0 vertexCount:4];
        [encoder endEncoding];
        [command presentDrawable:drawable];
        if (record_presentation_metrics) {
            [command addCompletedHandler:^(id<MTLCommandBuffer> buffer) {
                (void) buffer;
                pthread_mutex_lock(&decoder->stats_lock);
                decoder->frames_presented++;
                pthread_mutex_unlock(&decoder->stats_lock);
                record_packet_to_present(decoder, packet_ticks);
                record_transport_to_present(decoder, transport_ticks);
                if (input_changed_probe) record_input_to_present(decoder);
            }];
        }
        [command commit];
        CFRelease(y_ref);
        if (uv_ref) CFRelease(uv_ref);
    }
    pthread_mutex_unlock(&shared_metal.render_lock);
}

// True if [window] is a borderless overlay owned by one of our presenters. Best-effort read of the
// presenters array without hub_lock (apply_presenter_frame runs on the main thread and must not
// block the destroy path that syncs to main).
static bool is_presenter_owned_window(NSWindow *window) {
    if (!window) return false;
    for (int i = 0; i < ANDY_MAX_PRESENTERS; i++) {
        if (presenters[i].active && presenters[i].window == window) return true;
    }
    return false;
}

static void apply_presenter_frame(GpuPresenter *presenter) {
    if (!presenter->window || !presenter->overlay_open) return;
    const CGFloat w = MAX(1, presenter->pending_w);
    const CGFloat h = MAX(1, presenter->pending_h);
    NSScreen *primary = NSScreen.screens.firstObject;
    const CGFloat screen_height = primary ? NSMaxY(primary.frame) : h;
    const NSRect frame = NSMakeRect(presenter->pending_x, screen_height - presenter->pending_y - h, w, h);
    const CGFloat backing_scale = presenter->pending_scale > 0.0 ? (CGFloat) presenter->pending_scale :
        (presenter->window.backingScaleFactor > 0.0 ? presenter->window.backingScaleFactor : 1.0);
    // Prefer the explicit AWT parent; fall back to window-at-point like the legacy overlay so the
    // Metal surface stays an AppKit child and survives Canvas focus without orderFront flashes.
    NSWindow *under = nil;
    if (presenter->pending_parent_window > 0) {
        under = [NSApp windowWithWindowNumber:presenter->pending_parent_window];
    }
    if (!under) {
        // Walk windows top-to-bottom at the overlay's midpoint until we hit a real app window,
        // skipping every presenter overlay (this one AND siblings). During an Android->iOS switch
        // the closing Android overlay still sits at this point; latching onto it re-parents the new
        // iOS overlay under a window that is about to close, orphaning it into a permanent black.
        NSPoint probe = NSMakePoint(NSMidX(frame), NSMidY(frame));
        NSInteger window_number = [NSWindow windowNumberAtPoint:probe belowWindowWithWindowNumber:0];
        NSWindow *candidate = [NSApp windowWithWindowNumber:window_number];
        while (candidate && is_presenter_owned_window(candidate)) {
            window_number = [NSWindow windowNumberAtPoint:probe belowWindowWithWindowNumber:window_number];
            if (window_number == 0) {
                candidate = nil;
                break;
            }
            candidate = [NSApp windowWithWindowNumber:window_number];
        }
        under = candidate;
    }
    NSWindow *parent = presenter->window.parentWindow;
    if (under && under != presenter->window && parent != under) {
        if (parent) {
            [parent removeChildWindow:presenter->window];
        }
        [under addChildWindow:presenter->window ordered:NSWindowAbove];
    } else if (under && parent == under) {
        // Already parented — do not re-addChildWindow here; that can flash on every resize.
    }
    if (!NSEqualRects(presenter->window.frame, frame)) {
        [presenter->window setFrame:frame display:NO];
    }
    presenter->view.frame = NSMakeRect(0, 0, w, h);
    presenter->layer.contentsScale = backing_scale;
    const CGSize drawable = CGSizeMake(w * backing_scale, h * backing_scale);
    const bool drawable_changed = !CGSizeEqualToSize(presenter->layer.drawableSize, drawable);
    presenter->layer.drawableSize = drawable;
    if (presenter->visible && !presenter->window.isVisible) {
        [presenter->window orderFront:nil];
    }
    // Only repaint when the drawable actually changed. Clearing+presenting on every focus /
    // geometry tick flashes black under the Metal overlay.
    if (drawable_changed) {
        andy_hub_repaint_presenter(presenter->id);
    }
}

static void schedule_presenter_geometry(GpuPresenter *presenter) {
    // Always coalesce onto the next main-queue pass. AWT delivers resize callbacks on the EDT
    // (main thread); calling windowNumberAtPoint / addChildWindow / setFrame synchronously inside
    // componentResized deadlocks or re-enters the event loop and freezes the whole JVM UI.
    if (presenter->geometry_scheduled) return;
    presenter->geometry_scheduled = true;
    dispatch_async(dispatch_get_main_queue(), ^{
        presenter->geometry_scheduled = false;
        apply_presenter_frame(presenter);
    });
}

static bool open_presenter_window(GpuPresenter *presenter) {
    if (!ensure_shared_metal() || !NSApp) return false;
    __block bool opened = false;
    run_on_main(^{
        const NSRect content_rect = NSMakeRect(0, 0, 390, 844);
        presenter->window = [[NSWindow alloc] initWithContentRect:content_rect
                                                      styleMask:NSWindowStyleMaskBorderless
                                                        backing:NSBackingStoreBuffered defer:NO];
        if (!presenter->window) return;
        presenter->content = [[NSView alloc] initWithFrame:content_rect];
        presenter->view = [[MTKView alloc] initWithFrame:content_rect device:shared_metal.device];
        presenter->view.colorPixelFormat = MTLPixelFormatBGRA8Unorm;
        presenter->view.framebufferOnly = YES;
        presenter->view.paused = YES;
        presenter->view.enableSetNeedsDisplay = NO;
        presenter->view.autoresizingMask = NSViewWidthSizable | NSViewHeightSizable;
        [presenter->content addSubview:presenter->view];
        presenter->window.contentView = presenter->content;
        presenter->window.opaque = YES;
        presenter->window.backgroundColor = NSColor.blackColor;
        presenter->window.hasShadow = NO;
        presenter->window.ignoresMouseEvents = YES;
        presenter->window.level = NSNormalWindowLevel;
        presenter->layer = (CAMetalLayer *) presenter->view.layer;
        presenter->layer.opaque = YES;
        presenter->layer.framebufferOnly = YES;
        presenter->layer.device = shared_metal.device;
        presenter->overlay_open = true;
        presenter->visible = false;
        opened = presenter->layer != nil;
    });
    return opened;
}

static void close_presenter_window(GpuPresenter *presenter) {
    run_on_main(^{
        // Serialize with render_to_presenter so a frame in flight on the decode/capture thread
        // finishes before we release the Metal layer/window. Without this, tearing down a
        // presenter while another device's frame renders could deref a freed CAMetalLayer.
        pthread_mutex_lock(&shared_metal.render_lock);
        if (presenter->window.parentWindow) {
            [presenter->window.parentWindow removeChildWindow:presenter->window];
        }
        [presenter->window orderOut:nil];
        presenter->window = nil;
        presenter->view = nil;
        presenter->content = nil;
        presenter->layer = nil;
        presenter->overlay_open = false;
        presenter->visible = false;
        pthread_mutex_unlock(&shared_metal.render_lock);
    });
}

static uint8_t *copy_bytes(const uint8_t *source, size_t length) {
    uint8_t *result = malloc(length);
    if (result) memcpy(result, source, length);
    return result;
}

static bool replace_parameter_set(uint8_t **target, size_t *target_size, const uint8_t *source, size_t length) {
    uint8_t *copy = copy_bytes(source, length);
    if (!copy) return false;
    free(*target);
    *target = copy;
    *target_size = length;
    return true;
}

static size_t start_code_length(const uint8_t *bytes, size_t offset, size_t length) {
    if (offset + 3 <= length && bytes[offset] == 0 && bytes[offset + 1] == 0 && bytes[offset + 2] == 1) return 3;
    if (offset + 4 <= length && bytes[offset] == 0 && bytes[offset + 1] == 0 && bytes[offset + 2] == 0 &&
        bytes[offset + 3] == 1) {
        return 4;
    }
    return 0;
}

static size_t find_start_code(const uint8_t *bytes, size_t offset, size_t length) {
    for (size_t i = offset; i + 3 <= length; ++i) {
        if (start_code_length(bytes, i, length)) return i;
    }
    return length;
}

static bool ensure_decoder_session(GpuDecoder *decoder) {
    if (decoder->decoder) return true;
    if (!decoder->sps || !decoder->pps) return false;
    const uint8_t *parameter_sets[] = {decoder->sps, decoder->pps};
    const size_t parameter_set_sizes[] = {decoder->sps_size, decoder->pps_size};
    if (decoder->format) {
        CFRelease(decoder->format);
        decoder->format = NULL;
    }
    if (CMVideoFormatDescriptionCreateFromH264ParameterSets(kCFAllocatorDefault, 2, parameter_sets,
            parameter_set_sizes, 4, &decoder->format) != noErr) {
        return false;
    }
    NSDictionary *specification = @{
        (__bridge NSString *) kVTVideoDecoderSpecification_EnableHardwareAcceleratedVideoDecoder: @YES,
        (__bridge NSString *) kVTVideoDecoderSpecification_RequireHardwareAcceleratedVideoDecoder: @YES,
    };
    NSDictionary *attributes = @{
        (__bridge NSString *) kCVPixelBufferPixelFormatTypeKey: @(kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange),
        (__bridge NSString *) kCVPixelBufferMetalCompatibilityKey: @YES,
    };
    VTDecompressionOutputCallbackRecord callback = {
        .decompressionOutputCallback = decoded_frame_callback,
        .decompressionOutputRefCon = decoder,
    };
    if (VTDecompressionSessionCreate(kCFAllocatorDefault, decoder->format, (__bridge CFDictionaryRef) specification,
            (__bridge CFDictionaryRef) attributes, &callback, &decoder->decoder) != noErr) {
        destroy_decoder_locked(decoder);
        return false;
    }
    CFTypeRef hardware = NULL;
    OSStatus hardware_status = VTSessionCopyProperty(decoder->decoder,
        kVTDecompressionPropertyKey_UsingHardwareAcceleratedVideoDecoder, kCFAllocatorDefault, &hardware);
    decoder->decoder_is_hardware = hardware_status == noErr && hardware &&
        CFGetTypeID(hardware) == CFBooleanGetTypeID() && CFBooleanGetValue((CFBooleanRef) hardware);
    if (hardware) CFRelease(hardware);
    if (!decoder->decoder_is_hardware) {
        destroy_decoder_locked(decoder);
        return false;
    }
    VTSessionSetProperty(decoder->decoder, kVTDecompressionPropertyKey_RealTime, kCFBooleanTrue);
    return true;
}

int64_t andy_hub_create_decoder(void) {
    if (!ensure_shared_metal()) return ANDY_HUB_INVALID_ID;
    pthread_mutex_lock(&hub_lock);
    for (int i = 0; i < ANDY_MAX_DECODERS; i++) {
        if (!decoders[i].active) {
            memset(&decoders[i], 0, sizeof(GpuDecoder));
            decoders[i].active = true;
            decoders[i].id = allocate_id();
            pthread_mutex_init(&decoders[i].latest_pixels_lock, NULL);
            pthread_mutex_init(&decoders[i].decoder_lock, NULL);
            pthread_mutex_init(&decoders[i].stats_lock, NULL);
            const int64_t id = decoders[i].id;
            pthread_mutex_unlock(&hub_lock);
            return id;
        }
    }
    pthread_mutex_unlock(&hub_lock);
    return ANDY_HUB_INVALID_ID;
}

void andy_hub_destroy_decoder(int64_t decoder_id) {
    pthread_mutex_lock(&hub_lock);
    GpuDecoder *decoder = find_decoder(decoder_id);
    if (!decoder) {
        pthread_mutex_unlock(&hub_lock);
        return;
    }
    for (int i = 0; i < ANDY_MAX_PRESENTERS; i++) {
        if (presenters[i].active && presenters[i].decoder_id == decoder_id) {
            destroy_presenter_locked(&presenters[i]);
        }
    }
    if (ios_decoder_id == decoder_id) {
        ios_decoder_id = ANDY_HUB_INVALID_ID;
    }
    pthread_mutex_lock(&decoder->decoder_lock);
    destroy_decoder_locked(decoder);
    pthread_mutex_unlock(&decoder->decoder_lock);
    decoder->active = false;
    pthread_mutex_unlock(&hub_lock);
}

int64_t andy_hub_create_presenter(int64_t decoder_id) {
    pthread_mutex_lock(&hub_lock);
    GpuDecoder *decoder = find_decoder(decoder_id);
    if (!decoder || decoder->presenter_count >= ANDY_MAX_PRESENTERS_PER_DECODER) {
        pthread_mutex_unlock(&hub_lock);
        return ANDY_HUB_INVALID_ID;
    }
    for (int i = 0; i < ANDY_MAX_PRESENTERS; i++) {
        if (!presenters[i].active) {
            // Slot is inactive: close_presenter_window already released every __strong ObjC field
            // (or it was never used), so zeroing the raw storage cannot drop a live reference.
            memset((void *) &presenters[i], 0, sizeof(GpuPresenter));
            presenters[i].active = true;
            presenters[i].id = allocate_id();
            presenters[i].decoder_id = decoder_id;
            presenters[i].pending_w = 1;
            presenters[i].pending_h = 1;
            decoder->presenter_ids[decoder->presenter_count++] = presenters[i].id;
            const int64_t id = presenters[i].id;
            pthread_mutex_unlock(&hub_lock);
            return id;
        }
    }
    pthread_mutex_unlock(&hub_lock);
    return ANDY_HUB_INVALID_ID;
}

// Assumes hub_lock is held. Callable from andy_hub_destroy_decoder (which already holds it) so we
// never re-enter the non-recursive hub_lock.
static void destroy_presenter_locked(GpuPresenter *presenter) {
    if (!presenter || !presenter->active) return;
    GpuDecoder *decoder = find_decoder(presenter->decoder_id);
    if (decoder) {
        for (int i = 0; i < decoder->presenter_count; i++) {
            if (decoder->presenter_ids[i] == presenter->id) {
                decoder->presenter_ids[i] = decoder->presenter_ids[decoder->presenter_count - 1];
                decoder->presenter_count--;
                break;
            }
        }
    }
    close_presenter_window(presenter);
    presenter->active = false;
}

void andy_hub_destroy_presenter(int64_t presenter_id) {
    pthread_mutex_lock(&hub_lock);
    destroy_presenter_locked(find_presenter(presenter_id));
    pthread_mutex_unlock(&hub_lock);
}

bool andy_hub_open_presenter_overlay(int64_t presenter_id) {
    GpuPresenter *presenter = find_presenter(presenter_id);
    if (!presenter) return false;
    if (presenter->overlay_open) return true;
    return open_presenter_window(presenter);
}

void andy_hub_set_presenter_visible(int64_t presenter_id, bool visible) {
    GpuPresenter *presenter = find_presenter(presenter_id);
    if (!presenter) return;
    presenter->visible = visible;
    run_on_main(^{
        if (!presenter->window) return;
        if (visible) {
            // Match the legacy overlay: only orderFront when hidden. Re-ordering an already
            // visible borderless window on every click flashes the black AWT Canvas underneath.
            if (!presenter->window.isVisible) {
                [presenter->window orderFront:nil];
            }
        } else if (presenter->window.isVisible) {
            [presenter->window orderOut:nil];
        }
    });
}

void andy_hub_update_presenter_geometry(int64_t presenter_id, int x, int y, int width, int height,
                                        double scale, int parent_window_number) {
    GpuPresenter *presenter = find_presenter(presenter_id);
    if (!presenter || !presenter->overlay_open) return;
    presenter->pending_x = x;
    presenter->pending_y = y;
    presenter->pending_w = MAX(1, width);
    presenter->pending_h = MAX(1, height);
    presenter->pending_scale = scale;
    presenter->pending_parent_window = parent_window_number;
    schedule_presenter_geometry(presenter);
}

void andy_hub_set_presenter_fill_host(int64_t presenter_id, bool fill_host) {
    GpuPresenter *presenter = find_presenter(presenter_id);
    if (presenter) presenter->fill_host = fill_host;
}

void andy_hub_set_presenter_content_size(int64_t presenter_id, int width, int height) {
    GpuPresenter *presenter = find_presenter(presenter_id);
    if (!presenter) return;
    presenter->content_width = width;
    presenter->content_height = height;
}

void andy_hub_remember_latest_pixels(int64_t decoder_id, CVPixelBufferRef pixels) {
    GpuDecoder *decoder = find_decoder(decoder_id);
    if (!decoder) return;
    CVPixelBufferRetain(pixels);
    pthread_mutex_lock(&decoder->latest_pixels_lock);
    CVPixelBufferRef old = decoder->latest_pixels;
    decoder->latest_pixels = pixels;
    pthread_mutex_unlock(&decoder->latest_pixels_lock);
    if (old) CVPixelBufferRelease(old);
}

void andy_hub_render_pixel_buffer(int64_t decoder_id, CVPixelBufferRef pixels, bool input_changed_probe,
                                  uint64_t packet_ticks, uint64_t transport_ticks,
                                  bool record_presentation_metrics) {
    GpuDecoder *decoder = find_decoder(decoder_id);
    if (!decoder) return;
    andy_hub_remember_latest_pixels(decoder_id, pixels);
    fan_out_render(decoder, pixels, input_changed_probe, packet_ticks, transport_ticks, record_presentation_metrics);
}

bool andy_hub_present_solid_bgra(int64_t decoder_id, int width, int height,
                                 uint8_t blue, uint8_t green, uint8_t red, uint8_t alpha) {
    if (width <= 0 || height <= 0) return false;
    GpuDecoder *decoder = find_decoder(decoder_id);
    if (!decoder) return false;
    NSDictionary *attrs = @{
        (NSString *) kCVPixelBufferIOSurfacePropertiesKey: @{},
        (NSString *) kCVPixelBufferMetalCompatibilityKey: @YES,
    };
    CVPixelBufferRef pixels = NULL;
    if (CVPixelBufferCreate(kCFAllocatorDefault, (size_t) width, (size_t) height,
                            kCVPixelFormatType_32BGRA, (__bridge CFDictionaryRef) attrs, &pixels) != kCVReturnSuccess ||
        !pixels) {
        return false;
    }
    CVPixelBufferLockBaseAddress(pixels, 0);
    uint8_t *base = (uint8_t *) CVPixelBufferGetBaseAddress(pixels);
    const size_t stride = CVPixelBufferGetBytesPerRow(pixels);
    for (int y = 0; y < height; y++) {
        uint8_t *row = base + ((size_t) y * stride);
        for (int x = 0; x < width; x++) {
            row[x * 4 + 0] = blue;
            row[x * 4 + 1] = green;
            row[x * 4 + 2] = red;
            row[x * 4 + 3] = alpha;
        }
    }
    CVPixelBufferUnlockBaseAddress(pixels, 0);
    andy_hub_render_pixel_buffer(decoder_id, pixels, false, 0, 0, true);
    CVPixelBufferRelease(pixels);
    return true;
}

void andy_hub_repaint_presenter(int64_t presenter_id) {
    GpuPresenter *presenter = find_presenter(presenter_id);
    if (!presenter) return;
    GpuDecoder *decoder = find_decoder(presenter->decoder_id);
    if (!decoder) return;
    pthread_mutex_lock(&decoder->latest_pixels_lock);
    CVPixelBufferRef pixels = decoder->latest_pixels;
    if (pixels) CVPixelBufferRetain(pixels);
    pthread_mutex_unlock(&decoder->latest_pixels_lock);
    if (!pixels) return;
    render_to_presenter(presenter, decoder, pixels, false, 0, 0, false);
    CVPixelBufferRelease(pixels);
}

void andy_hub_repaint_decoder(int64_t decoder_id) {
    GpuDecoder *decoder = find_decoder(decoder_id);
    if (!decoder) return;
    pthread_mutex_lock(&decoder->latest_pixels_lock);
    CVPixelBufferRef pixels = decoder->latest_pixels;
    if (pixels) CVPixelBufferRetain(pixels);
    pthread_mutex_unlock(&decoder->latest_pixels_lock);
    if (!pixels) return;
    fan_out_render(decoder, pixels, false, 0, 0, false);
    CVPixelBufferRelease(pixels);
}

bool andy_hub_consume_h264(int64_t decoder_id, const uint8_t *bytes, size_t length) {
    GpuDecoder *decoder = find_decoder(decoder_id);
    if (!decoder || !bytes || !length) return false;
    pthread_mutex_lock(&decoder->decoder_lock);
    size_t first = find_start_code(bytes, 0, length);
    if (first == length) {
        pthread_mutex_unlock(&decoder->decoder_lock);
        return false;
    }
    uint8_t *avcc = malloc(length + (length / 3) + 1);
    if (!avcc) {
        pthread_mutex_unlock(&decoder->decoder_lock);
        return false;
    }
    size_t avcc_length = 0;
    for (size_t marker = first; marker < length;) {
        size_t marker_length = start_code_length(bytes, marker, length);
        size_t nal_start = marker + marker_length;
        size_t next_marker = find_start_code(bytes, nal_start, length);
        size_t nal_length = next_marker - nal_start;
        if (nal_length) {
            uint8_t type = bytes[nal_start] & 0x1f;
            if (type == 7) {
                if (!replace_parameter_set(&decoder->sps, &decoder->sps_size, bytes + nal_start, nal_length)) {
                    free(avcc);
                    pthread_mutex_unlock(&decoder->decoder_lock);
                    return false;
                }
            } else if (type == 8) {
                if (!replace_parameter_set(&decoder->pps, &decoder->pps_size, bytes + nal_start, nal_length)) {
                    free(avcc);
                    pthread_mutex_unlock(&decoder->decoder_lock);
                    return false;
                }
            } else if (type == 1 || type == 5) {
                avcc[avcc_length++] = (uint8_t) (nal_length >> 24);
                avcc[avcc_length++] = (uint8_t) (nal_length >> 16);
                avcc[avcc_length++] = (uint8_t) (nal_length >> 8);
                avcc[avcc_length++] = (uint8_t) nal_length;
                memcpy(avcc + avcc_length, bytes + nal_start, nal_length);
                avcc_length += nal_length;
            }
        }
        marker = next_marker;
    }
    if (!ensure_decoder_session(decoder)) {
        free(avcc);
        pthread_mutex_unlock(&decoder->decoder_lock);
        return avcc_length == 0;
    }
    if (!avcc_length) {
        free(avcc);
        pthread_mutex_unlock(&decoder->decoder_lock);
        return true;
    }
    CMBlockBufferRef block = NULL;
    if (CMBlockBufferCreateWithMemoryBlock(kCFAllocatorDefault, NULL, avcc_length, kCFAllocatorDefault, NULL, 0,
            avcc_length, 0, &block) != kCMBlockBufferNoErr) {
        free(avcc);
        pthread_mutex_unlock(&decoder->decoder_lock);
        return false;
    }
    CMBlockBufferReplaceDataBytes(avcc, block, 0, avcc_length);
    free(avcc);
    CMSampleTimingInfo timing = {kCMTimeInvalid, kCMTimeInvalid, kCMTimeInvalid};
    size_t sample_size = avcc_length;
    CMSampleBufferRef sample = NULL;
    if (CMSampleBufferCreateReady(kCFAllocatorDefault, block, decoder->format, 1, 1, &timing, 1, &sample_size,
            &sample) != noErr) {
        CFRelease(block);
        pthread_mutex_unlock(&decoder->decoder_lock);
        return false;
    }
    CFRelease(block);
    const uint64_t packet_ticks = mach_continuous_time();
    const OSStatus decode_status = VTDecompressionSessionDecodeFrame(decoder->decoder, sample,
        kVTDecodeFrame_EnableAsynchronousDecompression | kVTDecodeFrame_1xRealTimePlayback,
        (void *) (uintptr_t) packet_ticks, NULL);
    CFRelease(sample);
    pthread_mutex_unlock(&decoder->decoder_lock);
    return decode_status == noErr;
}

void andy_hub_record_input(int64_t decoder_id) {
    GpuDecoder *decoder = find_decoder(decoder_id);
    if (!decoder) return;
    pthread_mutex_lock(&decoder->stats_lock);
    decoder->pending_input_ticks = mach_continuous_time();
    pthread_mutex_unlock(&decoder->stats_lock);
}

void andy_hub_record_transport_ingress(int64_t decoder_id) {
    GpuDecoder *decoder = find_decoder(decoder_id);
    if (!decoder) return;
    pthread_mutex_lock(&decoder->stats_lock);
    decoder->transport_ingress_ticks = mach_continuous_time();
    pthread_mutex_unlock(&decoder->stats_lock);
}

uint64_t andy_hub_frames_presented(int64_t decoder_id) {
    GpuDecoder *decoder = find_decoder(decoder_id);
    if (!decoder) return 0;
    pthread_mutex_lock(&decoder->stats_lock);
    const uint64_t count = decoder->frames_presented;
    pthread_mutex_unlock(&decoder->stats_lock);
    return count;
}

bool andy_hub_has_decoded_frame(int64_t decoder_id) {
    GpuDecoder *decoder = find_decoder(decoder_id);
    if (!decoder) return false;
    pthread_mutex_lock(&decoder->latest_pixels_lock);
    const bool ready = decoder->latest_pixels != NULL;
    pthread_mutex_unlock(&decoder->latest_pixels_lock);
    return ready;
}

bool andy_hub_is_hardware_ready(int64_t decoder_id) {
    GpuDecoder *decoder = find_decoder(decoder_id);
    if (!decoder) return false;
    pthread_mutex_lock(&decoder->decoder_lock);
    const bool ready = decoder->decoder != NULL && decoder->decoder_is_hardware;
    pthread_mutex_unlock(&decoder->decoder_lock);
    return ready;
}

bool andy_hub_latency_probe_changed(int64_t decoder_id, CVPixelBufferRef pixels) {
    GpuDecoder *decoder = find_decoder(decoder_id);
    if (!decoder) return false;
    pthread_mutex_lock(&decoder->stats_lock);
    const bool input_pending = decoder->pending_input_ticks != 0;
    pthread_mutex_unlock(&decoder->stats_lock);
    (void) pixels;
    return input_pending;
}

void andy_hub_set_ios_source_active(int64_t decoder_id, bool active) {
    GpuDecoder *decoder = find_decoder(decoder_id);
    if (!decoder) return;
    pthread_mutex_lock(&decoder->stats_lock);
    decoder->ios_source_active = active;
    pthread_mutex_unlock(&decoder->stats_lock);
}

bool andy_hub_is_ios_source_active(int64_t decoder_id) {
    GpuDecoder *decoder = find_decoder(decoder_id);
    if (!decoder) return false;
    pthread_mutex_lock(&decoder->stats_lock);
    const bool active = decoder->ios_source_active;
    pthread_mutex_unlock(&decoder->stats_lock);
    return active;
}

void andy_hub_configure_latency_probe(int64_t decoder_id, float left, float top, float width, float height) {
    GpuDecoder *decoder = find_decoder(decoder_id);
    if (!decoder) return;
    pthread_mutex_lock(&decoder->stats_lock);
    decoder->latency_probe_enabled = true;
    decoder->probe_left = left;
    decoder->probe_top = top;
    decoder->probe_width = width;
    decoder->probe_height = height;
    pthread_mutex_unlock(&decoder->stats_lock);
}

uint64_t andy_hub_latency_probe_transitions(int64_t decoder_id) {
    GpuDecoder *decoder = find_decoder(decoder_id);
    if (!decoder) return 0;
    pthread_mutex_lock(&decoder->stats_lock);
    const uint64_t count = decoder->probe_transitions;
    pthread_mutex_unlock(&decoder->stats_lock);
    return count;
}

void andy_hub_update_overlay(int64_t presenter_id, bool grid_enabled, float grid_step_x, float grid_step_y,
                             float grid_r, float grid_g, float grid_b, float grid_a, bool ruler_enabled,
                             float ruler_x, float ruler_y, float ruler_r, float ruler_g, float ruler_b, float ruler_a,
                             float source_width, float source_height, bool picker_enabled, float highlight_left,
                             float highlight_top, float highlight_right, float highlight_bottom) {
    GpuPresenter *presenter = find_presenter(presenter_id);
    if (!presenter) return;
    presenter->overlay.grid[0] = grid_enabled ? 1.0f : 0.0f;
    presenter->overlay.grid[1] = grid_step_x;
    presenter->overlay.grid[2] = grid_step_y;
    presenter->overlay.grid_color[0] = grid_r;
    presenter->overlay.grid_color[1] = grid_g;
    presenter->overlay.grid_color[2] = grid_b;
    presenter->overlay.grid_color[3] = grid_a;
    presenter->overlay.ruler[0] = ruler_enabled ? 1.0f : 0.0f;
    presenter->overlay.ruler[1] = ruler_x;
    presenter->overlay.ruler[2] = ruler_y;
    presenter->overlay.ruler_color[0] = ruler_r;
    presenter->overlay.ruler_color[1] = ruler_g;
    presenter->overlay.ruler_color[2] = ruler_b;
    presenter->overlay.ruler_color[3] = ruler_a;
    presenter->overlay.source_size[0] = source_width;
    presenter->overlay.source_size[1] = source_height;
    presenter->overlay.picker[0] = picker_enabled ? 1.0f : 0.0f;
    presenter->overlay.highlight[0] = highlight_left;
    presenter->overlay.highlight[1] = highlight_top;
    presenter->overlay.highlight[2] = highlight_right;
    presenter->overlay.highlight[3] = highlight_bottom;
    andy_hub_repaint_presenter(presenter_id);
}

void andy_hub_update_picker_point(int64_t presenter_id, float normalized_x, float normalized_y, bool visible) {
    GpuPresenter *presenter = find_presenter(presenter_id);
    if (!presenter) return;
    presenter->overlay.picker[1] = normalized_x;
    presenter->overlay.picker[2] = normalized_y;
    presenter->overlay.picker[3] = visible ? 1.0f : 0.0f;
    andy_hub_repaint_presenter(presenter_id);
}

int andy_hub_inspect_pixel(int64_t decoder_id, float normalized_x, float normalized_y) {
    (void) decoder_id;
    (void) normalized_x;
    (void) normalized_y;
    return -1;
}

static float p95_from_samples(double *samples, size_t count) {
    if (!count) return -1.0f;
    double sorted[120];
    memcpy(sorted, samples, count * sizeof(double));
    for (size_t i = 0; i < count; i++) {
        for (size_t j = i + 1; j < count; j++) {
            if (sorted[j] < sorted[i]) {
                const double tmp = sorted[i];
                sorted[i] = sorted[j];
                sorted[j] = tmp;
            }
        }
    }
    const size_t index = (size_t) fmin(count - 1, floor(count * 0.95));
    return (float) sorted[index];
}

float andy_hub_p95_input_to_present_millis(int64_t decoder_id) {
    GpuDecoder *decoder = find_decoder(decoder_id);
    if (!decoder) return -1.0f;
    pthread_mutex_lock(&decoder->stats_lock);
    const float value = p95_from_samples(decoder->input_to_present_millis, decoder->input_to_present_count);
    pthread_mutex_unlock(&decoder->stats_lock);
    return value;
}

float andy_hub_p95_packet_to_present_millis(int64_t decoder_id) {
    GpuDecoder *decoder = find_decoder(decoder_id);
    if (!decoder) return -1.0f;
    pthread_mutex_lock(&decoder->stats_lock);
    const float value = p95_from_samples(decoder->packet_to_present_millis, decoder->packet_to_present_count);
    pthread_mutex_unlock(&decoder->stats_lock);
    return value;
}

float andy_hub_p95_transport_to_present_millis(int64_t decoder_id) {
    GpuDecoder *decoder = find_decoder(decoder_id);
    if (!decoder) return -1.0f;
    pthread_mutex_lock(&decoder->stats_lock);
    const float value = p95_from_samples(decoder->transport_to_present_millis, decoder->transport_to_present_count);
    pthread_mutex_unlock(&decoder->stats_lock);
    return value;
}

void andy_hub_set_ios_decoder(int64_t decoder_id) {
    ios_decoder_id = decoder_id;
}

void andy_hub_clear_ios_decoder(int64_t decoder_id) {
    // Compare-and-clear: a decoder that never owned iOS routing (Android) must not clobber the
    // slot for a live iOS mirror bound to a different decoder.
    pthread_mutex_lock(&hub_lock);
    if (ios_decoder_id == decoder_id) {
        ios_decoder_id = ANDY_HUB_INVALID_ID;
    }
    pthread_mutex_unlock(&hub_lock);
}

int64_t andy_hub_ios_decoder(void) {
    return ios_decoder_id;
}

#define GPU_JNI_METHOD(name) Java_app_andy_desktop_service_mirror_GpuMirrorJni_##name

JNIEXPORT jlong JNICALL GPU_JNI_METHOD(nativeCreateDecoder)(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return (jlong) andy_hub_create_decoder();
}

JNIEXPORT void JNICALL GPU_JNI_METHOD(nativeDestroyDecoder)(JNIEnv *env, jclass clazz, jlong decoder_id) {
    (void) env;
    (void) clazz;
    andy_hub_destroy_decoder((int64_t) decoder_id);
}

JNIEXPORT jlong JNICALL GPU_JNI_METHOD(nativeCreatePresenter)(JNIEnv *env, jclass clazz, jlong decoder_id) {
    (void) env;
    (void) clazz;
    return (jlong) andy_hub_create_presenter((int64_t) decoder_id);
}

JNIEXPORT void JNICALL GPU_JNI_METHOD(nativeDestroyPresenter)(JNIEnv *env, jclass clazz, jlong presenter_id) {
    (void) env;
    (void) clazz;
    andy_hub_destroy_presenter((int64_t) presenter_id);
}

JNIEXPORT jboolean JNICALL GPU_JNI_METHOD(nativeOpenPresenterOverlay)(JNIEnv *env, jclass clazz, jlong presenter_id) {
    (void) env;
    (void) clazz;
    return andy_hub_open_presenter_overlay((int64_t) presenter_id) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL GPU_JNI_METHOD(nativeSetPresenterVisible)(JNIEnv *env, jclass clazz, jlong presenter_id,
                                                                 jboolean visible) {
    (void) env;
    (void) clazz;
    andy_hub_set_presenter_visible((int64_t) presenter_id, visible == JNI_TRUE);
}

JNIEXPORT void JNICALL GPU_JNI_METHOD(nativeUpdatePresenterGeometry)(JNIEnv *env, jclass clazz, jlong presenter_id,
                                                                   jint x, jint y, jint width, jint height,
                                                                   jdouble scale, jint parent_window_number) {
    (void) env;
    (void) clazz;
    andy_hub_update_presenter_geometry((int64_t) presenter_id, x, y, width, height, scale, parent_window_number);
}

JNIEXPORT void JNICALL GPU_JNI_METHOD(nativeSetPresenterFillHost)(JNIEnv *env, jclass clazz, jlong presenter_id,
                                                                  jboolean fill_host) {
    (void) env;
    (void) clazz;
    andy_hub_set_presenter_fill_host((int64_t) presenter_id, fill_host == JNI_TRUE);
}

JNIEXPORT void JNICALL GPU_JNI_METHOD(nativeSetPresenterContentSize)(JNIEnv *env, jclass clazz, jlong presenter_id,
                                                                     jint width, jint height) {
    (void) env;
    (void) clazz;
    andy_hub_set_presenter_content_size((int64_t) presenter_id, width, height);
}

JNIEXPORT void JNICALL GPU_JNI_METHOD(nativeRepaintPresenter)(JNIEnv *env, jclass clazz, jlong presenter_id) {
    (void) env;
    (void) clazz;
    andy_hub_repaint_presenter((int64_t) presenter_id);
}

JNIEXPORT jboolean JNICALL GPU_JNI_METHOD(nativeConsumeH264)(JNIEnv *env, jclass clazz, jlong decoder_id,
                                                             jbyteArray packet) {
    if (!packet) return JNI_FALSE;
    jsize length = (*env)->GetArrayLength(env, packet);
    if (length <= 0) return JNI_FALSE;
    jbyte *bytes = (*env)->GetByteArrayElements(env, packet, NULL);
    if (!bytes) return JNI_FALSE;
    const bool ok = andy_hub_consume_h264((int64_t) decoder_id, (const uint8_t *) bytes, (size_t) length);
    (*env)->ReleaseByteArrayElements(env, packet, bytes, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL GPU_JNI_METHOD(nativePresentSolidBgra)(JNIEnv *env, jclass clazz, jlong decoder_id,
                                                                  jint width, jint height, jint blue, jint green,
                                                                  jint red, jint alpha) {
    (void) env;
    (void) clazz;
    return andy_hub_present_solid_bgra((int64_t) decoder_id, width, height,
                                       (uint8_t) (blue & 0xff), (uint8_t) (green & 0xff),
                                       (uint8_t) (red & 0xff), (uint8_t) (alpha & 0xff))
               ? JNI_TRUE
               : JNI_FALSE;
}

JNIEXPORT void JNICALL GPU_JNI_METHOD(nativeRecordInput)(JNIEnv *env, jclass clazz, jlong decoder_id) {
    (void) env;
    (void) clazz;
    andy_hub_record_input((int64_t) decoder_id);
}

JNIEXPORT void JNICALL GPU_JNI_METHOD(nativeRecordTransportIngress)(JNIEnv *env, jclass clazz, jlong decoder_id) {
    (void) env;
    (void) clazz;
    andy_hub_record_transport_ingress((int64_t) decoder_id);
}

JNIEXPORT jlong JNICALL GPU_JNI_METHOD(nativeFramesPresented)(JNIEnv *env, jclass clazz, jlong decoder_id) {
    (void) env;
    (void) clazz;
    return (jlong) andy_hub_frames_presented((int64_t) decoder_id);
}

JNIEXPORT jboolean JNICALL GPU_JNI_METHOD(nativeHasDecodedFrame)(JNIEnv *env, jclass clazz, jlong decoder_id) {
    (void) env;
    (void) clazz;
    return andy_hub_has_decoded_frame((int64_t) decoder_id) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL GPU_JNI_METHOD(nativeIsHardwareReady)(JNIEnv *env, jclass clazz, jlong decoder_id) {
    (void) env;
    (void) clazz;
    return andy_hub_is_hardware_ready((int64_t) decoder_id) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL GPU_JNI_METHOD(nativeSetIosDecoder)(JNIEnv *env, jclass clazz, jlong decoder_id) {
    (void) env;
    (void) clazz;
    andy_hub_set_ios_decoder((int64_t) decoder_id);
}

JNIEXPORT jlong JNICALL GPU_JNI_METHOD(nativeIosDecoder)(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return (jlong) andy_hub_ios_decoder();
}

JNIEXPORT void JNICALL GPU_JNI_METHOD(nativeClearIosDecoder)(JNIEnv *env, jclass clazz, jlong decoder_id) {
    (void) env;
    (void) clazz;
    andy_hub_clear_ios_decoder((int64_t) decoder_id);
}

JNIEXPORT void JNICALL GPU_JNI_METHOD(nativeUpdatePresenterOverlay)(
        JNIEnv *env, jclass clazz, jlong presenter_id, jboolean grid_enabled, jfloat grid_step_x, jfloat grid_step_y,
        jfloat grid_r, jfloat grid_g, jfloat grid_b, jfloat grid_a, jboolean ruler_enabled, jfloat ruler_x,
        jfloat ruler_y, jfloat ruler_r, jfloat ruler_g, jfloat ruler_b, jfloat ruler_a, jfloat source_width,
        jfloat source_height, jboolean picker_enabled, jfloat highlight_left, jfloat highlight_top,
        jfloat highlight_right, jfloat highlight_bottom) {
    (void) env;
    (void) clazz;
    andy_hub_update_overlay((int64_t) presenter_id, grid_enabled == JNI_TRUE, grid_step_x, grid_step_y, grid_r, grid_g,
        grid_b, grid_a, ruler_enabled == JNI_TRUE, ruler_x, ruler_y, ruler_r, ruler_g, ruler_b, ruler_a,
        source_width, source_height, picker_enabled == JNI_TRUE, highlight_left, highlight_top, highlight_right,
        highlight_bottom);
}

JNIEXPORT void JNICALL GPU_JNI_METHOD(nativeUpdatePresenterPickerPoint)(
        JNIEnv *env, jclass clazz, jlong presenter_id, jfloat normalized_x, jfloat normalized_y, jboolean visible) {
    (void) env;
    (void) clazz;
    andy_hub_update_picker_point((int64_t) presenter_id, normalized_x, normalized_y, visible == JNI_TRUE);
}

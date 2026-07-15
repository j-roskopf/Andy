#include <jni.h>
#include <jawt.h>
#include <jawt_md.h>
#include <math.h>

#import <AppKit/AppKit.h>
#import <QuartzCore/CAMetalLayer.h>
#import <QuartzCore/CATransaction.h>
#import <Metal/Metal.h>
#import <MetalKit/MetalKit.h>
#import <VideoToolbox/VideoToolbox.h>
#import <CoreVideo/CVMetalTextureCache.h>
#include <mach/mach_time.h>
#include <pthread.h>
#include <stdio.h>
#include <string.h>

typedef struct {
    float grid[4];       // enabled, normalized x step, normalized y step, unused
    float ruler[4];      // enabled, normalized x, normalized y, unused
    float highlight[4];  // normalized left, top, right, bottom; disabled when right <= left
    float grid_color[4];
    float ruler_color[4];
    float picker[4];     // enabled, normalized x, normalized y, cursor is in content
    float source_size[4]; // decoded coordinate space used for ruler labels
} AndyMirrorOverlay;

typedef struct {
    CAMetalLayer *layer;
    id<MTLDevice> device;
    id<MTLCommandQueue> queue;
    id<MTLRenderPipelineState> pipeline;
    CVMetalTextureCacheRef texture_cache;
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
    AndyMirrorOverlay overlay;
    CVPixelBufferRef latest_pixels;
} AndyMirrorRenderer;

static AndyMirrorRenderer renderer = {0};
static pthread_mutex_t latest_pixels_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t stats_lock = PTHREAD_MUTEX_INITIALIZER;
// Serialize decoder create/decode/destroy. VideoToolbox sessions are not safe to Invalidate
// while DecodeFrame (or its async callbacks) are in flight, and long CPU locks on live
// CVPixelBuffers from bug-capture sampling have correlated with CoreMedia null-mutex crashes.
static pthread_mutex_t decoder_lock = PTHREAD_MUTEX_INITIALIZER;
// AppKit is owned by AWT's NSApplication in this process. Closing an NSWindow while its
// CAMetalLayer still has a Core Animation transaction in flight can make that host release an
// animation object after the layer is gone (a native objc_release crash on the AppKit thread).
// Keep one Andy-owned window for the lifetime of the JVM and hide/reuse it between mirror
// sessions instead. Its Metal resources remain owned by `renderer` and are released normally.
static __strong NSWindow *andy_popout_window = nil;
static __strong MTKView *andy_popout_view = nil;
static __strong NSView *andy_popout_content = nil;
static __strong NSView *andy_guide_overlay = nil;
static bool andy_inline_overlay = false;

// Coalesce geometry updates: AWT resize callbacks can fire dozens of times per drag. Syncing onto
// the AppKit main queue (or calling setFrame:display:YES / windowNumberAtPoint mid-resize) deadlocks
// or re-enters the event loop and freezes the whole JVM UI.
static jint andy_pending_overlay_x = 0;
static jint andy_pending_overlay_y = 0;
static jint andy_pending_overlay_w = 1;
static jint andy_pending_overlay_h = 1;
static jdouble andy_pending_overlay_scale = 1.0;
static bool andy_overlay_geometry_scheduled = false;
static bool andy_overlay_suppressed = false;

static NSColor *guide_color(const float components[4]) {
    return [NSColor colorWithSRGBRed:fmaxf(0.0f, fminf(1.0f, components[0]))
                               green:fmaxf(0.0f, fminf(1.0f, components[1]))
                                blue:fmaxf(0.0f, fminf(1.0f, components[2]))
                               alpha:fmaxf(0.0f, fminf(1.0f, components[3]))];
}

static void draw_guide_badge(NSString *text, NSPoint origin, NSRect bounds) {
    NSFont *font = [NSFont monospacedSystemFontOfSize:10.0 weight:NSFontWeightMedium];
    if (!font) font = [NSFont userFixedPitchFontOfSize:10.0];
    if (!font) font = [NSFont systemFontOfSize:10.0];
    // NSDictionary literals throw when any value is nil. Some AppKit configurations do not
    // provide a monospaced system font, so build the optional text attributes defensively.
    NSMutableDictionary *attributes = [NSMutableDictionary dictionaryWithCapacity:2];
    if (font) [attributes setObject:font forKey:NSFontAttributeName];
    NSColor *foreground = [NSColor whiteColor];
    if (foreground) [attributes setObject:foreground forKey:NSForegroundColorAttributeName];
    NSSize text_size = [text sizeWithAttributes:attributes];
    NSRect rect = NSMakeRect(
        fmax(NSMinX(bounds) + 4.0, fmin(origin.x, NSMaxX(bounds) - text_size.width - 14.0)),
        fmax(NSMinY(bounds) + 4.0, fmin(origin.y, NSMaxY(bounds) - text_size.height - 10.0)),
        text_size.width + 10.0,
        text_size.height + 6.0
    );
    [[NSColor colorWithSRGBRed:0.85 green:0.44 blue:0.29 alpha:0.96] setFill];
    [[NSBezierPath bezierPathWithRoundedRect:rect xRadius:5.0 yRadius:5.0] fill];
    [text drawAtPoint:NSMakePoint(NSMinX(rect) + 5.0, NSMinY(rect) + 3.0) withAttributes:attributes];
}

/**
 * The Metal presenter owns the live pixels, so the AppKit guide layer builds the small lens
 * directly from the latest decoded CVPixelBuffer. This keeps Design inspection visible above
 * the separate native window without copying full video frames through the JVM.
 */
static void draw_picker_magnifier(const AndyMirrorOverlay overlay, NSRect bounds) {
    if (overlay.picker[0] <= 0.5f || overlay.picker[3] <= 0.5f) return;
    pthread_mutex_lock(&latest_pixels_lock);
    CVPixelBufferRef pixels = renderer.latest_pixels;
    if (pixels) CVPixelBufferRetain(pixels);
    pthread_mutex_unlock(&latest_pixels_lock);
    if (!pixels) return;

    const size_t width = CVPixelBufferGetWidthOfPlane(pixels, 0);
    const size_t height = CVPixelBufferGetHeightOfPlane(pixels, 0);
    if (!width || !height ||
        CVPixelBufferLockBaseAddress(pixels, kCVPixelBufferLock_ReadOnly) != kCVReturnSuccess) {
        CVPixelBufferRelease(pixels);
        return;
    }
    const int sample_radius = 10;
    const size_t sample_size = (size_t) (sample_radius * 2 + 1);
    uint8_t *rgba = calloc(sample_size * sample_size, 4);
    const uint8_t *y_base = CVPixelBufferGetBaseAddressOfPlane(pixels, 0);
    const uint8_t *uv_base = CVPixelBufferGetBaseAddressOfPlane(pixels, 1);
    const size_t y_stride = CVPixelBufferGetBytesPerRowOfPlane(pixels, 0);
    const size_t uv_stride = CVPixelBufferGetBytesPerRowOfPlane(pixels, 1);
    const int center_x = (int) fmaxf(0.0f, fminf((float) width - 1.0f, overlay.picker[1] * width));
    const int center_y = (int) fmaxf(0.0f, fminf((float) height - 1.0f, overlay.picker[2] * height));
    for (size_t row = 0; row < sample_size; row++) {
        const int source_y = (int) fmaxf(0.0f, fminf((float) height - 1.0f, center_y + (int) row - sample_radius));
        for (size_t column = 0; column < sample_size; column++) {
            const int source_x = (int) fmaxf(0.0f, fminf((float) width - 1.0f, center_x + (int) column - sample_radius));
            const int yy = y_base[source_y * y_stride + source_x] - 16;
            const size_t uv_x = (source_x / 2) * 2;
            const size_t uv_y = source_y / 2;
            const int uu = uv_base[uv_y * uv_stride + uv_x] - 128;
            const int vv = uv_base[uv_y * uv_stride + uv_x + 1] - 128;
            uint8_t *pixel = rgba + (row * sample_size + column) * 4;
            pixel[0] = (uint8_t) fmax(0, fmin(255, (298 * yy + 409 * vv + 128) >> 8));
            pixel[1] = (uint8_t) fmax(0, fmin(255, (298 * yy - 100 * uu - 208 * vv + 128) >> 8));
            pixel[2] = (uint8_t) fmax(0, fmin(255, (298 * yy + 516 * uu + 128) >> 8));
            pixel[3] = 255;
        }
    }
    CVPixelBufferUnlockBaseAddress(pixels, kCVPixelBufferLock_ReadOnly);
    CVPixelBufferRelease(pixels);

    CGColorSpaceRef color_space = CGColorSpaceCreateWithName(kCGColorSpaceSRGB);
    CGContextRef bitmap = CGBitmapContextCreate(
        rgba, sample_size, sample_size, 8, sample_size * 4, color_space,
        kCGImageAlphaPremultipliedLast | kCGBitmapByteOrder32Big
    );
    CGColorSpaceRelease(color_space);
    CGImageRef image = bitmap ? CGBitmapContextCreateImage(bitmap) : NULL;
    if (image) {
        const CGFloat x = NSMinX(bounds) + bounds.size.width * overlay.picker[1];
        const CGFloat y = NSMaxY(bounds) - bounds.size.height * overlay.picker[2];
        const CGFloat radius = fmin(48.0, bounds.size.height * 0.092);
        NSBezierPath *clip = [NSBezierPath bezierPathWithOvalInRect:NSMakeRect(x - radius, y - radius, radius * 2.0, radius * 2.0)];
        [NSGraphicsContext saveGraphicsState];
        [clip addClip];
        CGContextDrawImage(NSGraphicsContext.currentContext.CGContext, NSMakeRect(x - radius, y - radius, radius * 2.0, radius * 2.0), image);
        [NSGraphicsContext restoreGraphicsState];
        CGImageRelease(image);
    }
    if (bitmap) CGContextRelease(bitmap);
    free(rgba);
}

@interface AndyMirrorGuideOverlay : NSView
@end

@implementation AndyMirrorGuideOverlay

- (BOOL)isOpaque {
    return NO;
}

- (void)drawRect:(NSRect)dirty_rect {
    (void) dirty_rect;
    const AndyMirrorOverlay overlay = renderer.overlay;
    const NSRect bounds = self.bounds;
    if (NSIsEmptyRect(bounds)) return;

    if (overlay.grid[0] > 0.5f && overlay.grid[1] > 0.0f) {
        const CGFloat step_x = fmax(2.0, bounds.size.width * overlay.grid[1]);
        // Grid guides are a visual measuring aid, so their cells must be square in the rendered
        // mirror rather than inheriting a potentially mismatched stream/display aspect ratio.
        const CGFloat step_y = step_x;
        NSBezierPath *grid = [NSBezierPath bezierPath];
        grid.lineWidth = 1.0;
        for (CGFloat x = NSMinX(bounds); x <= NSMaxX(bounds); x += step_x) {
            [grid moveToPoint:NSMakePoint(x, NSMinY(bounds))];
            [grid lineToPoint:NSMakePoint(x, NSMaxY(bounds))];
        }
        for (CGFloat y = NSMinY(bounds); y <= NSMaxY(bounds); y += step_y) {
            [grid moveToPoint:NSMakePoint(NSMinX(bounds), y)];
            [grid lineToPoint:NSMakePoint(NSMaxX(bounds), y)];
        }
        [guide_color(overlay.grid_color) setStroke];
        [grid stroke];
    }

    if (overlay.ruler[0] > 0.5f) {
        const CGFloat x = NSMinX(bounds) + bounds.size.width * overlay.ruler[1];
        const CGFloat y = NSMaxY(bounds) - bounds.size.height * overlay.ruler[2];
        NSBezierPath *ruler = [NSBezierPath bezierPath];
        ruler.lineWidth = 1.5;
        [ruler moveToPoint:NSMakePoint(x, NSMinY(bounds))];
        [ruler lineToPoint:NSMakePoint(x, NSMaxY(bounds))];
        [ruler moveToPoint:NSMakePoint(NSMinX(bounds), y)];
        [ruler lineToPoint:NSMakePoint(NSMaxX(bounds), y)];
        [guide_color(overlay.ruler_color) setStroke];
        [ruler stroke];

        const float source_width = fmaxf(1.0f, overlay.source_size[0]);
        const float source_height = fmaxf(1.0f, overlay.source_size[1]);
        const int left = (int) lroundf(overlay.ruler[1] * source_width);
        const int top = (int) lroundf(overlay.ruler[2] * source_height);
        draw_guide_badge([NSString stringWithFormat:@"L %d", left], NSMakePoint(x + 8.0, NSMaxY(bounds) - 24.0), bounds);
        draw_guide_badge([NSString stringWithFormat:@"R %d", (int) lroundf(source_width) - left], NSMakePoint(x - 70.0, NSMinY(bounds) + 8.0), bounds);
        draw_guide_badge([NSString stringWithFormat:@"T %d", top], NSMakePoint(NSMinX(bounds) + 8.0, y - 24.0), bounds);
        draw_guide_badge([NSString stringWithFormat:@"B %d", (int) lroundf(source_height) - top], NSMakePoint(NSMaxX(bounds) - 74.0, y + 8.0), bounds);
    }

    if (overlay.picker[0] > 0.5f && overlay.picker[3] > 0.5f) {
        const CGFloat x = NSMinX(bounds) + bounds.size.width * overlay.picker[1];
        const CGFloat y = NSMaxY(bounds) - bounds.size.height * overlay.picker[2];
        const CGFloat radius = fmin(48.0, bounds.size.height * 0.092);
        draw_picker_magnifier(overlay, bounds);
        NSBezierPath *lens = [NSBezierPath bezierPathWithOvalInRect:NSMakeRect(x - radius, y - radius, radius * 2.0, radius * 2.0)];
        lens.lineWidth = 2.0;
        [[NSColor colorWithSRGBRed:0.85 green:0.44 blue:0.29 alpha:1.0] setStroke];
        [lens stroke];
        NSBezierPath *crosshair = [NSBezierPath bezierPath];
        [crosshair moveToPoint:NSMakePoint(x - 7.0, y)];
        [crosshair lineToPoint:NSMakePoint(x + 7.0, y)];
        [crosshair moveToPoint:NSMakePoint(x, y - 7.0)];
        [crosshair lineToPoint:NSMakePoint(x, y + 7.0)];
        crosshair.lineWidth = 1.5;
        [crosshair stroke];
        draw_guide_badge(@"5×", NSMakePoint(x + radius + 6.0, y - 8.0), bounds);
    }
}

@end

static void invalidate_guide_overlay(void) {
    if (!andy_guide_overlay) return;
    void (^redraw)(void) = ^{
        // setNeedsDisplay alone waits for AppKit's next display cycle. That can be held until
        // the next pointer event while a transparent child window is above the Swing host.
        // Flush this small guide view now so turning a guide off never leaves cached lines up.
        if (!andy_guide_overlay) return;
        [andy_guide_overlay setNeedsDisplay:YES];
        [andy_guide_overlay displayIfNeeded];
    };
    if ([NSThread isMainThread]) {
        redraw();
    } else {
        dispatch_async(dispatch_get_main_queue(), redraw);
    }
}

static void
close_popout_window(void) {
    if (!andy_popout_window) {
        return;
    }
    void (^hide)(void) = ^{
        andy_overlay_geometry_scheduled = false;
        andy_overlay_suppressed = false;
        NSWindow *parent = andy_popout_window.parentWindow;
        if (parent) {
            [parent removeChildWindow:andy_popout_window];
        }
        [andy_popout_window orderOut:nil];
        andy_inline_overlay = false;
    };
    if ([NSThread isMainThread]) {
        hide();
    } else {
        dispatch_sync(dispatch_get_main_queue(), hide);
    }
}

static void
ensure_metal_view(NSRect content_rect) {
    if (!andy_popout_content) {
        andy_popout_content = [[NSView alloc] initWithFrame:content_rect];
    }
    if (!andy_popout_view) {
        andy_popout_view = [[MTKView alloc] initWithFrame:content_rect device:renderer.device];
        andy_popout_view.colorPixelFormat = MTLPixelFormatBGRA8Unorm;
        andy_popout_view.framebufferOnly = YES;
        andy_popout_view.paused = YES;
        andy_popout_view.enableSetNeedsDisplay = NO;
        andy_popout_view.autoresizingMask = NSViewWidthSizable | NSViewHeightSizable;
        [andy_popout_content addSubview:andy_popout_view];
    } else {
        andy_popout_view.device = renderer.device;
    }
    if (!andy_guide_overlay) {
        andy_guide_overlay = [[AndyMirrorGuideOverlay alloc] initWithFrame:content_rect];
        andy_guide_overlay.autoresizingMask = NSViewWidthSizable | NSViewHeightSizable;
        [andy_popout_content addSubview:andy_guide_overlay];
    }
}

/**
 * Borderless, mouse-transparent Metal surface that tracks the Live JAWT Canvas on screen.
 * Compose Desktop's SwingPanel does not reliably composite an in-process CAMetalLayer, so this
 * is the supported inline GPU route: pixels sit over the Canvas while input still hits it.
 */
static bool
open_inline_overlay_window(void) {
    if (!renderer.device || !NSApp) {
        return false;
    }
    __block bool opened = false;
    void (^open)(void) = ^{
        const NSRect content_rect = NSMakeRect(0, 0, 390, 844);
        if (andy_popout_window && !andy_inline_overlay) {
            // A prior titled pop-out cannot become borderless reliably; rebuild.
            NSWindow *parent = andy_popout_window.parentWindow;
            if (parent) {
                [parent removeChildWindow:andy_popout_window];
            }
            [andy_popout_window orderOut:nil];
            andy_popout_window = nil;
            andy_popout_view = nil;
            andy_popout_content = nil;
            andy_guide_overlay = nil;
        }
        if (!andy_popout_window) {
            andy_popout_window = [[NSWindow alloc] initWithContentRect:content_rect
                                                            styleMask:NSWindowStyleMaskBorderless
                                                              backing:NSBackingStoreBuffered
                                                                defer:NO];
            if (!andy_popout_window) {
                return;
            }
            ensure_metal_view(content_rect);
            andy_popout_window.contentView = andy_popout_content;
            andy_popout_window.opaque = YES;
            andy_popout_window.backgroundColor = NSColor.blackColor;
            andy_popout_window.hasShadow = NO;
            andy_popout_window.ignoresMouseEvents = YES;
            andy_popout_window.level = NSNormalWindowLevel;
            andy_popout_window.collectionBehavior = NSWindowCollectionBehaviorMoveToActiveSpace |
                NSWindowCollectionBehaviorFullScreenAuxiliary;
        } else {
            ensure_metal_view(andy_popout_view.bounds);
            andy_popout_window.contentView = andy_popout_content;
        }
        andy_inline_overlay = true;
        andy_overlay_suppressed = false;
        renderer.layer = (CAMetalLayer *) andy_popout_view.layer;
        renderer.layer.opaque = YES;
        renderer.layer.framebufferOnly = YES;
        [andy_popout_window orderFront:nil];
        const CGFloat scale = andy_popout_window.backingScaleFactor > 0.0 ? andy_popout_window.backingScaleFactor : 1.0;
        renderer.layer.contentsScale = scale;
        renderer.layer.drawableSize = CGSizeMake(MAX(1.0, andy_popout_view.bounds.size.width * scale),
                                                 MAX(1.0, andy_popout_view.bounds.size.height * scale));
        opened = renderer.layer != nil;
    };
    if ([NSThread isMainThread]) {
        open();
    } else {
        dispatch_sync(dispatch_get_main_queue(), open);
    }
    return opened;
}

static void
apply_inline_overlay_frame(jint awt_x, jint awt_y, jint width, jint height, jdouble scale) {
    if (!andy_popout_window || !andy_inline_overlay || !renderer.layer) {
        return;
    }
    // Dialogs temporarily suppress the overlay. Geometry callbacks must not orderFront it back
    // over Compose/Swing UI while the user is in Capture bug / clip text.
    if (andy_overlay_suppressed) {
        if (andy_popout_window.isVisible) {
            [andy_popout_window orderOut:nil];
        }
        return;
    }
    const CGFloat w = MAX(1, width);
    const CGFloat h = MAX(1, height);
    // AWT locationOnScreen is top-left origin on the primary display; Cocoa frames are bottom-left.
    NSScreen *primary = NSScreen.screens.firstObject;
    const CGFloat screen_height = primary ? NSMaxY(primary.frame) : h;
    const NSRect frame = NSMakeRect(awt_x, screen_height - awt_y - h, w, h);
    const CGFloat backing_scale = scale > 0.0 ? (CGFloat) scale :
        (andy_popout_window.backingScaleFactor > 0.0 ? andy_popout_window.backingScaleFactor : 1.0);
    // The presenter is shared by the main Live Canvas and Compose's separate pop-out Window.
    // Reparent only when that active Canvas changes its top-level window. Keeping the original
    // parent leaves the pop-out Canvas black: the Metal surface remains above the main window.
    NSPoint probe = NSMakePoint(NSMidX(frame), NSMidY(frame));
    const NSInteger window_number = [NSWindow windowNumberAtPoint:probe belowWindowWithWindowNumber:0];
    NSWindow *under = [NSApp windowWithWindowNumber:window_number];
    NSWindow *parent = andy_popout_window.parentWindow;
    if (under && under != andy_popout_window && parent != under) {
        if (parent) {
            [parent removeChildWindow:andy_popout_window];
        }
        [under addChildWindow:andy_popout_window ordered:NSWindowAbove];
    }
    if (!NSEqualRects(andy_popout_window.frame, frame)) {
        // display:NO avoids a synchronous redraw while AWT is still inside componentResized.
        [andy_popout_window setFrame:frame display:NO];
    }
    renderer.layer.contentsScale = backing_scale;
    renderer.layer.drawableSize = CGSizeMake(w * backing_scale, h * backing_scale);
    if (!andy_popout_window.isVisible) {
        [andy_popout_window orderFront:nil];
    }
}

static void
set_inline_overlay_visible(bool visible) {
    andy_overlay_suppressed = !visible;
    if (!andy_popout_window || !andy_inline_overlay) {
        return;
    }
    void (^apply)(void) = ^{
        if (!andy_popout_window || !andy_inline_overlay) {
            return;
        }
        if (visible) {
            if (!andy_popout_window.isVisible) {
                [andy_popout_window orderFront:nil];
            }
        } else if (andy_popout_window.isVisible) {
            [andy_popout_window orderOut:nil];
        }
    };
    if ([NSThread isMainThread]) {
        apply();
    } else {
        dispatch_async(dispatch_get_main_queue(), apply);
    }
}

static void
update_inline_overlay_frame(jint awt_x, jint awt_y, jint width, jint height, jdouble scale) {
    if (!andy_popout_window || !andy_inline_overlay || !renderer.layer) {
        return;
    }
    andy_pending_overlay_x = awt_x;
    andy_pending_overlay_y = awt_y;
    andy_pending_overlay_w = width;
    andy_pending_overlay_h = height;
    andy_pending_overlay_scale = scale;
    if (andy_overlay_geometry_scheduled) {
        return;
    }
    andy_overlay_geometry_scheduled = true;
    dispatch_async(dispatch_get_main_queue(), ^{
        andy_overlay_geometry_scheduled = false;
        apply_inline_overlay_frame(
            andy_pending_overlay_x,
            andy_pending_overlay_y,
            andy_pending_overlay_w,
            andy_pending_overlay_h,
            andy_pending_overlay_scale);
    });
}

static bool
open_popout_window(void) {
    if (!renderer.device || !NSApp) {
        return false;
    }
    __block bool opened = false;
    void (^open)(void) = ^{
        const NSRect content_rect = NSMakeRect(0, 0, 390, 844);
        const NSWindowStyleMask style = NSWindowStyleMaskTitled |
            NSWindowStyleMaskClosable |
            NSWindowStyleMaskMiniaturizable |
            NSWindowStyleMaskResizable;
        if (andy_popout_window && andy_inline_overlay) {
            NSWindow *parent = andy_popout_window.parentWindow;
            if (parent) {
                [parent removeChildWindow:andy_popout_window];
            }
            [andy_popout_window orderOut:nil];
            andy_popout_window = nil;
            andy_popout_view = nil;
            andy_popout_content = nil;
            andy_guide_overlay = nil;
        }
        andy_inline_overlay = false;
        if (!andy_popout_window) {
            andy_popout_window = [[NSWindow alloc] initWithContentRect:content_rect
                                                              styleMask:style
                                                                backing:NSBackingStoreBuffered
                                                                  defer:NO];
            if (!andy_popout_window) {
                return;
            }
            ensure_metal_view(content_rect);
            andy_popout_window.contentView = andy_popout_content;
            andy_popout_window.title = @"Andy GPU Mirror";
            andy_popout_window.contentAspectRatio = NSMakeSize(390, 844);
            andy_popout_window.minSize = NSMakeSize(240, 520);
            andy_popout_window.ignoresMouseEvents = NO;
            andy_popout_window.hasShadow = YES;
        } else {
            ensure_metal_view(andy_popout_view.bounds);
        }
        renderer.layer = (CAMetalLayer *) andy_popout_view.layer;
        [andy_popout_window center];
        [andy_popout_window makeKeyAndOrderFront:nil];
        [NSApp activateIgnoringOtherApps:YES];
        const CGFloat scale = andy_popout_window.backingScaleFactor > 0.0 ? andy_popout_window.backingScaleFactor : 1.0;
        renderer.layer.frame = andy_popout_view.bounds;
        renderer.layer.contentsScale = scale;
        renderer.layer.drawableSize = CGSizeMake(MAX(1.0, andy_popout_view.bounds.size.width * scale),
                                                 MAX(1.0, andy_popout_view.bounds.size.height * scale));
        renderer.layer.autoresizingMask = kCALayerWidthSizable | kCALayerHeightSizable;
        opened = true;
    };
    if ([NSThread isMainThread]) {
        open();
    } else {
        dispatch_sync(dispatch_get_main_queue(), open);
    }
    return opened;
}

static NSString *const shader_source = @
    "#include <metal_stdlib>\n"
    "using namespace metal;\n"
    "struct VertexOut { float4 position [[position]]; float2 uv; };\n"
    "vertex VertexOut vertex_main(uint id [[vertex_id]]) {\n"
    "  float2 positions[4] = {float2(-1,-1), float2(1,-1), float2(-1,1), float2(1,1)};\n"
    "  float2 uvs[4] = {float2(0,1), float2(1,1), float2(0,0), float2(1,0)};\n"
    "  VertexOut out; out.position=float4(positions[id],0,1); out.uv=uvs[id]; return out;\n"
    "}\n"
    "struct Overlay { float4 grid; float4 ruler; float4 highlight; float4 grid_color; float4 ruler_color; float4 picker; };\n"
    "float3 sampled_rgb(texture2d<float> y_tex, texture2d<float> uv_tex, sampler sample, float2 coord) {\n"
    "  float y = 1.1643 * (y_tex.sample(sample, coord).r - 0.0625);\n"
    "  float2 uv = uv_tex.sample(sample, coord).rg - float2(0.5, 0.5);\n"
    "  return float3(y + 1.5958 * uv.y, y - 0.39173 * uv.x - 0.81290 * uv.y, y + 2.017 * uv.x);\n"
    "}\n"
    "fragment half4 fragment_main(VertexOut in [[stage_in]], texture2d<float> y_tex [[texture(0)]], texture2d<float> uv_tex [[texture(1)]], constant Overlay &overlay [[buffer(0)]]) {\n"
    "  constexpr sampler linear_sampler(address::clamp_to_edge, filter::linear);\n"
    "  constexpr sampler nearest_sampler(address::clamp_to_edge, filter::nearest);\n"
    "  float3 rgb = sampled_rgb(y_tex, uv_tex, linear_sampler, in.uv);\n"
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
    "      if (lens_distance >= lens_radius - 0.004) {\n"
    "        rgb = float3(0.85, 0.44, 0.29);\n"
    "      } else {\n"
    "        float2 magnified_uv = overlay.picker.yz + delta / 5.0;\n"
    "        rgb = sampled_rgb(y_tex, uv_tex, nearest_sampler, magnified_uv);\n"
    "        float2 pixel = magnified_uv * float2(y_tex.get_width(), y_tex.get_height());\n"
    "        float2 pixel_edge = abs(fract(pixel) - 0.5);\n"
    "        if (max(pixel_edge.x, pixel_edge.y) > 0.47) rgb = mix(rgb, float3(0.0), 0.22);\n"
    "        if (abs(delta.x * aspect) < 0.002 || abs(delta.y) < 0.002) rgb = mix(rgb, float3(0.85, 0.44, 0.29), 0.9);\n"
    "      }\n"
    "    }\n"
    "  }\n"
    "  return half4(half3(rgb), 1);\n"
    "}\n";

/*
 * macOS JAWT does not expose a portable NSView/HWND-style handle. Instead it exposes a
 * JAWT_SurfaceLayers object that belongs to this JVM process. The accelerated renderer must
 * therefore be loaded in-process: it attaches a CAMetalLayer here and renders to it directly.
 */
@protocol JAWT_SurfaceLayers;

static void
destroy_decoder(void) {
    // Caller must hold decoder_lock.
    if (renderer.decoder) {
        VTDecompressionSessionWaitForAsynchronousFrames(renderer.decoder);
        VTDecompressionSessionInvalidate(renderer.decoder);
        CFRelease(renderer.decoder);
        renderer.decoder = NULL;
    }
    if (renderer.format) {
        CFRelease(renderer.format);
        renderer.format = NULL;
    }
    renderer.decoder_is_hardware = false;
}

static void
record_input_to_present(void) {
    pthread_mutex_lock(&stats_lock);
    if (!renderer.pending_input_ticks) {
        pthread_mutex_unlock(&stats_lock);
        return;
    }
    mach_timebase_info_data_t timebase = {0};
    mach_timebase_info(&timebase);
    const uint64_t elapsed_ticks = mach_continuous_time() - renderer.pending_input_ticks;
    const double elapsed_millis = (double) elapsed_ticks * timebase.numer / timebase.denom / 1000000.0;
    if (renderer.input_to_present_count < 120) {
        renderer.input_to_present_millis[renderer.input_to_present_count++] = elapsed_millis;
    } else {
        memmove(renderer.input_to_present_millis, renderer.input_to_present_millis + 1,
                sizeof(double) * 119);
        renderer.input_to_present_millis[119] = elapsed_millis;
    }
    renderer.pending_input_ticks = 0;
    pthread_mutex_unlock(&stats_lock);
}

static void
record_packet_to_present(uint64_t packet_ticks) {
    if (!packet_ticks) {
        return;
    }
    mach_timebase_info_data_t timebase = {0};
    mach_timebase_info(&timebase);
    const uint64_t elapsed_ticks = mach_continuous_time() - packet_ticks;
    const double elapsed_millis = (double) elapsed_ticks * timebase.numer / timebase.denom / 1000000.0;
    pthread_mutex_lock(&stats_lock);
    if (renderer.packet_to_present_count < 120) {
        renderer.packet_to_present_millis[renderer.packet_to_present_count++] = elapsed_millis;
    } else {
        memmove(renderer.packet_to_present_millis, renderer.packet_to_present_millis + 1,
                sizeof(double) * 119);
        renderer.packet_to_present_millis[119] = elapsed_millis;
    }
    pthread_mutex_unlock(&stats_lock);
}

static void
record_transport_to_present(uint64_t transport_ticks) {
    if (!transport_ticks) {
        return;
    }
    mach_timebase_info_data_t timebase = {0};
    mach_timebase_info(&timebase);
    const uint64_t elapsed_ticks = mach_continuous_time() - transport_ticks;
    const double elapsed_millis = (double) elapsed_ticks * timebase.numer / timebase.denom / 1000000.0;
    pthread_mutex_lock(&stats_lock);
    if (renderer.transport_to_present_count < 120) {
        renderer.transport_to_present_millis[renderer.transport_to_present_count++] = elapsed_millis;
    } else {
        memmove(renderer.transport_to_present_millis, renderer.transport_to_present_millis + 1,
                sizeof(double) * 119);
        renderer.transport_to_present_millis[119] = elapsed_millis;
    }
    pthread_mutex_unlock(&stats_lock);
}

static void
destroy_renderer(void) {
    close_popout_window();
    pthread_mutex_lock(&decoder_lock);
    destroy_decoder();
    free(renderer.sps);
    free(renderer.pps);
    renderer.sps = NULL;
    renderer.pps = NULL;
    renderer.sps_size = 0;
    renderer.pps_size = 0;
    pthread_mutex_unlock(&decoder_lock);
    if (renderer.texture_cache) {
        CFRelease(renderer.texture_cache);
        renderer.texture_cache = NULL;
    }
    renderer.pipeline = nil;
    renderer.queue = nil;
    renderer.device = nil;
    renderer.layer = nil;
    pthread_mutex_lock(&stats_lock);
    renderer.frames_presented = 0;
    renderer.dropped_frames = 0;
    renderer.pending_input_ticks = 0;
    renderer.transport_ingress_ticks = 0;
    renderer.input_to_present_count = 0;
    renderer.packet_to_present_count = 0;
    renderer.transport_to_present_count = 0;
    renderer.latency_probe_enabled = false;
    renderer.probe_has_baseline = false;
    renderer.probe_transitions = 0;
    pthread_mutex_unlock(&stats_lock);
    pthread_mutex_lock(&latest_pixels_lock);
    if (renderer.latest_pixels) {
        CVPixelBufferRelease(renderer.latest_pixels);
        renderer.latest_pixels = NULL;
    }
    pthread_mutex_unlock(&latest_pixels_lock);
}

static bool
configure_metal(void) {
    renderer.device = MTLCreateSystemDefaultDevice();
    if (!renderer.device) {
        return false;
    }
    renderer.queue = [renderer.device newCommandQueue];
    NSError *error = nil;
    id<MTLLibrary> library = [renderer.device newLibraryWithSource:shader_source options:nil error:&error];
    if (!library) {
        return false;
    }
    MTLRenderPipelineDescriptor *descriptor = [[MTLRenderPipelineDescriptor alloc] init];
    descriptor.vertexFunction = [library newFunctionWithName:@"vertex_main"];
    descriptor.fragmentFunction = [library newFunctionWithName:@"fragment_main"];
    descriptor.colorAttachments[0].pixelFormat = MTLPixelFormatBGRA8Unorm;
    renderer.pipeline = [renderer.device newRenderPipelineStateWithDescriptor:descriptor error:&error];
    if (!renderer.pipeline) {
        return false;
    }
    return CVMetalTextureCacheCreate(kCFAllocatorDefault, NULL, renderer.device, NULL, &renderer.texture_cache) == kCVReturnSuccess;
}

static bool
latency_probe_changed(CVPixelBufferRef pixels) {
    pthread_mutex_lock(&stats_lock);
    const bool probe_enabled = renderer.latency_probe_enabled;
    const bool input_pending = renderer.pending_input_ticks != 0;
    const float probe_left = renderer.probe_left;
    const float probe_top = renderer.probe_top;
    const float probe_width = renderer.probe_width;
    const float probe_height = renderer.probe_height;
    pthread_mutex_unlock(&stats_lock);
    if (!probe_enabled) {
        return input_pending;
    }
    const size_t full_width = CVPixelBufferGetWidthOfPlane(pixels, 0);
    const size_t full_height = CVPixelBufferGetHeightOfPlane(pixels, 0);
    if (!full_width || !full_height ||
        CVPixelBufferLockBaseAddress(pixels, kCVPixelBufferLock_ReadOnly) != kCVReturnSuccess) {
        return false;
    }
    const uint8_t *base = CVPixelBufferGetBaseAddressOfPlane(pixels, 0);
    const size_t stride = CVPixelBufferGetBytesPerRowOfPlane(pixels, 0);
    const size_t left = (size_t) fmax(0, fmin(full_width - 1, probe_left * full_width));
    const size_t top = (size_t) fmax(0, fmin(full_height - 1, probe_top * full_height));
    const size_t right = (size_t) fmax(left + 1, fmin(full_width, (probe_left + probe_width) * full_width));
    const size_t bottom = (size_t) fmax(top + 1, fmin(full_height, (probe_top + probe_height) * full_height));
    uint64_t total = 0;
    size_t samples = 0;
    for (size_t y = top; y < bottom; y += 4) {
        for (size_t x = left; x < right; x += 4) {
            total += base[y * stride + x];
            samples++;
        }
    }
    CVPixelBufferUnlockBaseAddress(pixels, kCVPixelBufferLock_ReadOnly);
    if (!samples) {
        return false;
    }
    const double luminance = (double) total / samples;
    pthread_mutex_lock(&stats_lock);
    const bool changed = renderer.probe_has_baseline && fabs(luminance - renderer.probe_luminance) >= 40.0;
    renderer.probe_luminance = luminance;
    renderer.probe_has_baseline = true;
    if (changed) {
        renderer.probe_transitions++;
    }
    const bool pending = renderer.pending_input_ticks != 0;
    pthread_mutex_unlock(&stats_lock);
    return changed && pending;
}

static void
remember_latest_pixels(CVPixelBufferRef pixels) {
    CVPixelBufferRetain(pixels);
    pthread_mutex_lock(&latest_pixels_lock);
    CVPixelBufferRef old = renderer.latest_pixels;
    renderer.latest_pixels = pixels;
    pthread_mutex_unlock(&latest_pixels_lock);
    if (old) {
        CVPixelBufferRelease(old);
    }
}

static void
render_pixel_buffer(CVPixelBufferRef pixels, bool input_changed_probe, uint64_t packet_ticks,
                    uint64_t transport_ticks, bool record_presentation_metrics) {
    @autoreleasepool {
        if (!renderer.layer || !renderer.texture_cache || !renderer.pipeline) {
            return;
        }
        const size_t width = CVPixelBufferGetWidthOfPlane(pixels, 0);
        const size_t height = CVPixelBufferGetHeightOfPlane(pixels, 0);
        if (!width || !height) {
            return;
        }
        // JAWT owns the layer's bounds as it tracks the Canvas rectangle. Do not replace that
        // presentation geometry with the decoded H.264 dimensions: a 720p source inside a
        // smaller Compose/Swing host otherwise asks Core Animation for drawables unrelated to
        // the overlaid component. The render pass naturally scales its fullscreen quad to the
        // drawable supplied by CAMetalLayer.
        id<CAMetalDrawable> drawable = [renderer.layer nextDrawable];
        if (!drawable) {
            pthread_mutex_lock(&stats_lock);
            renderer.dropped_frames++;
            pthread_mutex_unlock(&stats_lock);
            return;
        }
        CVMetalTextureRef y_ref = NULL;
        CVMetalTextureRef uv_ref = NULL;
        CVReturn y_result = CVMetalTextureCacheCreateTextureFromImage(
            kCFAllocatorDefault, renderer.texture_cache, pixels, NULL,
            MTLPixelFormatR8Unorm, width, height, 0, &y_ref);
        CVReturn uv_result = CVMetalTextureCacheCreateTextureFromImage(
            kCFAllocatorDefault, renderer.texture_cache, pixels, NULL,
            MTLPixelFormatRG8Unorm, width / 2, height / 2, 1, &uv_ref);
        if (y_result != kCVReturnSuccess || uv_result != kCVReturnSuccess) {
            if (y_ref) CFRelease(y_ref);
            if (uv_ref) CFRelease(uv_ref);
            pthread_mutex_lock(&stats_lock);
            renderer.dropped_frames++;
            pthread_mutex_unlock(&stats_lock);
            return;
        }
        id<MTLCommandBuffer> command = [renderer.queue commandBuffer];
        id<MTLBuffer> overlay = [renderer.device newBufferWithBytes:&renderer.overlay
                                                              length:sizeof(AndyMirrorOverlay)
                                                             options:MTLResourceStorageModeShared];
        MTLRenderPassDescriptor *pass = [MTLRenderPassDescriptor renderPassDescriptor];
        pass.colorAttachments[0].texture = drawable.texture;
        pass.colorAttachments[0].loadAction = MTLLoadActionClear;
        pass.colorAttachments[0].storeAction = MTLStoreActionStore;
        pass.colorAttachments[0].clearColor = MTLClearColorMake(0, 0, 0, 1);
        id<MTLRenderCommandEncoder> encoder = [command renderCommandEncoderWithDescriptor:pass];
        [encoder setRenderPipelineState:renderer.pipeline];
        [encoder setFragmentBuffer:overlay offset:0 atIndex:0];
        [encoder setFragmentTexture:CVMetalTextureGetTexture(y_ref) atIndex:0];
        [encoder setFragmentTexture:CVMetalTextureGetTexture(uv_ref) atIndex:1];
        [encoder drawPrimitives:MTLPrimitiveTypeTriangleStrip vertexStart:0 vertexCount:4];
        [encoder endEncoding];
        [command presentDrawable:drawable];
        if (record_presentation_metrics) {
            [command addCompletedHandler:^(id<MTLCommandBuffer> buffer) {
                (void) buffer;
                pthread_mutex_lock(&stats_lock);
                renderer.frames_presented++;
                pthread_mutex_unlock(&stats_lock);
                record_packet_to_present(packet_ticks);
                record_transport_to_present(transport_ticks);
                if (input_changed_probe) {
                    record_input_to_present();
                }
            }];
        }
        [command commit];
        CFRelease(y_ref);
        CFRelease(uv_ref);
    }
}

static void
decoded_frame(void *decompression_output_refcon, void *source_frame_refcon,
              OSStatus status, VTDecodeInfoFlags info_flags,
              CVImageBufferRef image_buffer, CMTime presentation_time_stamp,
              CMTime presentation_duration) {
    (void) decompression_output_refcon;
    (void) info_flags;
    (void) presentation_time_stamp;
    (void) presentation_duration;
    if (status != noErr || !image_buffer) {
        pthread_mutex_lock(&stats_lock);
        renderer.dropped_frames++;
        pthread_mutex_unlock(&stats_lock);
        return;
    }
    const uint64_t packet_ticks = (uint64_t) (uintptr_t) source_frame_refcon;
    CVPixelBufferRef pixels = (CVPixelBufferRef) image_buffer;
    remember_latest_pixels(pixels);
    pthread_mutex_lock(&stats_lock);
    const uint64_t transport_ticks = renderer.transport_ingress_ticks;
    pthread_mutex_unlock(&stats_lock);
    render_pixel_buffer(pixels, latency_probe_changed(pixels), packet_ticks, transport_ticks, true);
}

/**
 * Overlay controls must repaint a paused Android screen too. Re-submit the latest decoded
 * frame with the new shader constants instead of waiting for the next H.264 access unit.
 */
static void
present_latest_frame_for_overlay(void) {
    pthread_mutex_lock(&latest_pixels_lock);
    CVPixelBufferRef pixels = renderer.latest_pixels;
    if (pixels) CVPixelBufferRetain(pixels);
    pthread_mutex_unlock(&latest_pixels_lock);
    if (!pixels) return;
    render_pixel_buffer(pixels, false, 0, 0, false);
    CVPixelBufferRelease(pixels);
}

static bool
configure_decoder(void) {
    // Caller must hold decoder_lock.
    if (renderer.decoder || !renderer.sps || !renderer.pps) {
        return renderer.decoder != NULL;
    }
    const uint8_t *parameter_sets[] = {renderer.sps, renderer.pps};
    const size_t parameter_set_sizes[] = {renderer.sps_size, renderer.pps_size};
    OSStatus format_status = CMVideoFormatDescriptionCreateFromH264ParameterSets(
        kCFAllocatorDefault, 2, parameter_sets, parameter_set_sizes, 4, &renderer.format);
    if (format_status != noErr) {
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
        .decompressionOutputCallback = decoded_frame,
        .decompressionOutputRefCon = NULL,
    };
    OSStatus decoder_status = VTDecompressionSessionCreate(
        kCFAllocatorDefault, renderer.format, (__bridge CFDictionaryRef) specification,
        (__bridge CFDictionaryRef) attributes, &callback, &renderer.decoder);
    if (decoder_status != noErr) {
        destroy_decoder();
        return false;
    }
    CFTypeRef hardware = NULL;
    OSStatus hardware_status = VTSessionCopyProperty(
        renderer.decoder,
        kVTDecompressionPropertyKey_UsingHardwareAcceleratedVideoDecoder,
        kCFAllocatorDefault,
        &hardware);
    renderer.decoder_is_hardware = hardware_status == noErr && hardware
        && CFGetTypeID(hardware) == CFBooleanGetTypeID()
        && CFBooleanGetValue((CFBooleanRef) hardware);
    if (hardware) {
        CFRelease(hardware);
    }
    if (!renderer.decoder_is_hardware) {
        destroy_decoder();
        return false;
    }
    // VideoToolbox otherwise optimizes for throughput and can queue old frames behind a host
    // input event. The mirror is an interactive preview, so favor the newest frame.
    VTSessionSetProperty(renderer.decoder, kVTDecompressionPropertyKey_RealTime, kCFBooleanTrue);
    return true;
}

static uint8_t *
copy_bytes(const uint8_t *source, size_t length) {
    uint8_t *result = malloc(length);
    if (result) {
        memcpy(result, source, length);
    }
    return result;
}

static bool
replace_parameter_set(uint8_t **target, size_t *target_size, const uint8_t *source, size_t length) {
    uint8_t *copy = copy_bytes(source, length);
    if (!copy) {
        return false;
    }
    free(*target);
    *target = copy;
    *target_size = length;
    return true;
}

static size_t
start_code_length(const uint8_t *bytes, size_t offset, size_t length) {
    if (offset + 3 <= length && bytes[offset] == 0 && bytes[offset + 1] == 0 && bytes[offset + 2] == 1) {
        return 3;
    }
    if (offset + 4 <= length && bytes[offset] == 0 && bytes[offset + 1] == 0 && bytes[offset + 2] == 0 && bytes[offset + 3] == 1) {
        return 4;
    }
    return 0;
}

static size_t
find_start_code(const uint8_t *bytes, size_t offset, size_t length) {
    for (size_t i = offset; i + 3 <= length; ++i) {
        if (start_code_length(bytes, i, length)) {
            return i;
        }
    }
    return length;
}

static bool
consume_h264_access_unit(const uint8_t *bytes, size_t length) {
    pthread_mutex_lock(&decoder_lock);
    if (!renderer.decoder && !renderer.layer) {
        pthread_mutex_unlock(&decoder_lock);
        return false;
    }
    size_t first = find_start_code(bytes, 0, length);
    if (first == length) {
        pthread_mutex_unlock(&decoder_lock);
        return false;
    }
    uint8_t *avcc = malloc(length + (length / 3) + 1);
    if (!avcc) {
        pthread_mutex_unlock(&decoder_lock);
        return false;
    }
    size_t avcc_length = 0;
    bool keyframe = false;
    for (size_t marker = first; marker < length;) {
        size_t marker_length = start_code_length(bytes, marker, length);
        size_t nal_start = marker + marker_length;
        size_t next_marker = find_start_code(bytes, nal_start, length);
        size_t nal_length = next_marker - nal_start;
        if (nal_length) {
            uint8_t type = bytes[nal_start] & 0x1f;
            if (type == 7) {
                if (!replace_parameter_set(&renderer.sps, &renderer.sps_size, bytes + nal_start, nal_length)) {
                    free(avcc);
                    pthread_mutex_unlock(&decoder_lock);
                    return false;
                }
            } else if (type == 8) {
                if (!replace_parameter_set(&renderer.pps, &renderer.pps_size, bytes + nal_start, nal_length)) {
                    free(avcc);
                    pthread_mutex_unlock(&decoder_lock);
                    return false;
                }
            } else if (type == 1 || type == 5) {
                avcc[avcc_length++] = (uint8_t) (nal_length >> 24);
                avcc[avcc_length++] = (uint8_t) (nal_length >> 16);
                avcc[avcc_length++] = (uint8_t) (nal_length >> 8);
                avcc[avcc_length++] = (uint8_t) nal_length;
                memcpy(avcc + avcc_length, bytes + nal_start, nal_length);
                avcc_length += nal_length;
                keyframe = keyframe || type == 5;
            }
        }
        marker = next_marker;
    }
    if (!renderer.decoder && renderer.sps && renderer.pps && !configure_decoder()) {
        free(avcc);
        pthread_mutex_unlock(&decoder_lock);
        return false;
    }
    if (!avcc_length) {
        free(avcc);
        pthread_mutex_unlock(&decoder_lock);
        // Codec parameter sets commonly arrive before the first access unit.
        return true;
    }
    if (!renderer.decoder) {
        free(avcc);
        pthread_mutex_unlock(&decoder_lock);
        return false;
    }
    CMBlockBufferRef block = NULL;
    OSStatus block_status = CMBlockBufferCreateWithMemoryBlock(
        kCFAllocatorDefault, NULL, avcc_length, kCFAllocatorDefault, NULL, 0,
        avcc_length, 0, &block);
    if (block_status != kCMBlockBufferNoErr) {
        free(avcc);
        pthread_mutex_unlock(&decoder_lock);
        return false;
    }
    CMBlockBufferReplaceDataBytes(avcc, block, 0, avcc_length);
    free(avcc);
    CMSampleTimingInfo timing = {kCMTimeInvalid, kCMTimeInvalid, kCMTimeInvalid};
    size_t sample_size = avcc_length;
    CMSampleBufferRef sample = NULL;
    OSStatus sample_status = CMSampleBufferCreateReady(
        kCFAllocatorDefault, block, renderer.format, 1, 1, &timing, 1, &sample_size, &sample);
    CFRelease(block);
    if (sample_status != noErr) {
        pthread_mutex_unlock(&decoder_lock);
        return false;
    }
    VTDecodeFrameFlags flags = kVTDecodeFrame_EnableAsynchronousDecompression |
        kVTDecodeFrame_1xRealTimePlayback;
    const uint64_t packet_ticks = mach_continuous_time();
    OSStatus decode_status = VTDecompressionSessionDecodeFrame(
        renderer.decoder, sample, flags, (void *) (uintptr_t) packet_ticks, NULL);
    CFRelease(sample);
    pthread_mutex_unlock(&decoder_lock);
    return decode_status == noErr;
}

static id
surface_layers_for(JNIEnv *env, jobject component, JAWT *awt,
                   JAWT_DrawingSurface **surface_out,
                   JAWT_DrawingSurfaceInfo **info_out) {
    awt->version = JAWT_VERSION_1_7;
    if (!JAWT_GetAWT(env, awt)) {
        return nil;
    }

    JAWT_DrawingSurface *surface = awt->GetDrawingSurface(env, component);
    if (!surface) {
        return nil;
    }
    jint lock = surface->Lock(surface);
    if (lock & JAWT_LOCK_ERROR) {
        awt->FreeDrawingSurface(surface);
        return nil;
    }
    JAWT_DrawingSurfaceInfo *info = surface->GetDrawingSurfaceInfo(surface);
    if (!info || !info->platformInfo) {
        if (info) {
            surface->FreeDrawingSurfaceInfo(info);
        }
        surface->Unlock(surface);
        awt->FreeDrawingSurface(surface);
        return nil;
    }

    *surface_out = surface;
    *info_out = info;
    return (__bridge id) info->platformInfo;
}

/*
 * AWTSurfaceLayers.setLayer only adopts the CAMetalLayer into windowLayer — it does not place
 * it. Placement goes through setBounds with top-left window-content coordinates, which OpenJDK
 * then flips into Core Animation's bottom-left space. Forcing frame=(0,0,w,h) made Metal draw at
 * the window origin while the Compose Live Canvas stayed black (command buffers still completed).
 * Drawable size must follow that placed frame, never the decoded H.264 dimensions.
 */
static void
position_metal_layer(id surface_layers, jint x, jint y, jint width, jint height) {
    if (!renderer.layer || !surface_layers) {
        return;
    }
    CALayer *window_layer = [surface_layers windowLayer];
    if (!window_layer) {
        return;
    }
    const CGFloat w = MAX(1, width);
    const CGFloat h = MAX(1, height);
    CGRect rect = CGRectMake(x, y, w, h);
    // Match AWTSurfaceLayers.setBounds: translate into the window layer's coordinate system.
    rect.origin.y = window_layer.bounds.size.height - rect.origin.y - rect.size.height;
    [CATransaction begin];
    [CATransaction setDisableActions:YES];
    renderer.layer.frame = rect;
    [CATransaction commit];
    const CGFloat scale = window_layer.contentsScale > 0.0 ? window_layer.contentsScale : 1.0;
    renderer.layer.contentsScale = scale;
    renderer.layer.drawableSize = CGSizeMake(w * scale, h * scale);
}

static void
release_surface(JAWT *awt, JAWT_DrawingSurface *surface,
                JAWT_DrawingSurfaceInfo *info) {
    surface->FreeDrawingSurfaceInfo(info);
    surface->Unlock(surface);
    awt->FreeDrawingSurface(surface);
}

JNIEXPORT jboolean JNICALL
Java_app_andy_desktop_service_mirror_NativeMirrorJni_nativeInstallMetalLayer(
        JNIEnv *env, jclass clazz, jobject component) {
    (void) clazz;
    @autoreleasepool {
        JAWT awt = {0};
        JAWT_DrawingSurface *surface = NULL;
        JAWT_DrawingSurfaceInfo *info = NULL;
        id surface_layers = surface_layers_for(env, component, &awt, &surface, &info);
        if (!surface_layers) {
            return JNI_FALSE;
        }

        destroy_renderer();
        renderer.layer = [CAMetalLayer layer];
        renderer.layer.opaque = YES;
        if (!configure_metal()) {
            destroy_renderer();
            release_surface(&awt, surface, info);
            return JNI_FALSE;
        }
        renderer.layer.device = renderer.device;
        renderer.layer.pixelFormat = MTLPixelFormatBGRA8Unorm;
        renderer.layer.backgroundColor = NSColor.blackColor.CGColor;
        renderer.layer.framebufferOnly = YES;
        renderer.layer.maximumDrawableCount = 2;
        renderer.layer.presentsWithTransaction = NO;
        [surface_layers setValue:renderer.layer forKey:@"layer"];
        // Bounds are applied from Kotlin via nativeUpdateMetalLayerBounds using window-content
        // coordinates. JAWT's info->bounds are parent-local and must not drive layer.frame.
        release_surface(&awt, surface, info);
        return JNI_TRUE;
    }
}

/*
 * Supported inline GPU path: borderless mouse-transparent AppKit overlay tracking the Live
 * Canvas. Compose Desktop does not reliably composite an in-process CAMetalLayer, so this is
 * the product presentation route that keeps pixels in the Live pane.
 */
JNIEXPORT jboolean JNICALL
Java_app_andy_desktop_service_mirror_NativeMirrorJni_nativeOpenMetalInlineOverlay(
        JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    @autoreleasepool {
        destroy_renderer();
        if (!configure_metal()) {
            destroy_renderer();
            return JNI_FALSE;
        }
        if (!open_inline_overlay_window()) {
            destroy_renderer();
            return JNI_FALSE;
        }
        renderer.layer.opaque = YES;
        renderer.layer.backgroundColor = NSColor.blackColor.CGColor;
        renderer.layer.maximumDrawableCount = 2;
        renderer.layer.presentsWithTransaction = NO;
        return JNI_TRUE;
    }
}

JNIEXPORT void JNICALL
Java_app_andy_desktop_service_mirror_NativeMirrorJni_nativeUpdateMetalInlineOverlay(
        JNIEnv *env, jclass clazz, jint x, jint y, jint width, jint height, jdouble scale) {
    (void) env;
    (void) clazz;
    @autoreleasepool {
        update_inline_overlay_frame(x, y, width, height, scale);
    }
}

JNIEXPORT void JNICALL
Java_app_andy_desktop_service_mirror_NativeMirrorJni_nativeSetMetalInlineOverlayVisible(
        JNIEnv *env, jclass clazz, jboolean visible) {
    (void) env;
    (void) clazz;
    @autoreleasepool {
        set_inline_overlay_visible(visible == JNI_TRUE);
    }
}

/*
 * Copies the latest VideoToolbox CVPixelBuffer as packed ARGB_8888 for bug-capture sampling.
 * out_size must be a length-2 int array that receives [width, height] on success.
 */
JNIEXPORT jintArray JNICALL
Java_app_andy_desktop_service_mirror_NativeMirrorJni_nativeCopyLatestFrameArgb(
        JNIEnv *env, jclass clazz, jintArray out_size) {
    (void) clazz;
    if (!out_size || (*env)->GetArrayLength(env, out_size) < 2) {
        return NULL;
    }
    // Snapshot Y/UV under a short CVPixelBuffer lock so Metal/VideoToolbox are not blocked for
    // the full ARGB convert. Holding LockBaseAddress across a 1080p convert raced with decode.
    pthread_mutex_lock(&latest_pixels_lock);
    CVPixelBufferRef pixels = renderer.latest_pixels;
    if (pixels) {
        CVPixelBufferRetain(pixels);
    }
    pthread_mutex_unlock(&latest_pixels_lock);
    if (!pixels) {
        return NULL;
    }
    const size_t width = CVPixelBufferGetWidthOfPlane(pixels, 0);
    const size_t height = CVPixelBufferGetHeightOfPlane(pixels, 0);
    if (!width || !height || width > 8192 || height > 8192 ||
        CVPixelBufferLockBaseAddress(pixels, kCVPixelBufferLock_ReadOnly) != kCVReturnSuccess) {
        CVPixelBufferRelease(pixels);
        return NULL;
    }
    const size_t y_stride = CVPixelBufferGetBytesPerRowOfPlane(pixels, 0);
    const size_t uv_stride = CVPixelBufferGetBytesPerRowOfPlane(pixels, 1);
    const uint8_t *y_base = CVPixelBufferGetBaseAddressOfPlane(pixels, 0);
    const uint8_t *uv_base = CVPixelBufferGetBaseAddressOfPlane(pixels, 1);
    uint8_t *y_copy = malloc(height * width);
    uint8_t *uv_copy = malloc((height / 2) * width);
    if (!y_copy || !uv_copy || !y_base || !uv_base) {
        free(y_copy);
        free(uv_copy);
        CVPixelBufferUnlockBaseAddress(pixels, kCVPixelBufferLock_ReadOnly);
        CVPixelBufferRelease(pixels);
        return NULL;
    }
    for (size_t y = 0; y < height; ++y) {
        memcpy(y_copy + y * width, y_base + y * y_stride, width);
    }
    for (size_t y = 0; y < height / 2; ++y) {
        memcpy(uv_copy + y * width, uv_base + y * uv_stride, width);
    }
    CVPixelBufferUnlockBaseAddress(pixels, kCVPixelBufferLock_ReadOnly);
    CVPixelBufferRelease(pixels);

    const size_t count = width * height;
    jintArray result = (*env)->NewIntArray(env, (jsize) count);
    if (!result) {
        free(y_copy);
        free(uv_copy);
        return NULL;
    }
    jint *dest = (*env)->GetIntArrayElements(env, result, NULL);
    if (!dest) {
        free(y_copy);
        free(uv_copy);
        return NULL;
    }
    for (size_t y = 0; y < height; ++y) {
        const uint8_t *y_row = y_copy + y * width;
        const uint8_t *uv_row = uv_copy + (y / 2) * width;
        jint *out_row = dest + y * width;
        for (size_t x = 0; x < width; ++x) {
            const int yy = y_row[x] - 16;
            const size_t uv_x = (x / 2) * 2;
            const int uu = uv_row[uv_x] - 128;
            const int vv = uv_row[uv_x + 1] - 128;
            int red = (298 * yy + 409 * vv + 128) >> 8;
            int green = (298 * yy - 100 * uu - 208 * vv + 128) >> 8;
            int blue = (298 * yy + 516 * uu + 128) >> 8;
            if (red < 0) red = 0; else if (red > 255) red = 255;
            if (green < 0) green = 0; else if (green > 255) green = 255;
            if (blue < 0) blue = 0; else if (blue > 255) blue = 255;
            out_row[x] = (jint) (0xff000000u | ((unsigned) red << 16) | ((unsigned) green << 8) | (unsigned) blue);
        }
    }
    free(y_copy);
    free(uv_copy);
    (*env)->ReleaseIntArrayElements(env, result, dest, 0);
    jint size_values[2] = { (jint) width, (jint) height };
    (*env)->SetIntArrayRegion(env, out_size, 0, 2, size_values);
    return result;
}

JNIEXPORT jboolean JNICALL
Java_app_andy_desktop_service_mirror_NativeMirrorJni_nativeIsMetalInlineOverlayOpen(
        JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return andy_inline_overlay && andy_popout_window && andy_popout_window.isVisible ? JNI_TRUE : JNI_FALSE;
}

/*
 * Escape hatch when no realized JAWT host exists (or for manual debugging). Prefer the inline
 * overlay path for Live; a separate titled window is not the product presentation route.
 */
JNIEXPORT jboolean JNICALL
Java_app_andy_desktop_service_mirror_NativeMirrorJni_nativeOpenMetalPopout(
        JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    @autoreleasepool {
        destroy_renderer();
        if (!configure_metal()) {
            destroy_renderer();
            return JNI_FALSE;
        }
        if (!open_popout_window()) {
            destroy_renderer();
            return JNI_FALSE;
        }
        renderer.layer.opaque = YES;
        renderer.layer.backgroundColor = NSColor.blackColor.CGColor;
        renderer.layer.maximumDrawableCount = 2;
        renderer.layer.presentsWithTransaction = NO;
        return JNI_TRUE;
    }
}

JNIEXPORT void JNICALL
Java_app_andy_desktop_service_mirror_NativeMirrorJni_nativeUpdateMetalLayerBounds(
        JNIEnv *env, jclass clazz, jobject component, jint x, jint y, jint width, jint height) {
    (void) clazz;
    @autoreleasepool {
        if (!renderer.layer) {
            return;
        }
        JAWT awt = {0};
        JAWT_DrawingSurface *surface = NULL;
        JAWT_DrawingSurfaceInfo *info = NULL;
        id surface_layers = surface_layers_for(env, component, &awt, &surface, &info);
        if (!surface_layers) {
            return;
        }
        position_metal_layer(surface_layers, x, y, width, height);
        release_surface(&awt, surface, info);
    }
}

JNIEXPORT jboolean JNICALL
Java_app_andy_desktop_service_mirror_NativeMirrorJni_nativeIsHardwareReady(
        JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return renderer.device && renderer.queue && renderer.decoder && renderer.decoder_is_hardware
        ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_app_andy_desktop_service_mirror_NativeMirrorJni_nativeRemoveMetalLayer(
        JNIEnv *env, jclass clazz, jobject component) {
    (void) clazz;
    @autoreleasepool {
        JAWT awt = {0};
        JAWT_DrawingSurface *surface = NULL;
        JAWT_DrawingSurfaceInfo *info = NULL;
        id surface_layers = surface_layers_for(env, component, &awt, &surface, &info);
        if (!surface_layers) {
            return;
        }

        [surface_layers setValue:nil forKey:@"layer"];
        destroy_renderer();
        release_surface(&awt, surface, info);
    }
}

/*
 * A Compose SwingPanel may already have called Canvas.removeNotify() by the time
 * its LaunchedEffect cleanup reaches DesktopMirrorEngine.disconnect().  Asking
 * JAWT for a drawing surface after that point can dereference a stale AWT peer
 * inside libawt_lwawt, which is a JVM process crash rather than a Java exception.
 * The heavyweight host is gone in that case, so releasing our decoder/Metal state
 * is both sufficient and the only safe teardown route.
 */
JNIEXPORT void JNICALL
Java_app_andy_desktop_service_mirror_NativeMirrorJni_nativeDestroyRenderer(
        JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    @autoreleasepool {
        destroy_renderer();
    }
}

JNIEXPORT jboolean JNICALL
Java_app_andy_desktop_service_mirror_NativeMirrorJni_nativeConsumeH264(
        JNIEnv *env, jclass clazz, jbyteArray packet) {
    (void) clazz;
    jsize length = (*env)->GetArrayLength(env, packet);
    if (length <= 0) {
        return JNI_FALSE;
    }
    jbyte *bytes = (*env)->GetByteArrayElements(env, packet, NULL);
    if (!bytes) {
        return JNI_FALSE;
    }
    bool consumed = consume_h264_access_unit((const uint8_t *) bytes, (size_t) length);
    (*env)->ReleaseByteArrayElements(env, packet, bytes, JNI_ABORT);
    return consumed ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_app_andy_desktop_service_mirror_NativeMirrorJni_nativeFramesPresented(
        JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    pthread_mutex_lock(&stats_lock);
    const jlong frames = (jlong) renderer.frames_presented;
    pthread_mutex_unlock(&stats_lock);
    return frames;
}

JNIEXPORT void JNICALL
Java_app_andy_desktop_service_mirror_NativeMirrorJni_nativeRecordInput(
        JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    pthread_mutex_lock(&stats_lock);
    renderer.pending_input_ticks = mach_continuous_time();
    pthread_mutex_unlock(&stats_lock);
}

JNIEXPORT void JNICALL
Java_app_andy_desktop_service_mirror_NativeMirrorJni_nativeRecordTransportIngress(
        JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    pthread_mutex_lock(&stats_lock);
    renderer.transport_ingress_ticks = mach_continuous_time();
    pthread_mutex_unlock(&stats_lock);
}

JNIEXPORT void JNICALL
Java_app_andy_desktop_service_mirror_NativeMirrorJni_nativeConfigureLatencyProbe(
        JNIEnv *env, jclass clazz, jfloat left, jfloat top, jfloat width, jfloat height) {
    (void) env;
    (void) clazz;
    pthread_mutex_lock(&stats_lock);
    renderer.latency_probe_enabled = width > 0 && height > 0;
    renderer.probe_left = fmaxf(0.0f, fminf(1.0f, left));
    renderer.probe_top = fmaxf(0.0f, fminf(1.0f, top));
    renderer.probe_width = fmaxf(0.0f, fminf(1.0f - renderer.probe_left, width));
    renderer.probe_height = fmaxf(0.0f, fminf(1.0f - renderer.probe_top, height));
    renderer.probe_has_baseline = false;
    renderer.probe_transitions = 0;
    pthread_mutex_unlock(&stats_lock);
}

JNIEXPORT void JNICALL
Java_app_andy_desktop_service_mirror_NativeMirrorJni_nativeUpdateOverlay(
        JNIEnv *env, jclass clazz,
        jboolean grid_enabled, jfloat grid_step_x, jfloat grid_step_y,
        jfloat grid_r, jfloat grid_g, jfloat grid_b, jfloat grid_a,
        jboolean ruler_enabled, jfloat ruler_x, jfloat ruler_y,
        jfloat ruler_r, jfloat ruler_g, jfloat ruler_b, jfloat ruler_a,
        jfloat source_width, jfloat source_height,
        jboolean picker_enabled,
        jfloat highlight_left, jfloat highlight_top, jfloat highlight_right, jfloat highlight_bottom) {
    (void) env;
    (void) clazz;
    const AndyMirrorOverlay previous = renderer.overlay;
    renderer.overlay.grid[0] = grid_enabled ? 1.0f : 0.0f;
    renderer.overlay.grid[1] = fmaxf(0.0f, fminf(1.0f, grid_step_x));
    renderer.overlay.grid[2] = fmaxf(0.0f, fminf(1.0f, grid_step_y));
    renderer.overlay.grid_color[0] = fmaxf(0.0f, fminf(1.0f, grid_r));
    renderer.overlay.grid_color[1] = fmaxf(0.0f, fminf(1.0f, grid_g));
    renderer.overlay.grid_color[2] = fmaxf(0.0f, fminf(1.0f, grid_b));
    renderer.overlay.grid_color[3] = fmaxf(0.0f, fminf(1.0f, grid_a));
    renderer.overlay.ruler[0] = ruler_enabled ? 1.0f : 0.0f;
    renderer.overlay.ruler[1] = fmaxf(0.0f, fminf(1.0f, ruler_x));
    renderer.overlay.ruler[2] = fmaxf(0.0f, fminf(1.0f, ruler_y));
    renderer.overlay.ruler_color[0] = fmaxf(0.0f, fminf(1.0f, ruler_r));
    renderer.overlay.ruler_color[1] = fmaxf(0.0f, fminf(1.0f, ruler_g));
    renderer.overlay.ruler_color[2] = fmaxf(0.0f, fminf(1.0f, ruler_b));
    renderer.overlay.ruler_color[3] = fmaxf(0.0f, fminf(1.0f, ruler_a));
    renderer.overlay.source_size[0] = fmaxf(1.0f, source_width);
    renderer.overlay.source_size[1] = fmaxf(1.0f, source_height);
    renderer.overlay.picker[0] = picker_enabled ? 1.0f : 0.0f;
    if (!picker_enabled) {
        renderer.overlay.picker[3] = 0.0f;
    }
    renderer.overlay.highlight[0] = fmaxf(0.0f, fminf(1.0f, highlight_left));
    renderer.overlay.highlight[1] = fmaxf(0.0f, fminf(1.0f, highlight_top));
    renderer.overlay.highlight[2] = fmaxf(0.0f, fminf(1.0f, highlight_right));
    renderer.overlay.highlight[3] = fmaxf(0.0f, fminf(1.0f, highlight_bottom));
    if (memcmp(&previous, &renderer.overlay, sizeof(AndyMirrorOverlay)) == 0) return;
    invalidate_guide_overlay();
    present_latest_frame_for_overlay();
}

JNIEXPORT void JNICALL
Java_app_andy_desktop_service_mirror_NativeMirrorJni_nativeUpdatePickerPoint(
        JNIEnv *env, jclass clazz, jfloat normalized_x, jfloat normalized_y, jboolean visible) {
    (void) env;
    (void) clazz;
    renderer.overlay.picker[1] = fmaxf(0.0f, fminf(1.0f, normalized_x));
    renderer.overlay.picker[2] = fmaxf(0.0f, fminf(1.0f, normalized_y));
    renderer.overlay.picker[3] = visible ? 1.0f : 0.0f;
    invalidate_guide_overlay();
}

JNIEXPORT jlong JNICALL
Java_app_andy_desktop_service_mirror_NativeMirrorJni_nativeLatencyProbeTransitions(
        JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    pthread_mutex_lock(&stats_lock);
    const jlong transitions = (jlong) renderer.probe_transitions;
    pthread_mutex_unlock(&stats_lock);
    return transitions;
}

JNIEXPORT jint JNICALL
Java_app_andy_desktop_service_mirror_NativeMirrorJni_nativeInspectPixel(
        JNIEnv *env, jclass clazz, jfloat normalized_x, jfloat normalized_y) {
    (void) env;
    (void) clazz;
    pthread_mutex_lock(&latest_pixels_lock);
    CVPixelBufferRef pixels = renderer.latest_pixels;
    if (pixels) {
        CVPixelBufferRetain(pixels);
    }
    pthread_mutex_unlock(&latest_pixels_lock);
    if (!pixels) {
        return -1;
    }
    const size_t width = CVPixelBufferGetWidthOfPlane(pixels, 0);
    const size_t height = CVPixelBufferGetHeightOfPlane(pixels, 0);
    if (!width || !height ||
        CVPixelBufferLockBaseAddress(pixels, kCVPixelBufferLock_ReadOnly) != kCVReturnSuccess) {
        CVPixelBufferRelease(pixels);
        return -1;
    }
    const size_t x = (size_t) fmaxf(0.0f, fminf((float) width - 1.0f, normalized_x * width));
    const size_t y = (size_t) fmaxf(0.0f, fminf((float) height - 1.0f, normalized_y * height));
    const uint8_t *y_base = CVPixelBufferGetBaseAddressOfPlane(pixels, 0);
    const uint8_t *uv_base = CVPixelBufferGetBaseAddressOfPlane(pixels, 1);
    const size_t y_stride = CVPixelBufferGetBytesPerRowOfPlane(pixels, 0);
    const size_t uv_stride = CVPixelBufferGetBytesPerRowOfPlane(pixels, 1);
    const int yy = y_base[y * y_stride + x] - 16;
    const size_t uv_x = (x / 2) * 2;
    const size_t uv_y = y / 2;
    const int uu = uv_base[uv_y * uv_stride + uv_x] - 128;
    const int vv = uv_base[uv_y * uv_stride + uv_x + 1] - 128;
    const int red = (298 * yy + 409 * vv + 128) >> 8;
    const int green = (298 * yy - 100 * uu - 208 * vv + 128) >> 8;
    const int blue = (298 * yy + 516 * uu + 128) >> 8;
    CVPixelBufferUnlockBaseAddress(pixels, kCVPixelBufferLock_ReadOnly);
    CVPixelBufferRelease(pixels);
    const int r = red < 0 ? 0 : (red > 255 ? 255 : red);
    const int g = green < 0 ? 0 : (green > 255 ? 255 : green);
    const int b = blue < 0 ? 0 : (blue > 255 ? 255 : blue);
    return (jint) (0xff000000u | ((unsigned) r << 16) | ((unsigned) g << 8) | (unsigned) b);
}

JNIEXPORT jfloat JNICALL
Java_app_andy_desktop_service_mirror_NativeMirrorJni_nativeP95InputToPresentMillis(
        JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    double samples[120];
    pthread_mutex_lock(&stats_lock);
    const size_t count = renderer.input_to_present_count;
    if (count) {
        memcpy(samples, renderer.input_to_present_millis, count * sizeof(double));
    }
    pthread_mutex_unlock(&stats_lock);
    if (!count) {
        return -1.0f;
    }
    for (size_t i = 0; i < count; ++i) {
        for (size_t j = i + 1; j < count; ++j) {
            if (samples[j] < samples[i]) {
                double swap = samples[i];
                samples[i] = samples[j];
                samples[j] = swap;
            }
        }
    }
    size_t index = (size_t) ceil(count * 0.95) - 1;
    return (jfloat) samples[index];
}

JNIEXPORT jstring JNICALL
Java_app_andy_desktop_service_mirror_NativeMirrorJni_nativeInputToPresentSamplesMillis(
        JNIEnv *env, jclass clazz) {
    (void) clazz;
    char output[2048] = {0};
    size_t offset = 0;
    double samples[120];
    pthread_mutex_lock(&stats_lock);
    const size_t count = renderer.input_to_present_count;
    memcpy(samples, renderer.input_to_present_millis, count * sizeof(double));
    pthread_mutex_unlock(&stats_lock);
    for (size_t i = 0; i < count && offset < sizeof(output); ++i) {
        int written = snprintf(output + offset, sizeof(output) - offset, "%s%.3f",
                               i ? "," : "", samples[i]);
        if (written < 0 || (size_t) written >= sizeof(output) - offset) {
            break;
        }
        offset += (size_t) written;
    }
    return (*env)->NewStringUTF(env, output);
}

JNIEXPORT jfloat JNICALL
Java_app_andy_desktop_service_mirror_NativeMirrorJni_nativeP95PacketToPresentMillis(
        JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    double samples[120];
    pthread_mutex_lock(&stats_lock);
    const size_t count = renderer.packet_to_present_count;
    if (count) {
        memcpy(samples, renderer.packet_to_present_millis, count * sizeof(double));
    }
    pthread_mutex_unlock(&stats_lock);
    if (!count) {
        return -1;
    }
    for (size_t i = 0; i < count; ++i) {
        for (size_t j = i + 1; j < count; ++j) {
            if (samples[j] < samples[i]) {
                const double value = samples[i];
                samples[i] = samples[j];
                samples[j] = value;
            }
        }
    }
    size_t index = (size_t) ceil(count * 0.95) - 1;
    return (jfloat) samples[index];
}

JNIEXPORT jfloat JNICALL
Java_app_andy_desktop_service_mirror_NativeMirrorJni_nativeP95TransportToPresentMillis(
        JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    double samples[120];
    pthread_mutex_lock(&stats_lock);
    const size_t count = renderer.transport_to_present_count;
    if (count) {
        memcpy(samples, renderer.transport_to_present_millis, count * sizeof(double));
    }
    pthread_mutex_unlock(&stats_lock);
    if (!count) {
        return -1;
    }
    for (size_t i = 0; i < count; ++i) {
        for (size_t j = i + 1; j < count; ++j) {
            if (samples[j] < samples[i]) {
                const double value = samples[i];
                samples[i] = samples[j];
                samples[j] = value;
            }
        }
    }
    size_t index = (size_t) ceil(count * 0.95) - 1;
    return (jfloat) samples[index];
}

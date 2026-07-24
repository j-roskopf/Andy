#import <jni.h>
#import <Foundation/Foundation.h>
#import <AppKit/AppKit.h>
#import <objc/runtime.h>
#import <objc/message.h>
#import <dlfcn.h>
#import <IOSurface/IOSurface.h>
#import <CoreVideo/CoreVideo.h>
#import <ApplicationServices/ApplicationServices.h>
#include <pthread.h>
#include <string.h>
#include <unistd.h>
#include "andy_mirror_internal.h"

static const int kAndyIosHidTarget = 0x32;
static const uint32_t kAndyIosHidEdgeBottom = 3;

typedef struct {
    bool available;
    char diagnostic[1024];
    void *core_simulator;
    void *simulator_kit;
    Class sim_service_context_class;
    Class sim_device_io_class;
    Class sim_device_legacy_hid_client_class;
    SEL shared_context_sel;
    SEL default_device_set_sel;
    SEL io_for_device_sel;
    SEL update_io_ports_sel;
    SEL port_identifier_sel;
    SEL descriptor_sel;
    SEL state_sel;
    SEL display_class_sel;
    SEL default_width_sel;
    SEL default_height_sel;
    SEL framebuffer_surface_sel;
    SEL register_screen_callbacks_sel;
    SEL register_io_surfaces_sel;
    SEL register_io_surface_singular_sel;
    SEL unregister_screen_callbacks_sel;
    SEL hid_init_sel;
    SEL hid_send_sel;
    void *(*indigo_mouse)(CGPoint *, CGPoint *, uint32_t, uint32_t, uint32_t, double, double, double, double);
    void *(*indigo_mouse_edge)(CGPoint *, CGPoint *, uint32_t, uint32_t, uint32_t, double, double);
    void *(*indigo_keyboard)(void *, unsigned short, int);
    void *(*indigo_button)(int, int, int);
    void *(*indigo_scroll)(uint32_t, double, double, double, int);
    void *(*create_pointer_service)(void);
    void *(*create_mouse_service)(void);
} AndyIosSimRuntime;

typedef struct {
    char udid[64];
    id io_client;
    id display_descriptor;
    NSUUID *callback_uuid;
    dispatch_queue_t queue;
    int display_width_points;
    int display_height_points;
    uint32_t last_surface_seed;
    bool connected;
} AndyIosSimSession;

static AndyIosSimRuntime sim_runtime = {0};
static AndyIosSimSession sim_session = {0};
static pthread_mutex_t sim_session_lock = PTHREAD_MUTEX_INITIALIZER;
static id sim_hid_client = nil;
static id sim_hid_device = nil;
static dispatch_source_t sim_frame_timer = NULL;

static void capture_latest_framebuffer(void);
static bool ensure_hid_client(void);

static void stop_sim_frame_pump(void) {
    if (!sim_frame_timer) return;
    dispatch_source_cancel(sim_frame_timer);
    sim_frame_timer = NULL;
}

static void start_sim_frame_pump(dispatch_queue_t queue) {
    stop_sim_frame_pump();
    sim_frame_timer = dispatch_source_create(DISPATCH_SOURCE_TYPE_TIMER, 0, 0, queue);
    if (!sim_frame_timer) return;
    dispatch_source_set_timer(
        sim_frame_timer,
        dispatch_time(DISPATCH_TIME_NOW, 33 * NSEC_PER_MSEC),
        33 * NSEC_PER_MSEC,
        5 * NSEC_PER_MSEC);
    dispatch_source_set_event_handler(sim_frame_timer, ^{
        capture_latest_framebuffer();
    });
    dispatch_resume(sim_frame_timer);
}

static void schedule_boot_framebuffer_refreshes(dispatch_queue_t queue) {
    const int delays_ms[] = {250, 500, 1000, 2000, 4000};
    for (size_t i = 0; i < sizeof(delays_ms) / sizeof(delays_ms[0]); i++) {
        const int delay_ms = delays_ms[i];
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t) delay_ms * NSEC_PER_MSEC), queue, ^{
            capture_latest_framebuffer();
        });
    }
}

static void append_diagnostic(char *buffer, size_t size, const char *message) {
    if (!buffer || !size || !message) return;
    const size_t used = strlen(buffer);
    if (used >= size - 1) return;
    strncat(buffer, message, size - used - 1);
}

static NSString *developer_dir(void) {
    FILE *pipe = popen("xcode-select -p 2>/dev/null", "r");
    if (!pipe) return @"/Applications/Xcode.app/Contents/Developer";
    char path[PATH_MAX] = {0};
    if (fgets(path, sizeof(path), pipe)) {
        char *newline = strchr(path, '\n');
        if (newline) *newline = '\0';
    }
    pclose(pipe);
    if (path[0] == '\0') return @"/Applications/Xcode.app/Contents/Developer";
    return [NSString stringWithUTF8String:path];
}

static void *dlopen_first_path(NSArray<NSString *> *paths, char *diagnostic, size_t diagnostic_size, const char *label) {
    for (NSString *path in paths) {
        void *handle = dlopen([path fileSystemRepresentation], RTLD_LAZY);
        if (handle) return handle;
    }
    if (diagnostic && diagnostic_size && label) {
        append_diagnostic(diagnostic, diagnostic_size, label);
        const char *error = dlerror();
        if (error) append_diagnostic(diagnostic, diagnostic_size, error);
    }
    return NULL;
}

static bool probe_ios_sim_runtime(void) {
    if (sim_runtime.available) return true;
    memset(&sim_runtime, 0, sizeof(sim_runtime));
    NSString *dev_dir = developer_dir();
    NSString *legacy_core_path = [[dev_dir stringByAppendingPathComponent:
        @"/../Library/PrivateFrameworks/CoreSimulator.framework/CoreSimulator"] stringByStandardizingPath];
    NSArray<NSString *> *core_paths = @[
        @"/Library/Developer/PrivateFrameworks/CoreSimulator.framework/CoreSimulator",
        legacy_core_path,
    ];
    NSString *kit_path = [dev_dir stringByAppendingPathComponent:@"/Library/PrivateFrameworks/SimulatorKit.framework/SimulatorKit"];
    sim_runtime.core_simulator = dlopen_first_path(
        core_paths,
        sim_runtime.diagnostic,
        sizeof(sim_runtime.diagnostic),
        "CoreSimulator dlopen failed: ");
    sim_runtime.simulator_kit = dlopen([kit_path fileSystemRepresentation], RTLD_NOW | RTLD_GLOBAL);
    if (!sim_runtime.core_simulator) {
        return false;
    }
    if (!sim_runtime.simulator_kit) {
        append_diagnostic(sim_runtime.diagnostic, sizeof(sim_runtime.diagnostic), "SimulatorKit dlopen failed: ");
        const char *error = dlerror();
        if (error) append_diagnostic(sim_runtime.diagnostic, sizeof(sim_runtime.diagnostic), error);
        return false;
    }
    sim_runtime.sim_service_context_class = NSClassFromString(@"SimServiceContext");
    sim_runtime.sim_device_io_class = NSClassFromString(@"SimDeviceIO");
    sim_runtime.sim_device_legacy_hid_client_class = NSClassFromString(@"SimulatorKit.SimDeviceLegacyHIDClient");
    if (!sim_runtime.sim_device_legacy_hid_client_class) {
        sim_runtime.sim_device_legacy_hid_client_class = objc_lookUpClass("_TtC12SimulatorKit24SimDeviceLegacyHIDClient");
    }
    if (!sim_runtime.sim_device_legacy_hid_client_class) {
        sim_runtime.sim_device_legacy_hid_client_class = NSClassFromString(@"SimDeviceLegacyHIDClient");
    }
    if (!sim_runtime.sim_service_context_class || !sim_runtime.sim_device_io_class) {
        append_diagnostic(sim_runtime.diagnostic, sizeof(sim_runtime.diagnostic), "SimServiceContext/SimDeviceIO missing");
        return false;
    }
    sim_runtime.shared_context_sel = NSSelectorFromString(@"sharedServiceContextForDeveloperDir:error:");
    sim_runtime.default_device_set_sel = NSSelectorFromString(@"defaultDeviceSetWithError:");
    sim_runtime.io_for_device_sel = NSSelectorFromString(@"ioForSimDevice:errorQueue:errorHandler:");
    sim_runtime.update_io_ports_sel = @selector(updateIOPorts);
    sim_runtime.port_identifier_sel = @selector(portIdentifier);
    sim_runtime.descriptor_sel = @selector(descriptor);
    sim_runtime.state_sel = @selector(state);
    sim_runtime.display_class_sel = @selector(displayClass);
    sim_runtime.default_width_sel = @selector(defaultWidthForDisplay);
    sim_runtime.default_height_sel = @selector(defaultHeightForDisplay);
    sim_runtime.framebuffer_surface_sel = @selector(framebufferSurface);
    sim_runtime.register_screen_callbacks_sel = NSSelectorFromString(
        @"registerScreenCallbacksWithUUID:callbackQueue:frameCallback:surfacesChangedCallback:propertiesChangedCallback:");
    sim_runtime.register_io_surfaces_sel = NSSelectorFromString(@"registerCallbackWithUUID:ioSurfacesChangeCallback:");
    sim_runtime.register_io_surface_singular_sel = NSSelectorFromString(@"registerCallbackWithUUID:ioSurfaceChangeCallback:");
    sim_runtime.unregister_screen_callbacks_sel = NSSelectorFromString(@"unregisterScreenCallbacksWithUUID:");
    sim_runtime.hid_init_sel = NSSelectorFromString(@"initWithDevice:error:");
    sim_runtime.hid_send_sel = NSSelectorFromString(@"sendWithMessage:freeWhenDone:completionQueue:completion:");
    void *indigo_mouse_symbol = dlsym(sim_runtime.simulator_kit, "IndigoHIDMessageForMouseNSEvent");
    sim_runtime.indigo_mouse = indigo_mouse_symbol;
    sim_runtime.indigo_mouse_edge = indigo_mouse_symbol;
    sim_runtime.indigo_keyboard = dlsym(sim_runtime.simulator_kit, "IndigoHIDMessageForKeyboardNSEvent");
    sim_runtime.indigo_button = dlsym(sim_runtime.simulator_kit, "IndigoHIDMessageForButton");
    sim_runtime.indigo_scroll = dlsym(sim_runtime.simulator_kit, "IndigoHIDMessageForScrollEvent");
    sim_runtime.create_pointer_service = dlsym(sim_runtime.simulator_kit, "IndigoHIDMessageToCreatePointerService");
    sim_runtime.create_mouse_service = dlsym(sim_runtime.simulator_kit, "IndigoHIDMessageToCreateMouseService");
    if (!sim_runtime.register_screen_callbacks_sel && !sim_runtime.register_io_surfaces_sel) {
        append_diagnostic(sim_runtime.diagnostic, sizeof(sim_runtime.diagnostic), "No framebuffer callback selectors");
        return false;
    }
    sim_runtime.available = true;
    return true;
}

static id sim_device_for_udid(NSString *udid_string, NSError **error) {
    id ctx = ((id (*)(id, SEL, id, NSError**))objc_msgSend)
        (sim_runtime.sim_service_context_class, sim_runtime.shared_context_sel, developer_dir(), error);
    if (!ctx) return nil;
    id device_set = ((id (*)(id, SEL, NSError**))objc_msgSend)(ctx, sim_runtime.default_device_set_sel, error);
    if (!device_set) return nil;
    NSUUID *udid = [[NSUUID alloc] initWithUUIDString:udid_string];
    return [[device_set valueForKey:@"devicesByUDID"] objectForKey:udid];
}

static id main_display_descriptor(id io_client) {
    ((void (*)(id, SEL))objc_msgSend)(io_client, sim_runtime.update_io_ports_sel);
    NSArray *ports = [io_client valueForKey:@"deviceIOPorts"];
    for (id port in ports) {
        if (![port respondsToSelector:sim_runtime.port_identifier_sel]) continue;
        id pid = ((id (*)(id, SEL))objc_msgSend)(port, sim_runtime.port_identifier_sel);
        if (![pid isEqual:@"com.apple.framebuffer.display"]) continue;
        if (![port respondsToSelector:sim_runtime.descriptor_sel]) continue;
        id desc = ((id (*)(id, SEL))objc_msgSend)(port, sim_runtime.descriptor_sel);
        if (![desc respondsToSelector:sim_runtime.state_sel]) continue;
        id state = ((id (*)(id, SEL))objc_msgSend)(desc, sim_runtime.state_sel);
        unsigned short display_class = 0;
        if ([state respondsToSelector:sim_runtime.display_class_sel]) {
            display_class = ((unsigned short (*)(id, SEL))objc_msgSend)(state, sim_runtime.display_class_sel);
        }
        if (display_class != 0) continue;
        if (![desc respondsToSelector:sim_runtime.framebuffer_surface_sel]) continue;
        return desc;
    }
    return nil;
}

static void present_iosurface(IOSurfaceRef surface) {
    if (!surface) return;
    IOSurfaceIncrementUseCount(surface);
    CVPixelBufferRef pixel_buffer = NULL;
    NSDictionary *pixel_buffer_attrs = @{
        (__bridge NSString *) kCVPixelBufferMetalCompatibilityKey: @YES,
    };
    if (CVPixelBufferCreateWithIOSurface(
            kCFAllocatorDefault, surface, (__bridge CFDictionaryRef) pixel_buffer_attrs, &pixel_buffer) != kCVReturnSuccess || !pixel_buffer) {
        IOSurfaceDecrementUseCount(surface);
        return;
    }
    andy_mirror_remember_latest_pixels(pixel_buffer);
    const int64_t hub_decoder = andy_hub_ios_decoder();
    if (hub_decoder != ANDY_HUB_INVALID_ID) {
        const bool probe = andy_hub_latency_probe_changed(hub_decoder, pixel_buffer);
        andy_hub_render_pixel_buffer(hub_decoder, pixel_buffer, probe, 0, 0, true);
        CVPixelBufferRelease(pixel_buffer);
        IOSurfaceDecrementUseCount(surface);
        return;
    }
    const bool probe = andy_mirror_latency_probe_changed(pixel_buffer);
    andy_mirror_render_pixel_buffer(pixel_buffer, probe, 0, 0, true);
    CVPixelBufferRelease(pixel_buffer);
    IOSurfaceDecrementUseCount(surface);
}

static void capture_latest_framebuffer(void) {
    pthread_mutex_lock(&sim_session_lock);
    id desc = sim_session.display_descriptor;
    uint32_t last_seed = sim_session.last_surface_seed;
    pthread_mutex_unlock(&sim_session_lock);
    if (!desc) return;
    IOSurfaceRef surface = ((IOSurfaceRef (*)(id, SEL))objc_msgSend)(desc, sim_runtime.framebuffer_surface_sel);
    if (!surface) return;
    const uint32_t seed = IOSurfaceGetSeed(surface);
    if (seed == last_seed) return;
    pthread_mutex_lock(&sim_session_lock);
    sim_session.last_surface_seed = seed;
    pthread_mutex_unlock(&sim_session_lock);
    present_iosurface(surface);
}

static void teardown_sim_session(void) {
    pthread_mutex_lock(&sim_session_lock);
    id desc = sim_session.display_descriptor;
    NSUUID *uuid = sim_session.callback_uuid;
    dispatch_queue_t queue = sim_session.queue;
    sim_session.display_descriptor = nil;
    sim_session.io_client = nil;
    sim_session.callback_uuid = nil;
    sim_session.queue = nil;
    sim_session.connected = false;
    sim_session.last_surface_seed = 0;
    sim_session.display_width_points = 0;
    sim_session.display_height_points = 0;
    memset(sim_session.udid, 0, sizeof(sim_session.udid));
    pthread_mutex_unlock(&sim_session_lock);
    andy_mirror_set_ios_source_active(false);
    stop_sim_frame_pump();
    if (desc && uuid && [desc respondsToSelector:sim_runtime.unregister_screen_callbacks_sel]) {
        ((void (*)(id, SEL, NSUUID*))objc_msgSend)(desc, sim_runtime.unregister_screen_callbacks_sel, uuid);
    }
    (void) queue;
    sim_hid_client = nil;
    sim_hid_device = nil;
}

static bool sim_device_is_booted(id device) {
    if (!device) return false;
    id state = [device valueForKey:@"state"];
    if (!state) return false;
    return [state intValue] == 3;
}

static void invalidate_hid_client(void) {
    sim_hid_client = nil;
    sim_hid_device = nil;
}

static bool session_target_booted(void) {
    pthread_mutex_lock(&sim_session_lock);
    NSString *udid = sim_session.udid[0] ? [NSString stringWithUTF8String:sim_session.udid] : nil;
    const bool session_connected = sim_session.connected;
    pthread_mutex_unlock(&sim_session_lock);
    if (!udid || !session_connected) return false;
    id device = sim_device_for_udid(udid, NULL);
    return device != nil && sim_device_is_booted(device);
}

static bool hid_client_matches_session(void) {
    if (!sim_hid_client) return false;
    return session_target_booted();
}

/**
 * True when Simulator.app has an on-screen device chrome window.
 * Optional [display_name] matches the window title (e.g. "iPhone 17 Pro"). When titles are
 * unavailable (Screen Recording denied) any device-sized Simulator window counts as a match.
 */
static bool simulator_has_visible_device_window(NSString *display_name) {
    CFArrayRef info = CGWindowListCopyWindowInfo(
        kCGWindowListOptionOnScreenOnly | kCGWindowListExcludeDesktopElements,
        kCGNullWindowID);
    if (!info) return false;
    const CFIndex count = CFArrayGetCount(info);
    bool any_device = false;
    bool named_match = false;
    const bool want_name = display_name.length > 0;
    for (CFIndex i = 0; i < count; i++) {
        CFDictionaryRef window = CFArrayGetValueAtIndex(info, i);
        if (!window) continue;
        NSString *owner = (__bridge NSString *)CFDictionaryGetValue(window, kCGWindowOwnerName);
        if (![owner isEqualToString:@"Simulator"]) continue;
        CFNumberRef layer_ref = CFDictionaryGetValue(window, kCGWindowLayer);
        int layer = -1;
        if (layer_ref) CFNumberGetValue(layer_ref, kCFNumberIntType, &layer);
        if (layer != 0) continue;
        CFDictionaryRef bounds = CFDictionaryGetValue(window, kCGWindowBounds);
        if (!bounds) continue;
        CGRect rect = CGRectZero;
        if (!CGRectMakeWithDictionaryRepresentation(bounds, &rect)) continue;
        // Device windows are phone/tablet chrome; ignore menu-strip leftovers (~33pt tall).
        if (rect.size.width < 150.0 || rect.size.height < 200.0) continue;
        any_device = true;
        if (!want_name) {
            named_match = true;
            break;
        }
        NSString *title = (__bridge NSString *)CFDictionaryGetValue(window, kCGWindowName);
        if (title.length > 0 &&
            [title rangeOfString:display_name options:NSCaseInsensitiveSearch].location != NSNotFound) {
            named_match = true;
            break;
        }
    }
    CFRelease(info);
    if (!want_name) return any_device;
    if (named_match) return true;
    // Privacy-stripped titles: still treat a lone device window as belonging to the handoff.
    return any_device;
}

JNIEXPORT jboolean JNICALL
Java_app_andy_desktop_service_ios_NativeIosSimJni_nativeHasVisibleDeviceWindow(
        JNIEnv *env, jclass clazz, jstring display_name) {
    (void) clazz;
    NSString *name = nil;
    if (display_name) {
        const char *utf = (*env)->GetStringUTFChars(env, display_name, NULL);
        if (utf) {
            name = [NSString stringWithUTF8String:utf];
            (*env)->ReleaseStringUTFChars(env, display_name, utf);
        }
    }
    return simulator_has_visible_device_window(name) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_app_andy_desktop_service_ios_NativeIosSimJni_nativeHideSimulatorApp(
        JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    for (NSRunningApplication *app in [[NSWorkspace sharedWorkspace] runningApplications]) {
        if ([[app bundleIdentifier] isEqualToString:@"com.apple.iphonesimulator"]) {
            [app hide];
            return;
        }
    }
}

JNIEXPORT void JNICALL
Java_app_andy_desktop_service_ios_NativeIosSimJni_nativeResetInput(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    invalidate_hid_client();
}

JNIEXPORT jboolean JNICALL
Java_app_andy_desktop_service_ios_NativeIosSimJni_nativeIsCaptureHealthy(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return session_target_booted() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_app_andy_desktop_service_ios_NativeIosSimJni_nativeProbe(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return probe_ios_sim_runtime() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_app_andy_desktop_service_ios_NativeIosSimJni_nativeDiagnostic(JNIEnv *env, jclass clazz) {
    (void) clazz;
    probe_ios_sim_runtime();
    return (*env)->NewStringUTF(env, sim_runtime.diagnostic);
}

JNIEXPORT jintArray JNICALL
Java_app_andy_desktop_service_ios_NativeIosSimJni_nativeConnect(JNIEnv *env, jclass clazz, jstring udid) {
    (void) clazz;
    jint size_out[2] = {0, 0};
    jintArray result = (*env)->NewIntArray(env, 2);
    if (!result) return NULL;
    if (!probe_ios_sim_runtime()) {
        (*env)->SetIntArrayRegion(env, result, 0, 2, size_out);
        return result;
    }
    const char *udid_utf = (*env)->GetStringUTFChars(env, udid, NULL);
    if (!udid_utf) return result;
    teardown_sim_session();
    @try {
        NSError *error = nil;
        NSString *udid_string = [NSString stringWithUTF8String:udid_utf];
        id device = sim_device_for_udid(udid_string, &error);
        if (!device) {
            append_diagnostic(sim_runtime.diagnostic, sizeof(sim_runtime.diagnostic), " SimDevice not found");
            (*env)->ReleaseStringUTFChars(env, udid, udid_utf);
            (*env)->SetIntArrayRegion(env, result, 0, 2, size_out);
            return result;
        }
        dispatch_queue_t queue = dispatch_queue_create("andy.ios.sim", DISPATCH_QUEUE_SERIAL);
        id io_client = ((id (*)(id, SEL, id, dispatch_queue_t, void(^)(NSError*)))objc_msgSend)
            (sim_runtime.sim_device_io_class, sim_runtime.io_for_device_sel, device, queue, ^(NSError *e) {
                if (e) NSLog(@"Andy iOS sim IO error: %@", e);
            });
        id desc = main_display_descriptor(io_client);
        if (!desc) {
            append_diagnostic(sim_runtime.diagnostic, sizeof(sim_runtime.diagnostic), " Main display descriptor missing");
            (*env)->ReleaseStringUTFChars(env, udid, udid_utf);
            (*env)->SetIntArrayRegion(env, result, 0, 2, size_out);
            return result;
        }
        NSUUID *callback_uuid = [NSUUID UUID];
        __block void (^frame_callback)(void) = ^{
            capture_latest_framebuffer();
        };
        if ([desc respondsToSelector:sim_runtime.register_screen_callbacks_sel]) {
            ((void (*)(id, SEL, NSUUID*, dispatch_queue_t, void(^)(void), void(^)(void), void(^)(void)))objc_msgSend)
                (desc, sim_runtime.register_screen_callbacks_sel, callback_uuid, queue, frame_callback, frame_callback, ^(void){});
        } else if ([desc respondsToSelector:sim_runtime.register_io_surfaces_sel]) {
            void (^surface_callback)(IOSurfaceRef, IOSurfaceRef) = ^(IOSurfaceRef a, IOSurfaceRef b) {
                (void) b;
                if (a) present_iosurface(a);
            };
            ((void (*)(id, SEL, NSUUID*, void(^)(IOSurfaceRef, IOSurfaceRef)))objc_msgSend)
                (desc, sim_runtime.register_io_surfaces_sel, callback_uuid, surface_callback);
        } else if ([desc respondsToSelector:sim_runtime.register_io_surface_singular_sel]) {
            void (^surface_callback)(IOSurfaceRef, IOSurfaceRef) = ^(IOSurfaceRef a, IOSurfaceRef b) {
                (void) b;
                if (a) present_iosurface(a);
            };
            ((void (*)(id, SEL, NSUUID*, void(^)(IOSurfaceRef, IOSurfaceRef)))objc_msgSend)
                (desc, sim_runtime.register_io_surface_singular_sel, callback_uuid, surface_callback);
        }
        int width_points = 0;
        int height_points = 0;
        if ([desc respondsToSelector:sim_runtime.state_sel]) {
            id state = ((id (*)(id, SEL))objc_msgSend)(desc, sim_runtime.state_sel);
            if ([state respondsToSelector:sim_runtime.default_width_sel]) {
                width_points = (int) ((unsigned int (*)(id, SEL))objc_msgSend)(state, sim_runtime.default_width_sel);
            }
            if ([state respondsToSelector:sim_runtime.default_height_sel]) {
                height_points = (int) ((unsigned int (*)(id, SEL))objc_msgSend)(state, sim_runtime.default_height_sel);
            }
        }
        IOSurfaceRef initial = ((IOSurfaceRef (*)(id, SEL))objc_msgSend)(desc, sim_runtime.framebuffer_surface_sel);
        if (initial) {
            size_out[0] = (jint) IOSurfaceGetWidth(initial);
            size_out[1] = (jint) IOSurfaceGetHeight(initial);
            if (width_points <= 0) {
                width_points = (int) (IOSurfaceGetWidth(initial) / 3);
            }
            if (height_points <= 0) {
                height_points = (int) (IOSurfaceGetHeight(initial) / 3);
            }
            present_iosurface(initial);
        } else if (width_points > 0 && height_points > 0) {
            size_out[0] = width_points;
            size_out[1] = height_points;
        }
        pthread_mutex_lock(&sim_session_lock);
        strncpy(sim_session.udid, udid_utf, sizeof(sim_session.udid) - 1);
        sim_session.io_client = io_client;
        sim_session.display_descriptor = desc;
        sim_session.callback_uuid = callback_uuid;
        sim_session.queue = queue;
        sim_session.display_width_points = width_points;
        sim_session.display_height_points = height_points;
        sim_session.connected = true;
        if (initial) sim_session.last_surface_seed = IOSurfaceGetSeed(initial);
        pthread_mutex_unlock(&sim_session_lock);
        andy_mirror_set_ios_source_active(true);
        start_sim_frame_pump(queue);
        schedule_boot_framebuffer_refreshes(queue);
        // Defer HID client creation to the first input. Building it here blocks the connect on
        // synchronous HID handshakes (each up to ~2s), which surfaced as a stuck
        // "Starting iOS screen capture" when the simulator was slow to answer HID.
        invalidate_hid_client();
    } @catch (NSException *exception) {
        append_diagnostic(sim_runtime.diagnostic, sizeof(sim_runtime.diagnostic), " Native exception during connect");
        teardown_sim_session();
    }
    (*env)->ReleaseStringUTFChars(env, udid, udid_utf);
    (*env)->SetIntArrayRegion(env, result, 0, 2, size_out);
    return result;
}

JNIEXPORT void JNICALL
Java_app_andy_desktop_service_ios_NativeIosSimJni_nativeDisconnect(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    teardown_sim_session();
}

JNIEXPORT jintArray JNICALL
Java_app_andy_desktop_service_ios_NativeIosSimJni_nativeContentSizePoints(JNIEnv *env, jclass clazz) {
    (void) clazz;
    jint size_out[2] = {0, 0};
    jintArray result = (*env)->NewIntArray(env, 2);
    if (!result) return NULL;
    pthread_mutex_lock(&sim_session_lock);
    size_out[0] = sim_session.display_width_points;
    size_out[1] = sim_session.display_height_points;
    pthread_mutex_unlock(&sim_session_lock);
    (*env)->SetIntArrayRegion(env, result, 0, 2, size_out);
    return result;
}

// HID input implementation continues in second part - sendTouch, sendKey, etc.

static bool send_hid_message_sync(void *message);
static void activate_simulator_app(void);
static void post_simulator_keyboard_shortcut(CGKeyCode key_code, CGEventFlags flags);

static bool send_hid_message_impl(id client, void *message) {
    if (!client || !message || !sim_runtime.hid_send_sel) return false;
    dispatch_semaphore_t sem = dispatch_semaphore_create(0);
    __block bool ok = true;
    dispatch_queue_t queue = dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0);
    ((void (*)(id, SEL, void*, BOOL, dispatch_queue_t, void(^)(NSError*)))objc_msgSend)
        (client, sim_runtime.hid_send_sel, message, YES, queue, ^(NSError *error) {
            if (error) {
                NSLog(@"Andy iOS HID error: %@", error);
                ok = false;
            }
            dispatch_semaphore_signal(sem);
        });
    const long wait_result =
        dispatch_semaphore_wait(sem, dispatch_time(DISPATCH_TIME_NOW, 2 * NSEC_PER_SEC));
    return wait_result == 0 && ok;
}

static bool ensure_hid_client_once(void) {
    if (sim_hid_client) return true;
    if (!sim_runtime.sim_device_legacy_hid_client_class || !sim_runtime.hid_init_sel) return false;
    pthread_mutex_lock(&sim_session_lock);
    NSString *udid = sim_session.udid[0] ? [NSString stringWithUTF8String:sim_session.udid] : nil;
    const bool session_connected = sim_session.connected;
    pthread_mutex_unlock(&sim_session_lock);
    if (!udid || !session_connected) return false;
    NSError *error = nil;
    id device = sim_device_for_udid(udid, &error);
    if (!device || !sim_device_is_booted(device)) return false;
    id client = ((id (*)(id, SEL, id, NSError**))objc_msgSend)(
        [sim_runtime.sim_device_legacy_hid_client_class alloc],
        sim_runtime.hid_init_sel,
        device,
        &error);
    if (!client) return false;
    if (sim_runtime.create_pointer_service) {
        void *message = sim_runtime.create_pointer_service();
        if (!message || !send_hid_message_impl(client, message)) {
            return false;
        }
        usleep(20000);
    }
    if (sim_runtime.create_mouse_service) {
        void *message = sim_runtime.create_mouse_service();
        if (!message || !send_hid_message_impl(client, message)) {
            return false;
        }
        usleep(20000);
    }
    sim_hid_device = device;
    sim_hid_client = client;
    return true;
}

static bool send_hid_message_sync(void *message);

/** Retries HID attach — Simulator.app may still be coming up after a headless simctl boot. */
static bool ensure_hid_client_ready(int attempts, useconds_t delay_us) {
    if (sim_hid_client && !hid_client_matches_session()) {
        invalidate_hid_client();
    }
    if (sim_hid_client) return true;
    for (int attempt = 0; attempt < attempts; attempt++) {
        if (ensure_hid_client_once()) return true;
        if (attempt + 1 < attempts) usleep(delay_us);
    }
    return false;
}

static bool ensure_hid_client(void) {
    // A couple of quick retries so the first tap after Devices → Live still works when
    // Simulator.app / Indigo is slightly behind screen capture. Keep this short — connect
    // no longer waits here, but sendInput still can.
    return ensure_hid_client_ready(3, 100000);
}

static bool send_hid_message_sync(void *message) {
    if (!message) return false;
    if (!ensure_hid_client()) return false;
    andy_mirror_record_input();
    const bool ok = send_hid_message_impl(sim_hid_client, message);
    if (!ok) invalidate_hid_client();
    return ok;
}

JNIEXPORT jboolean JNICALL
Java_app_andy_desktop_service_ios_NativeIosSimJni_nativeEnsureInputReady(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    // Longer budget than per-tap retries: used once after connect while Simulator.app starts.
    return ensure_hid_client_ready(12, 250000) ? JNI_TRUE : JNI_FALSE;
}

static void send_hid_message(void *message) {
    if (!message) return;
    (void) send_hid_message_sync(message);
}

static CGSize screen_size_points(void) {
    pthread_mutex_lock(&sim_session_lock);
    const int w = sim_session.display_width_points > 0 ? sim_session.display_width_points : 390;
    const int h = sim_session.display_height_points > 0 ? sim_session.display_height_points : 844;
    pthread_mutex_unlock(&sim_session_lock);
    return CGSizeMake(w, h);
}

// IndigoHIDMessageForMouseNSEvent divides the input point by SIMD q0/q1 to recover
// screen ratios. Preset those registers to 1.0 on arm64 so normalized coordinates work.
static void preset_indigo_hid_simd_registers(void) {
#if defined(__aarch64__)
    double one = 1.0;
    __asm__ volatile(
        "ldr d0, %[one]\n"
        "ldr d1, %[one]\n"
        "ldr d2, %[one]\n"
        "ldr d3, %[one]\n"
        : : [one] "m"(one)
        : "d0", "d1", "d2", "d3");
#endif
}

static void *create_indigo_mouse_message(float nx, float ny, int ns_event_type, int direction) {
    if (!sim_runtime.indigo_mouse) return NULL;
    preset_indigo_hid_simd_registers();
    CGSize screen = screen_size_points();
    const double width_pts = screen.width > 0 ? screen.width : 390.0;
    const double height_pts = screen.height > 0 ? screen.height : 844.0;
#if defined(__aarch64__)
    CGPoint point = CGPointMake(nx, ny);
#else
    CGPoint point = CGPointMake(nx * width_pts, ny * height_pts);
#endif
    return sim_runtime.indigo_mouse(
        &point, NULL, kAndyIosHidTarget, (uint32_t) ns_event_type, (uint32_t) direction,
        1.0, 1.0, width_pts, height_pts);
}

static void *create_indigo_mouse_edge_message(float nx, float ny, uint32_t ns_event_type, uint32_t edge_flag) {
    if (!sim_runtime.indigo_mouse_edge) return NULL;
    preset_indigo_hid_simd_registers();
    CGPoint point = CGPointMake(nx, ny);
    return sim_runtime.indigo_mouse_edge(
        &point, NULL, kAndyIosHidTarget, ns_event_type, edge_flag, 1.0, 1.0);
}

static void touch_event_codes(int action, int *ns_event_type, int *edge) {
    switch (action) {
        case 0:
            *ns_event_type = 1;
            *edge = 1;
            break;
        case 1:
            *ns_event_type = 6;
            *edge = 0;
            break;
        default:
            *ns_event_type = 2;
            *edge = 2;
            break;
    }
}

JNIEXPORT jboolean JNICALL
Java_app_andy_desktop_service_ios_NativeIosSimJni_nativeSendTouch(
        JNIEnv *env, jclass clazz, jint action, jfloat nx, jfloat ny) {
    (void) env;
    (void) clazz;
    int ns_event_type = 2;
    int edge = 2;
    touch_event_codes(action, &ns_event_type, &edge);
    void *message = create_indigo_mouse_message(nx, ny, ns_event_type, edge);
    if (!message) return JNI_FALSE;
    return send_hid_message_sync(message) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_app_andy_desktop_service_ios_NativeIosSimJni_nativeSendScroll(
        JNIEnv *env, jclass clazz, jfloat nx, jfloat ny, jfloat delta_y) {
    (void) env;
    (void) clazz;
    (void) nx;
    (void) ny;
    if (!sim_runtime.indigo_scroll) return;
    void *message = sim_runtime.indigo_scroll(0, 0.0, (double) delta_y, 0.0, kAndyIosHidTarget);
    send_hid_message(message);
}

JNIEXPORT void JNICALL
Java_app_andy_desktop_service_ios_NativeIosSimJni_nativeSendText(
        JNIEnv *env, jclass clazz, jstring text) {
    (void) clazz;
    if (!sim_runtime.indigo_keyboard || !text) return;
    const char *utf = (*env)->GetStringUTFChars(env, text, NULL);
    if (!utf) return;
    NSString *value = [NSString stringWithUTF8String:utf];
    for (NSUInteger i = 0; i < value.length; i++) {
        const unichar character = [value characterAtIndex:i];
        void *message = sim_runtime.indigo_keyboard(NULL, character, NSEventTypeKeyDown);
        send_hid_message(message);
        message = sim_runtime.indigo_keyboard(NULL, character, NSEventTypeKeyUp);
        send_hid_message(message);
    }
    (*env)->ReleaseStringUTFChars(env, text, utf);
}

static void send_home_gesture(void) {
    if (!sim_runtime.indigo_mouse_edge) {
        activate_simulator_app();
        post_simulator_keyboard_shortcut((CGKeyCode) 4, kCGEventFlagMaskCommand | kCGEventFlagMaskShift);
        return;
    }
    const int steps = 12;
    const float start_y = 0.998f;
    const float end_y = 0.30f;
    if (!send_hid_message_sync(create_indigo_mouse_edge_message(0.5f, start_y, 1, kAndyIosHidEdgeBottom))) {
        activate_simulator_app();
        post_simulator_keyboard_shortcut((CGKeyCode) 4, kCGEventFlagMaskCommand | kCGEventFlagMaskShift);
        return;
    }
    usleep(20000);
    for (int step = 1; step <= steps; step++) {
        const float t = (float) step / (float) steps;
        const float ny = start_y + (end_y - start_y) * t;
        send_hid_message_sync(create_indigo_mouse_edge_message(0.5f, ny, 6, kAndyIosHidEdgeBottom));
        usleep(16000);
    }
    send_hid_message_sync(create_indigo_mouse_edge_message(0.5f, end_y, 2, kAndyIosHidEdgeBottom));
}

static void activate_simulator_app(void) {
    for (NSRunningApplication *app in [[NSWorkspace sharedWorkspace] runningApplications]) {
        if ([[app bundleIdentifier] isEqualToString:@"com.apple.iphonesimulator"]) {
            [app activateWithOptions:NSApplicationActivateAllWindows];
            usleep(50000);
            return;
        }
    }
}

static pid_t simulator_app_pid(void) {
    for (NSRunningApplication *app in [[NSWorkspace sharedWorkspace] runningApplications]) {
        if ([[app bundleIdentifier] isEqualToString:@"com.apple.iphonesimulator"]) {
            return app.processIdentifier;
        }
    }
    return 0;
}

static void post_simulator_keyboard_shortcut(CGKeyCode key_code, CGEventFlags flags) {
    activate_simulator_app();
    const pid_t pid = simulator_app_pid();
    if (pid <= 0) return;
    CGEventRef down = CGEventCreateKeyboardEvent(NULL, key_code, true);
    CGEventRef up = CGEventCreateKeyboardEvent(NULL, key_code, false);
    if (!down || !up) {
        if (down) CFRelease(down);
        if (up) CFRelease(up);
        return;
    }
    CGEventSetFlags(down, flags);
    CGEventSetFlags(up, flags);
    CGEventPostToPid(pid, down);
    usleep(50000);
    CGEventPostToPid(pid, up);
    CFRelease(down);
    CFRelease(up);
}

JNIEXPORT void JNICALL
Java_app_andy_desktop_service_ios_NativeIosSimJni_nativeSendButton(
        JNIEnv *env, jclass clazz, jint button) {
    (void) env;
    (void) clazz;
    if (button == 1) {
        // Cmd+L is the Simulator shortcut for lock/power.
        post_simulator_keyboard_shortcut((CGKeyCode) 37, kCGEventFlagMaskCommand);
        return;
    }
    // Hardware-button HID is not connected for embedded mirror sessions. Use the same
    // bottom-edge swipe gesture modern iPhones expose in the simulator UI.
    send_home_gesture();
}

JNIEXPORT void JNICALL
Java_app_andy_desktop_service_ios_NativeIosSimJni_nativeSendSwipe(
        JNIEnv *env, jclass clazz,
        jfloat start_x, jfloat start_y, jfloat end_x, jfloat end_y, jint steps) {
    (void) env;
    (void) clazz;
    if (!sim_runtime.indigo_mouse) return;
    const int count = steps < 2 ? 8 : steps;
    void *down = create_indigo_mouse_message(start_x, start_y, 1, 1);
    if (!send_hid_message_sync(down)) return;
    usleep(20000);
    for (int step = 1; step <= count; step++) {
        const float t = (float) step / (float) count;
        const float nx = start_x + (end_x - start_x) * t;
        const float ny = start_y + (end_y - start_y) * t;
        send_hid_message_sync(create_indigo_mouse_message(nx, ny, 6, 0));
        usleep(16000);
    }
    send_hid_message_sync(create_indigo_mouse_message(end_x, end_y, 2, 2));
}

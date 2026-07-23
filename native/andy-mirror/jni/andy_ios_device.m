#import <jni.h>
#import <Foundation/Foundation.h>
#import <AVFoundation/AVFoundation.h>
#import <CoreMediaIO/CMIOHardware.h>
#import <CoreVideo/CoreVideo.h>
#include <pthread.h>
#include <string.h>
#include <unistd.h>
#include "andy_mirror_internal.h"

static AVCaptureSession *device_session = nil;
static dispatch_queue_t device_queue = nil;
static char connected_udid[64] = {0};
static int content_width = 0;
static int content_height = 0;
static bool camera_enabled = false;
static char device_error[512] = {0};

static void set_device_error(const char *message) {
    if (!message) return;
    strncpy(device_error, message, sizeof(device_error) - 1);
}

static void append_device_error(const char *message) {
    if (!message) return;
    const size_t used = strlen(device_error);
    if (used >= sizeof(device_error) - 1) return;
    strncat(device_error, message, sizeof(device_error) - used - 1);
}

static void run_on_main(void (^block)(void)) {
    if ([NSThread isMainThread]) {
        block();
    } else {
        dispatch_sync(dispatch_get_main_queue(), block);
    }
}

static void request_camera_access_sync(void) {
    AVAuthorizationStatus status = [AVCaptureDevice authorizationStatusForMediaType:AVMediaTypeVideo];
    if (status != AVAuthorizationStatusNotDetermined) {
        return;
    }
    dispatch_semaphore_t sem = dispatch_semaphore_create(0);
    [AVCaptureDevice requestAccessForMediaType:AVMediaTypeVideo completionHandler:^(BOOL granted) {
        (void) granted;
        dispatch_semaphore_signal(sem);
    }];
    while (dispatch_semaphore_wait(sem, DISPATCH_TIME_NOW) != 0) {
        [[NSRunLoop currentRunLoop] runMode:NSDefaultRunLoopMode
                                  beforeDate:[NSDate dateWithTimeIntervalSinceNow:0.05]];
    }
}

static bool udid_matches(NSString *candidate_id, NSString *requested_udid) {
    if (!candidate_id || !requested_udid || !requested_udid.length) return false;
    if ([candidate_id isEqualToString:requested_udid]) return true;
    if ([candidate_id caseInsensitiveCompare:requested_udid] == NSOrderedSame) return true;
    NSString *candidate_compact = [[candidate_id stringByReplacingOccurrencesOfString:@"-" withString:@""] lowercaseString];
    NSString *requested_compact = [[requested_udid stringByReplacingOccurrencesOfString:@"-" withString:@""] lowercaseString];
    return candidate_compact.length > 0 && [candidate_compact isEqualToString:requested_compact];
}

static bool name_matches(NSString *device_name, NSString *requested_name) {
    if (!device_name.length || !requested_name.length) return false;
    if ([device_name isEqualToString:requested_name]) return true;
    return [device_name localizedCaseInsensitiveContainsString:requested_name] ||
        [requested_name localizedCaseInsensitiveContainsString:device_name];
}

static bool is_ios_screen_capture_device(AVCaptureDevice *device) {
    if (!device) return false;
    if (![device hasMediaType:AVMediaTypeMuxed]) return false;
    NSString *model = device.modelID ?: @"";
    if ([model rangeOfString:@"iPhone" options:NSCaseInsensitiveSearch].location != NSNotFound) return true;
    if ([model rangeOfString:@"iPad" options:NSCaseInsensitiveSearch].location != NSNotFound) return true;
    NSString *name = device.localizedName ?: @"";
    if ([name rangeOfString:@"iPhone" options:NSCaseInsensitiveSearch].location != NSNotFound) return true;
    if ([name rangeOfString:@"iPad" options:NSCaseInsensitiveSearch].location != NSNotFound) return true;
    return true;
}

static NSArray<AVCaptureDevice *> *discover_ios_screen_devices(void) {
    if (@available(macOS 14.0, *)) {
        AVCaptureDeviceDiscoverySession *discovery = [AVCaptureDeviceDiscoverySession
            discoverySessionWithDeviceTypes:@[AVCaptureDeviceTypeExternal]
                                  mediaType:AVMediaTypeMuxed
                                   position:AVCaptureDevicePositionUnspecified];
        NSMutableArray<AVCaptureDevice *> *matches = [NSMutableArray array];
        for (AVCaptureDevice *candidate in discovery.devices) {
            if (is_ios_screen_capture_device(candidate)) {
                [matches addObject:candidate];
            }
        }
        return matches;
    }
    return @[];
}

static void warmup_capture_discovery(void) {
    (void) discover_ios_screen_devices();
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdeprecated-declarations"
    (void) [AVCaptureDevice devicesWithMediaType:AVMediaTypeMuxed];
#pragma clang diagnostic pop
}

static bool id_matches_any(NSString *candidate_id, NSString *udid_string, NSString *alt_id_string) {
    return udid_matches(candidate_id, udid_string) || udid_matches(candidate_id, alt_id_string);
}

static AVCaptureDevice *find_ios_screen_device(NSString *udid_string, NSString *alt_id_string, NSString *name_string) {
    for (NSString *candidate_id in @[udid_string, alt_id_string]) {
        if (!candidate_id.length) continue;
        AVCaptureDevice *direct = [AVCaptureDevice deviceWithUniqueID:candidate_id];
        if (direct && is_ios_screen_capture_device(direct)) {
            return direct;
        }
    }
    NSArray<AVCaptureDevice *> *devices = discover_ios_screen_devices();
    for (AVCaptureDevice *candidate in devices) {
        if (id_matches_any(candidate.uniqueID, udid_string, alt_id_string)) {
            return candidate;
        }
    }
    if (name_string.length) {
        for (AVCaptureDevice *candidate in devices) {
            if (name_matches(candidate.localizedName, name_string)) {
                return candidate;
            }
        }
    }
    if (devices.count == 1) {
        return devices.firstObject;
    }
    return nil;
}

static AVCaptureDevice *wait_for_ios_screen_device(
    NSString *udid_string, NSString *alt_id_string, NSString *name_string, int timeout_ms) {
    __block AVCaptureDevice *found = nil;
    run_on_main(^{
        warmup_capture_discovery();
        NSDate *deadline = [NSDate dateWithTimeIntervalSinceNow:timeout_ms / 1000.0];
        __block id observer = nil;
        observer = [[NSNotificationCenter defaultCenter]
            addObserverForName:AVCaptureDeviceWasConnectedNotification
            object:nil
            queue:[NSOperationQueue mainQueue]
            usingBlock:^(NSNotification *note) {
                AVCaptureDevice *device = note.object;
                if (!device || !is_ios_screen_capture_device(device)) return;
                if (id_matches_any(device.uniqueID, udid_string, alt_id_string) ||
                    (name_string.length && name_matches(device.localizedName, name_string))) {
                    found = device;
                }
            }];
        while ([deadline timeIntervalSinceNow] > 0 && !found) {
            found = find_ios_screen_device(udid_string, alt_id_string, name_string);
            if (found) break;
            [[NSRunLoop currentRunLoop] runMode:NSDefaultRunLoopMode
                                      beforeDate:[NSDate dateWithTimeIntervalSinceNow:0.05]];
        }
        if (observer) {
            [[NSNotificationCenter defaultCenter] removeObserver:observer];
        }
    });
    return found;
}

static void append_discovered_device_names(void) {
    NSArray<AVCaptureDevice *> *devices = discover_ios_screen_devices();
    if (devices.count == 0) {
        append_device_error(" No muxed screen devices visible to macOS.");
        return;
    }
    append_device_error(" Visible screen devices:");
    for (AVCaptureDevice *device in devices) {
        char entry[160];
        snprintf(
            entry,
            sizeof(entry),
            " %s (%s)",
            device.localizedName.UTF8String ?: "device",
            device.uniqueID.UTF8String ?: "?");
        append_device_error(entry);
    }
}

static bool enable_screen_capture_devices(void) {
    if (camera_enabled) return true;
    UInt32 allow = 1;
    const OSStatus status = CMIOObjectSetPropertyData(
        kCMIOObjectSystemObject,
        &(CMIOObjectPropertyAddress) {
            .mSelector = kCMIOHardwarePropertyAllowScreenCaptureDevices,
            .mScope = kCMIOObjectPropertyScopeGlobal,
            .mElement = kCMIOObjectPropertyElementMain,
        },
        0,
        NULL,
        sizeof(UInt32),
        &allow);
    if (status != noErr) {
        set_device_error("Failed to enable CMIO screen capture devices");
        return false;
    }
    camera_enabled = true;
    return true;
}

static void teardown_device_session(void) {
    if (device_session) {
        [device_session stopRunning];
        device_session = nil;
    }
    device_queue = nil;
    content_width = 0;
    content_height = 0;
    memset(connected_udid, 0, sizeof(connected_udid));
    andy_mirror_set_ios_source_active(false);
}

@interface AndyIosDeviceDelegate : NSObject <AVCaptureVideoDataOutputSampleBufferDelegate>
@end

@implementation AndyIosDeviceDelegate
- (void)captureOutput:(AVCaptureOutput *)output
    didOutputSampleBuffer:(CMSampleBufferRef)sampleBuffer
           fromConnection:(AVCaptureConnection *)connection {
    (void) output;
    (void) connection;
    CVImageBufferRef image_buffer = CMSampleBufferGetImageBuffer(sampleBuffer);
    if (!image_buffer) return;
    const size_t width = CVPixelBufferGetWidth(image_buffer);
    const size_t height = CVPixelBufferGetHeight(image_buffer);
    if (width == 0 || height == 0) return;
    content_width = (int) width;
    content_height = (int) height;
    CVPixelBufferRetain(image_buffer);
    andy_mirror_remember_latest_pixels(image_buffer);
    const int64_t hub_decoder = andy_hub_ios_decoder();
    if (hub_decoder != ANDY_HUB_INVALID_ID) {
        const bool probe = andy_hub_latency_probe_changed(hub_decoder, image_buffer);
        andy_hub_render_pixel_buffer(hub_decoder, image_buffer, probe, 0, 0, true);
        CVPixelBufferRelease(image_buffer);
        return;
    }
    const bool probe = andy_mirror_latency_probe_changed(image_buffer);
    andy_mirror_render_pixel_buffer(image_buffer, probe, 0, 0, true);
    CVPixelBufferRelease(image_buffer);
}
@end

static AndyIosDeviceDelegate *device_delegate = nil;

JNIEXPORT jboolean JNICALL
Java_app_andy_desktop_service_ios_NativeIosDeviceJni_nativeProbe(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    if (@available(macOS 14.0, *)) {
        return JNI_TRUE;
    }
    set_device_error("Physical iOS mirroring requires macOS 14+");
    return JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_app_andy_desktop_service_ios_NativeIosDeviceJni_nativeDiagnostic(JNIEnv *env, jclass clazz) {
    (void) clazz;
    return (*env)->NewStringUTF(env, device_error);
}

JNIEXPORT void JNICALL
Java_app_andy_desktop_service_ios_NativeIosDeviceJni_nativePrepareForCapture(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    if (@available(macOS 14.0, *)) {
        run_on_main(^{
            request_camera_access_sync();
            enable_screen_capture_devices();
            warmup_capture_discovery();
        });
    }
}

JNIEXPORT jintArray JNICALL
Java_app_andy_desktop_service_ios_NativeIosDeviceJni_nativeConnect(
        JNIEnv *env, jclass clazz, jstring udid, jstring alt_udid, jstring display_name) {
    (void) clazz;
    jint size_out[2] = {0, 0};
    jintArray result = (*env)->NewIntArray(env, 2);
    if (!result) return NULL;
    const char *udid_utf = (*env)->GetStringUTFChars(env, udid, NULL);
    if (!udid_utf) return result;
    const char *alt_utf = alt_udid ? (*env)->GetStringUTFChars(env, alt_udid, NULL) : NULL;
    const char *name_utf = display_name ? (*env)->GetStringUTFChars(env, display_name, NULL) : NULL;
    teardown_device_session();
    device_error[0] = '\0';
    if (@available(macOS 14.0, *)) {
        __block BOOL connect_error = NO;
        __block const char *connect_error_message = NULL;
        run_on_main(^{
            request_camera_access_sync();
            if (!enable_screen_capture_devices()) {
                connect_error = YES;
                connect_error_message = device_error;
                return;
            }
            NSString *udid_string = [NSString stringWithUTF8String:udid_utf];
            NSString *alt_string = alt_utf ? [NSString stringWithUTF8String:alt_utf] : @"";
            NSString *name_string = name_utf ? [NSString stringWithUTF8String:name_utf] : @"";
            AVCaptureDevice *target = wait_for_ios_screen_device(udid_string, alt_string, name_string, 30000);
            if (!target) {
                set_device_error(
                    "No USB iPhone screen device found. Unlock the phone, tap Trust This Computer, "
                    "grant Camera access to Andy, then unplug and replug the cable.");
                append_discovered_device_names();
                connect_error = YES;
                connect_error_message = device_error;
                return;
            }
            NSError *error = nil;
            AVCaptureDeviceInput *input = [AVCaptureDeviceInput deviceInputWithDevice:target error:&error];
            if (!input || error) {
                set_device_error("Failed to open iPhone screen stream. Close QuickTime or other apps using it.");
                connect_error = YES;
                connect_error_message = device_error;
                return;
            }
            AVCaptureSession *session = [[AVCaptureSession alloc] init];
            session.sessionPreset = AVCaptureSessionPresetHigh;
            if (![session canAddInput:input]) {
                set_device_error("Cannot add iPhone capture input");
                connect_error = YES;
                connect_error_message = device_error;
                return;
            }
            [session addInput:input];
        AVCaptureVideoDataOutput *output = [[AVCaptureVideoDataOutput alloc] init];
        // Wired iOS screen capture is natively BGRA; forcing NV12 corrupts chroma (red tint).
        output.videoSettings = @{
            (NSString *) kCVPixelBufferPixelFormatTypeKey: @(kCVPixelFormatType_32BGRA),
            (NSString *) kCVPixelBufferMetalCompatibilityKey: @YES,
        };
            output.alwaysDiscardsLateVideoFrames = YES;
            if (![session canAddOutput:output]) {
                set_device_error("Cannot add iPhone video output");
                connect_error = YES;
                connect_error_message = device_error;
                return;
            }
            [session addOutput:output];
            if (!device_delegate) device_delegate = [[AndyIosDeviceDelegate alloc] init];
            device_queue = dispatch_queue_create("andy.ios.device", DISPATCH_QUEUE_SERIAL);
            [output setSampleBufferDelegate:device_delegate queue:device_queue];
            [session startRunning];
            device_session = session;
            strncpy(connected_udid, udid_utf, sizeof(connected_udid) - 1);
            andy_mirror_set_ios_source_active(true);
        });
        if (connect_error) {
            (void) connect_error_message;
            (*env)->ReleaseStringUTFChars(env, udid, udid_utf);
            if (alt_utf) (*env)->ReleaseStringUTFChars(env, alt_udid, alt_utf);
            if (name_utf) (*env)->ReleaseStringUTFChars(env, display_name, name_utf);
            (*env)->SetIntArrayRegion(env, result, 0, 2, size_out);
            return result;
        }
    } else {
        set_device_error("Physical iOS mirroring requires macOS 14+");
    }
    (*env)->ReleaseStringUTFChars(env, udid, udid_utf);
    if (alt_utf) (*env)->ReleaseStringUTFChars(env, alt_udid, alt_utf);
    if (name_utf) (*env)->ReleaseStringUTFChars(env, display_name, name_utf);
    size_out[0] = content_width;
    size_out[1] = content_height;
    (*env)->SetIntArrayRegion(env, result, 0, 2, size_out);
    return result;
}

JNIEXPORT void JNICALL
Java_app_andy_desktop_service_ios_NativeIosDeviceJni_nativeDisconnect(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    run_on_main(^{
        teardown_device_session();
    });
}

JNIEXPORT jintArray JNICALL
Java_app_andy_desktop_service_ios_NativeIosDeviceJni_nativeContentSize(JNIEnv *env, jclass clazz) {
    (void) clazz;
    jint size_out[2] = {content_width, content_height};
    jintArray result = (*env)->NewIntArray(env, 2);
    if (!result) return NULL;
    (*env)->SetIntArrayRegion(env, result, 0, 2, size_out);
    return result;
}

#include <jni.h>
#import <AVFoundation/AVFoundation.h>
#import <Foundation/Foundation.h>

/**
 * macOS microphone TCC bridge.
 *
 * Java Sound's TargetDataLine can open and return digital silence when TCC has never
 * been prompted. AVFoundation's requestAccessForMediaType: is what surfaces the system
 * dialog and the Control Center mic indicator.
 */

static void run_on_main_sync(void (^block)(void)) {
    if ([NSThread isMainThread]) {
        block();
    } else {
        dispatch_sync(dispatch_get_main_queue(), block);
    }
}

static jint status_code(AVAuthorizationStatus status) {
    switch (status) {
        case AVAuthorizationStatusAuthorized: return 1;
        case AVAuthorizationStatusDenied: return 0;
        case AVAuthorizationStatusRestricted: return 2;
        case AVAuthorizationStatusNotDetermined: return 3;
        default: return -1;
    }
}

JNIEXPORT jint JNICALL
Java_app_andy_desktop_service_voice_MacOsMicrophoneAccess_nativeAuthorizationStatus(
    JNIEnv *env,
    jclass cls
) {
    (void)env;
    (void)cls;
    return status_code([AVCaptureDevice authorizationStatusForMediaType:AVMediaTypeAudio]);
}

JNIEXPORT jint JNICALL
Java_app_andy_desktop_service_voice_MacOsMicrophoneAccess_nativeRequestAccess(
    JNIEnv *env,
    jclass cls
) {
    (void)env;
    (void)cls;
    __block jint result = -1;
    run_on_main_sync(^{
        AVAuthorizationStatus status = [AVCaptureDevice authorizationStatusForMediaType:AVMediaTypeAudio];
        if (status != AVAuthorizationStatusNotDetermined) {
            result = status_code(status);
            return;
        }
        dispatch_semaphore_t sem = dispatch_semaphore_create(0);
        [AVCaptureDevice requestAccessForMediaType:AVMediaTypeAudio completionHandler:^(BOOL granted) {
            result = granted ? 1 : 0;
            dispatch_semaphore_signal(sem);
        }];
        // Pump the run loop so the TCC dialog can present and the completion can fire.
        while (dispatch_semaphore_wait(sem, DISPATCH_TIME_NOW) != 0) {
            [[NSRunLoop currentRunLoop] runMode:NSDefaultRunLoopMode
                                     beforeDate:[NSDate dateWithTimeIntervalSinceNow:0.05]];
        }
    });
    return result;
}

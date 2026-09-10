#include <jni.h>
#import <AVFoundation/AVFoundation.h>
#import <Carbon/Carbon.h>
#import <Foundation/Foundation.h>

/**
 * macOS microphone TCC bridge + Carbon global hotkey.
 *
 * Java Sound's TargetDataLine can open and return digital silence when TCC has never
 * been prompted. AVFoundation's requestAccessForMediaType: is what surfaces the system
 * dialog and the Control Center mic indicator.
 *
 * Carbon RegisterEventHotKey needs no Accessibility grant (unlike CGEvent taps /
 * NSEvent global monitors), which is why voice-new-thread uses it for the unfocused hotkey.
 */

static JavaVM *g_jvm = NULL;
static jclass g_bridge_class = NULL; // GlobalRef to MacOsGlobalHotKeyBridge
static jmethodID g_hotkey_method = NULL; // static void dispatchHotKeyPressed()
static jmethodID g_hotkey_failed_method = NULL; // static void dispatchRegisterFailed(int)
static EventHandlerRef g_hotkey_handler = NULL;
static EventHotKeyRef g_hotkey_ref = NULL;
static UInt32 g_hotkey_id = 1;

static void run_on_main_sync(void (^block)(void)) {
    if ([NSThread isMainThread]) {
        block();
    } else {
        dispatch_sync(dispatch_get_main_queue(), block);
    }
}

/**
 * Carbon work must land on the main thread, but the caller must never *wait* for it.
 *
 * The AWT EDT and the AppKit main thread deadlock trivially: AppKit calls into Java on the
 * main thread for accessibility (postFocusChanged: -> getJavaRole -> CAccessibility, which
 * does LWCToolkit.invokeAndWait), so the main thread routinely blocks on the EDT. Opening
 * the voice overlay does exactly that, and the same Compose effect unregisters the hotkey
 * from the EDT — a dispatch_sync there closes the cycle and hangs the whole app.
 * Register/unregister have no return value the caller needs synchronously, so they go async.
 */
static void run_on_main_async(void (^block)(void)) {
    if ([NSThread isMainThread]) {
        block();
    } else {
        dispatch_async(dispatch_get_main_queue(), block);
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

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    g_jvm = vm;
    return JNI_VERSION_1_8;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM *vm, void *reserved) {
    (void)vm;
    (void)reserved;
    g_jvm = NULL;
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
    // authorizationStatusForMediaType is safe off the main thread — avoid dispatch_sync
    // when the answer is already known (holding Java locks across sync-to-main deadlocks
    // the AWT/Compose EDT if it also needs those locks).
    AVAuthorizationStatus status = [AVCaptureDevice authorizationStatusForMediaType:AVMediaTypeAudio];
    if (status != AVAuthorizationStatusNotDetermined) {
        return status_code(status);
    }
    __block jint result = -1;
    run_on_main_sync(^{
        AVAuthorizationStatus again = [AVCaptureDevice authorizationStatusForMediaType:AVMediaTypeAudio];
        if (again != AVAuthorizationStatusNotDetermined) {
            result = status_code(again);
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

static void andy_voice_debug_log(const char *message) {
    @autoreleasepool {
        NSString *home = NSHomeDirectory();
        if (home == nil) return;
        NSString *path = [home stringByAppendingPathComponent:@".andy/voice/debug.log"];
        [[NSFileManager defaultManager] createDirectoryAtPath:[path stringByDeletingLastPathComponent]
                                  withIntermediateDirectories:YES
                                                   attributes:nil
                                                        error:nil];
        NSString *line = [NSString stringWithFormat:@"%@ %s\n",
                          [NSDate date].description, message];
        NSFileHandle *handle = [NSFileHandle fileHandleForWritingAtPath:path];
        if (handle == nil) {
            [line writeToFile:path atomically:YES encoding:NSUTF8StringEncoding error:nil];
            return;
        }
        [handle seekToEndOfFile];
        [handle writeData:[line dataUsingEncoding:NSUTF8StringEncoding]];
        [handle closeFile];
    }
}

static void andy_hotkey_invoke_java(void) {
    if (g_jvm == NULL || g_bridge_class == NULL || g_hotkey_method == NULL) {
        NSLog(@"andy-voice: hotkey pressed but Java bridge not ready (jvm=%p class=%p method=%p)",
              g_jvm, g_bridge_class, g_hotkey_method);
        andy_voice_debug_log("Carbon hotkey: Java bridge not ready");
        return;
    }
    JNIEnv *env = NULL;
    jint getEnv = (*g_jvm)->GetEnv(g_jvm, (void **)&env, JNI_VERSION_1_8);
    if (getEnv == JNI_EDETACHED) {
        if ((*g_jvm)->AttachCurrentThread(g_jvm, (void **)&env, NULL) != JNI_OK || env == NULL) {
            NSLog(@"andy-voice: AttachCurrentThread failed for hotkey callback");
            andy_voice_debug_log("Carbon hotkey: AttachCurrentThread failed");
            return;
        }
    } else if (getEnv != JNI_OK || env == NULL) {
        NSLog(@"andy-voice: GetEnv failed for hotkey callback (%d)", (int)getEnv);
        andy_voice_debug_log("Carbon hotkey: GetEnv failed");
        return;
    }
    (*env)->CallStaticVoidMethod(env, g_bridge_class, g_hotkey_method);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
        andy_voice_debug_log("Carbon hotkey: Java exception in dispatchHotKeyPressed");
    } else {
        andy_voice_debug_log("Carbon hotkey: dispatched to Java");
    }
}

/** Must run on the main thread — clears the bridge refs installed by nativeRegisterHotKey. */
static void andy_hotkey_release_bridge(void) {
    if (g_bridge_class != NULL && g_jvm != NULL) {
        JNIEnv *mainEnv = NULL;
        if ((*g_jvm)->GetEnv(g_jvm, (void **)&mainEnv, JNI_VERSION_1_8) == JNI_OK && mainEnv != NULL) {
            (*mainEnv)->DeleteGlobalRef(mainEnv, g_bridge_class);
        }
    }
    g_bridge_class = NULL;
    g_hotkey_method = NULL;
    g_hotkey_failed_method = NULL;
}

/**
 * Registration now completes asynchronously, so OSStatus failures are pushed back to Kotlin
 * instead of returned. Takes explicit refs so the caller can report before tearing them down.
 */
static void andy_hotkey_report_failure(jclass bridgeClass, jmethodID failedMethod, jint status) {
    if (g_jvm == NULL || bridgeClass == NULL || failedMethod == NULL) return;
    JNIEnv *env = NULL;
    if ((*g_jvm)->GetEnv(g_jvm, (void **)&env, JNI_VERSION_1_8) != JNI_OK || env == NULL) return;
    (*env)->CallStaticVoidMethod(env, bridgeClass, failedMethod, status);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
    }
}

static OSStatus andy_hotkey_handler(
    EventHandlerCallRef nextHandler,
    EventRef event,
    void *userData
) {
    (void)nextHandler;
    (void)event;
    (void)userData;
    NSLog(@"andy-voice: Carbon hotkey pressed");
    andy_voice_debug_log("Carbon hotkey: pressed (handler entered)");
    // Defer off the Carbon stack. Calling into the JVM/AWT re-entrantly from the
    // hotkey handler has been observed to silently drop the callback on Compose Desktop.
    dispatch_async(dispatch_get_main_queue(), ^{
        andy_hotkey_invoke_java();
    });
    return noErr;
}

static OSStatus ensure_hotkey_handler(void) {
    if (g_hotkey_handler != NULL) return noErr;
    EventTypeSpec spec = { kEventClassKeyboard, kEventHotKeyPressed };
    // jkeymaster and other working Carbon hotkey bridges install on the dispatcher
    // target — GetApplicationEventTarget() can succeed at register time but never
    // deliver kEventHotKeyPressed into a Java/AWT process.
    OSStatus status = InstallEventHandler(
        GetEventDispatcherTarget(),
        andy_hotkey_handler,
        1,
        &spec,
        NULL,
        &g_hotkey_handler
    );
    if (status != noErr) {
        NSLog(@"andy-voice: InstallEventHandler failed status=%d", (int)status);
        g_hotkey_handler = NULL;
    }
    return status;
}

JNIEXPORT jint JNICALL
Java_app_andy_desktop_service_voice_MacOsGlobalHotKeyBridge_nativeRegisterHotKey(
    JNIEnv *env,
    jclass cls,
    jint virtualKeyCode,
    jint carbonModifiers
) {
    (void)cls;
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
    }

    // Resolve a static Java callback once — more reliable than an instance method on a
    // Kotlin object when the Carbon handler fires on the AppKit thread.
    jclass localClass = (*env)->FindClass(
        env,
        "app/andy/desktop/service/voice/MacOsGlobalHotKeyBridge"
    );
    if (localClass == NULL) {
        NSLog(@"andy-voice: FindClass MacOsGlobalHotKeyBridge failed");
        return -1;
    }
    jmethodID method = (*env)->GetStaticMethodID(
        env,
        localClass,
        "dispatchHotKeyPressed",
        "()V"
    );
    if (method == NULL) {
        NSLog(@"andy-voice: GetStaticMethodID dispatchHotKeyPressed failed");
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        return -1;
    }
    jmethodID failedMethod = (*env)->GetStaticMethodID(
        env,
        localClass,
        "dispatchRegisterFailed",
        "(I)V"
    );
    if (failedMethod == NULL) {
        NSLog(@"andy-voice: GetStaticMethodID dispatchRegisterFailed failed");
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    }
    jobject globalClass = (*env)->NewGlobalRef(env, localClass);
    if (globalClass == NULL) return -1;

    // Allocated up front so the handle can be returned without waiting on the main queue.
    jint handle = (jint)__atomic_fetch_add(&g_hotkey_id, 1, __ATOMIC_RELAXED);
    jobject retainedClass = globalClass;
    jmethodID retainedMethod = method;
    jmethodID retainedFailedMethod = failedMethod;
    jint vKey = virtualKeyCode;
    jint mods = carbonModifiers;

    run_on_main_async(^{
        if (g_hotkey_ref != NULL) {
            UnregisterEventHotKey(g_hotkey_ref);
            g_hotkey_ref = NULL;
        }
        andy_hotkey_release_bridge();

        g_bridge_class = (jclass)retainedClass;
        g_hotkey_method = retainedMethod;
        g_hotkey_failed_method = retainedFailedMethod;

        OSStatus handlerStatus = ensure_hotkey_handler();
        if (handlerStatus != noErr) {
            andy_hotkey_report_failure(g_bridge_class, g_hotkey_failed_method, -1);
            andy_hotkey_release_bridge();
            return;
        }

        EventHotKeyID hotKeyId;
        hotKeyId.signature = 'ANDY';
        hotKeyId.id = (UInt32)handle;
        OSStatus status = RegisterEventHotKey(
            (UInt32)vKey,
            (UInt32)mods,
            hotKeyId,
            GetEventDispatcherTarget(),
            0,
            &g_hotkey_ref
        );
        NSLog(
            @"andy-voice: RegisterEventHotKey status=%d key=0x%X mods=0x%X id=%u",
            (int)status,
            (unsigned)vKey,
            (unsigned)mods,
            (unsigned)hotKeyId.id
        );
        if (status != noErr) {
            g_hotkey_ref = NULL;
            // Encode OSStatus as negative so Kotlin can surface it (avoid colliding with -1).
            jint reported = status > 0 ? -(jint)status : (status == 0 ? -1 : (jint)status);
            andy_hotkey_report_failure(g_bridge_class, g_hotkey_failed_method, reported);
            andy_hotkey_release_bridge();
        }
    });
    return handle;
}

JNIEXPORT void JNICALL
Java_app_andy_desktop_service_voice_MacOsGlobalHotKeyBridge_nativeUnregisterHotKey(
    JNIEnv *env,
    jclass cls,
    jint handle
) {
    (void)env;
    (void)cls;
    (void)handle;
    run_on_main_async(^{
        if (g_hotkey_ref != NULL) {
            UnregisterEventHotKey(g_hotkey_ref);
            g_hotkey_ref = NULL;
        }
        andy_hotkey_release_bridge();
    });
}

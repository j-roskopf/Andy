#include <jni.h>
#import <AppKit/AppKit.h>
#import <pthread.h>

#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdeprecated-declarations"

static JavaVM *andy_jvm = NULL;
static jobject andy_callback = NULL; // global ref to MacOsNotificationBridge
static jmethodID andy_on_activated = NULL;
static pthread_mutex_t andy_lock = PTHREAD_MUTEX_INITIALIZER;

@interface AndyNotificationDelegate : NSObject <NSUserNotificationCenterDelegate>
@end

@implementation AndyNotificationDelegate

- (BOOL)userNotificationCenter:(NSUserNotificationCenter *)center
     shouldPresentNotification:(NSUserNotification *)notification {
    // Show banners even while Andy is frontmost (Settings → Always).
    return YES;
}

- (void)userNotificationCenter:(NSUserNotificationCenter *)center
       didActivateNotification:(NSUserNotification *)notification {
    NSDictionary *info = notification.userInfo;
    NSString *taskId = info[@"taskId"] ?: @"";
    NSString *projectId = info[@"projectId"];

    pthread_mutex_lock(&andy_lock);
    JavaVM *jvm = andy_jvm;
    jobject callback = andy_callback;
    jmethodID method = andy_on_activated;
    pthread_mutex_unlock(&andy_lock);
    if (jvm == NULL || callback == NULL || method == NULL) return;

    JNIEnv *env = NULL;
    jint getEnv = (*jvm)->GetEnv(jvm, (void **)&env, JNI_VERSION_1_8);
    bool attached = false;
    if (getEnv == JNI_EDETACHED) {
        if ((*jvm)->AttachCurrentThread(jvm, (void **)&env, NULL) != JNI_OK || env == NULL) return;
        attached = true;
    } else if (getEnv != JNI_OK || env == NULL) {
        return;
    }

    jstring jTaskId = (*env)->NewStringUTF(env, taskId.UTF8String);
    jstring jProjectId = projectId != nil ? (*env)->NewStringUTF(env, projectId.UTF8String) : NULL;
    (*env)->CallVoidMethod(env, callback, method, jTaskId, jProjectId);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    if (jTaskId != NULL) (*env)->DeleteLocalRef(env, jTaskId);
    if (jProjectId != NULL) (*env)->DeleteLocalRef(env, jProjectId);
    if (attached) (*jvm)->DetachCurrentThread(jvm);
}

@end

static AndyNotificationDelegate *andy_delegate = nil;

static NSString *AndyNSString(JNIEnv *env, jstring value) {
    if (value == NULL) return @"";
    const char *utf = (*env)->GetStringUTFChars(env, value, NULL);
    if (utf == NULL) return @"";
    NSString *result = [NSString stringWithUTF8String:utf];
    (*env)->ReleaseStringUTFChars(env, value, utf);
    return result ?: @"";
}

JNIEXPORT jboolean JNICALL
Java_app_andy_desktop_service_MacOsNotificationBridge_nativeInstall(
    JNIEnv *env, jobject bridge
) {
    if ((*env)->GetJavaVM(env, &andy_jvm) != JNI_OK) return JNI_FALSE;
    jclass cls = (*env)->GetObjectClass(env, bridge);
    jmethodID method = (*env)->GetMethodID(
        env, cls, "onNotificationActivated", "(Ljava/lang/String;Ljava/lang/String;)V"
    );
    if (method == NULL) {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        return JNI_FALSE;
    }

    pthread_mutex_lock(&andy_lock);
    if (andy_callback != NULL) {
        (*env)->DeleteGlobalRef(env, andy_callback);
        andy_callback = NULL;
    }
    andy_callback = (*env)->NewGlobalRef(env, bridge);
    andy_on_activated = method;
    pthread_mutex_unlock(&andy_lock);

    dispatch_async(dispatch_get_main_queue(), ^{
        if (andy_delegate == nil) {
            andy_delegate = [AndyNotificationDelegate new];
        }
        [NSUserNotificationCenter defaultUserNotificationCenter].delegate = andy_delegate;
    });
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_app_andy_desktop_service_MacOsNotificationBridge_nativeShow(
    JNIEnv *env, jobject bridge,
    jstring jTitle, jstring jSubtitle, jstring jBody,
    jstring jTaskId, jstring jProjectId
) {
    (void)bridge;
    NSString *title = AndyNSString(env, jTitle);
    NSString *subtitle = AndyNSString(env, jSubtitle);
    NSString *body = AndyNSString(env, jBody);
    NSString *taskId = AndyNSString(env, jTaskId);
    NSString *projectId = jProjectId != NULL ? AndyNSString(env, jProjectId) : nil;

    dispatch_async(dispatch_get_main_queue(), ^{
        if (andy_delegate == nil) {
            andy_delegate = [AndyNotificationDelegate new];
            [NSUserNotificationCenter defaultUserNotificationCenter].delegate = andy_delegate;
        }
        NSUserNotification *notification = [NSUserNotification new];
        notification.title = title;
        notification.subtitle = subtitle.length > 0 ? subtitle : nil;
        notification.informativeText = body;
        // Andy plays its own preference-backed sound.
        notification.soundName = nil;
        NSMutableDictionary *info = [NSMutableDictionary dictionary];
        info[@"taskId"] = taskId;
        if (projectId != nil && projectId.length > 0) {
            info[@"projectId"] = projectId;
        }
        notification.userInfo = info;
        [[NSUserNotificationCenter defaultUserNotificationCenter] deliverNotification:notification];
    });
}

#pragma clang diagnostic pop

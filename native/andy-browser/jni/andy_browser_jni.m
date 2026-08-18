#include <jni.h>
#import <AppKit/AppKit.h>
#import <QuartzCore/QuartzCore.h>
#import <WebKit/WebKit.h>
#import <pthread.h>

/**
 * Borderless WKWebView overlay that tracks a Compose Canvas (same parenting pattern as
 * andy-mirror's Metal presenter). CEF/JOGL cannot share AppKit's main thread with AWT on
 * macOS; system WebKit can — and it supports WebAssembly.
 */

static JavaVM *andy_jvm = NULL;
static jobject andy_listener = NULL;
static jmethodID andy_on_nav_state = NULL;
static pthread_mutex_t andy_lock = PTHREAD_MUTEX_INITIALIZER;

static NSWindow *andy_browser_window = NULL;
static WKWebView *andy_web_view = NULL;
static bool andy_browser_suppressed = false;
static CGFloat andy_bottom_radius = 18.0;
static bool andy_has_geometry = false;

static jint andy_pending_x = 0;
static jint andy_pending_y = 0;
static jint andy_pending_w = 0;
static jint andy_pending_h = 0;
static jdouble andy_pending_scale = 1.0;
static jint andy_pending_parent = 0;
static bool andy_geometry_scheduled = false;

/**
 * Borderless NSWindows return NO from canBecomeKeyWindow by default, so WKWebView never
 * receives keyboard focus and page text fields / Andy's address bar fight a dead first-responder
 * chain. Mirror overlays intentionally ignore mouse (Compose owns input); the browser must
 * accept both mouse and key.
 */
@interface AndyBrowserWindow : NSWindow
@end

@implementation AndyBrowserWindow
- (BOOL)canBecomeKeyWindow {
    return YES;
}
- (BOOL)canBecomeMainWindow {
    return NO;
}
- (BOOL)acceptsFirstMouse:(NSEvent *)event {
    (void)event;
    return YES;
}
@end

@interface AndyBrowserDelegate : NSObject <WKNavigationDelegate>
@end

static AndyBrowserDelegate *andy_delegate = nil;

/** Desktop Chrome UA so sites (Google homepage especially) don't serve the Safari/WebKit skin. */
static NSString *AndyBrowserUserAgent(void) {
    return @"Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
           @"AppleWebKit/537.36 (KHTML, like Gecko) "
           @"Chrome/131.0.0.0 Safari/537.36";
}

static bool andy_is_browser_window(NSWindow *window) {
    return window != nil && window == andy_browser_window;
}

static NSString *AndyNSString(JNIEnv *env, jstring value) {
    if (value == NULL) return @"";
    const char *utf = (*env)->GetStringUTFChars(env, value, NULL);
    if (utf == NULL) return @"";
    NSString *result = [NSString stringWithUTF8String:utf];
    (*env)->ReleaseStringUTFChars(env, value, utf);
    return result ?: @"";
}

static void
andy_emit_nav_state(void) {
    if (!andy_web_view) return;
    pthread_mutex_lock(&andy_lock);
    JavaVM *jvm = andy_jvm;
    jobject listener = andy_listener;
    jmethodID method = andy_on_nav_state;
    pthread_mutex_unlock(&andy_lock);
    if (jvm == NULL || listener == NULL || method == NULL) return;

    NSString *title = andy_web_view.title ?: @"";
    NSString *url = andy_web_view.URL.absoluteString ?: @"";
    const jboolean canBack = andy_web_view.canGoBack ? JNI_TRUE : JNI_FALSE;
    const jboolean canForward = andy_web_view.canGoForward ? JNI_TRUE : JNI_FALSE;
    const jboolean loading = andy_web_view.loading ? JNI_TRUE : JNI_FALSE;

    JNIEnv *env = NULL;
    jint getEnv = (*jvm)->GetEnv(jvm, (void **)&env, JNI_VERSION_1_8);
    bool attached = false;
    if (getEnv == JNI_EDETACHED) {
        if ((*jvm)->AttachCurrentThread(jvm, (void **)&env, NULL) != JNI_OK || env == NULL) return;
        attached = true;
    } else if (getEnv != JNI_OK || env == NULL) {
        return;
    }

    jstring jTitle = (*env)->NewStringUTF(env, title.UTF8String);
    jstring jUrl = (*env)->NewStringUTF(env, url.UTF8String);
    (*env)->CallVoidMethod(env, listener, method, jTitle, jUrl, canBack, canForward, loading);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    if (jTitle != NULL) (*env)->DeleteLocalRef(env, jTitle);
    if (jUrl != NULL) (*env)->DeleteLocalRef(env, jUrl);
    if (attached) (*jvm)->DetachCurrentThread(jvm);
}

static void
andy_apply_bottom_clip(void) {
    if (!andy_web_view) return;
    const CGFloat w = andy_web_view.bounds.size.width;
    const CGFloat h = andy_web_view.bounds.size.height;
    if (w < 1.0 || h < 1.0) return;
    const CGFloat r = MAX(0.0, MIN(andy_bottom_radius, MIN(w, h) / 2.0));
    andy_web_view.wantsLayer = YES;
    if (r <= 0.0) {
        andy_web_view.layer.mask = nil;
        return;
    }
    // CAShapeLayer.mask is layer-space (origin top-left, y down), not AppKit view
    // space. Keep the top edge square so the page meets the address bar flush;
    // round only the visual bottom to match Andy's dock sheet.
    CGMutablePathRef path = CGPathCreateMutable();
    CGPathMoveToPoint(path, NULL, 0, 0);
    CGPathAddLineToPoint(path, NULL, w, 0);
    CGPathAddLineToPoint(path, NULL, w, h - r);
    CGPathAddArcToPoint(path, NULL, w, h, w - r, h, r);
    CGPathAddLineToPoint(path, NULL, r, h);
    CGPathAddArcToPoint(path, NULL, 0, h, 0, h - r, r);
    CGPathAddLineToPoint(path, NULL, 0, 0);
    CGPathCloseSubpath(path);
    CAShapeLayer *mask = [CAShapeLayer layer];
    mask.path = path;
    mask.frame = andy_web_view.bounds;
    CGPathRelease(path);
    andy_web_view.layer.mask = mask;
}

static void
andy_apply_frame(jint awt_x, jint awt_y, jint width, jint height, jdouble scale,
                 jint parent_window_number) {
    if (!andy_browser_window || !andy_web_view) return;
    if (andy_browser_suppressed) {
        if (andy_browser_window.isVisible) {
            [andy_browser_window orderOut:nil];
        }
        return;
    }
    // Reject empty / placeholder sizes so we never orderFront an 800×600 overlay that
    // covers Andy's dock tab strip and address bar before the Compose tracker lays out.
    if (width < 2 || height < 2) {
        if (andy_browser_window.isVisible) {
            [andy_browser_window orderOut:nil];
        }
        return;
    }
    const CGFloat w = (CGFloat) width;
    const CGFloat h = (CGFloat) height;
    NSScreen *primary = NSScreen.screens.firstObject;
    const CGFloat screen_height = primary ? NSMaxY(primary.frame) : h;
    const NSRect frame = NSMakeRect(awt_x, screen_height - awt_y - h, w, h);

    NSWindow *under = nil;
    if (parent_window_number > 0) {
        under = [NSApp windowWithWindowNumber:parent_window_number];
    }
    if (!under || andy_is_browser_window(under)) {
        // Walk top-to-bottom like andy-mirror: never parent under ourselves.
        NSPoint probe = NSMakePoint(NSMidX(frame), NSMidY(frame));
        NSInteger window_number = [NSWindow windowNumberAtPoint:probe belowWindowWithWindowNumber:0];
        NSWindow *candidate = [NSApp windowWithWindowNumber:window_number];
        while (candidate && andy_is_browser_window(candidate)) {
            window_number = [NSWindow windowNumberAtPoint:probe belowWindowWithWindowNumber:window_number];
            if (window_number == 0) {
                candidate = nil;
                break;
            }
            candidate = [NSApp windowWithWindowNumber:window_number];
        }
        under = candidate;
    }
    NSWindow *parent = andy_browser_window.parentWindow;
    if (under && under != andy_browser_window && parent != under) {
        if (parent) {
            [parent removeChildWindow:andy_browser_window];
        }
        [under addChildWindow:andy_browser_window ordered:NSWindowAbove];
    }
    if (!NSEqualRects(andy_browser_window.frame, frame)) {
        [andy_browser_window setFrame:frame display:NO];
        andy_web_view.frame = andy_browser_window.contentView.bounds;
        andy_apply_bottom_clip();
    }
    (void)scale;
    andy_has_geometry = true;
    if (!andy_browser_window.isVisible) {
        [andy_browser_window orderFront:nil];
    }
}

static void
andy_schedule_frame(jint awt_x, jint awt_y, jint width, jint height, jdouble scale,
                    jint parent_window_number) {
    andy_pending_x = awt_x;
    andy_pending_y = awt_y;
    andy_pending_w = width;
    andy_pending_h = height;
    andy_pending_scale = scale;
    andy_pending_parent = parent_window_number;
    if (andy_geometry_scheduled) return;
    andy_geometry_scheduled = true;
    dispatch_async(dispatch_get_main_queue(), ^{
        andy_geometry_scheduled = false;
        andy_apply_frame(andy_pending_x, andy_pending_y, andy_pending_w, andy_pending_h,
                         andy_pending_scale, andy_pending_parent);
    });
}

static bool
andy_open_window(void) {
    if (!NSApp) return false;
    __block bool opened = false;
    void (^open)(void) = ^{
        if (andy_browser_window && andy_web_view) {
            opened = true;
            return;
        }
        if (!andy_delegate) {
            andy_delegate = [[AndyBrowserDelegate alloc] init];
        }
        const NSRect content_rect = NSMakeRect(0, 0, 800, 600);
        andy_browser_window = [[AndyBrowserWindow alloc] initWithContentRect:content_rect
                                                                   styleMask:NSWindowStyleMaskBorderless
                                                                     backing:NSBackingStoreBuffered
                                                                       defer:NO];
        if (!andy_browser_window) return;
        andy_browser_window.opaque = YES;
        andy_browser_window.backgroundColor = [NSColor colorWithCalibratedWhite:0.09 alpha:1.0];
        andy_browser_window.hasShadow = NO;
        andy_browser_window.ignoresMouseEvents = NO;
        andy_browser_window.level = NSNormalWindowLevel;
        andy_browser_window.collectionBehavior = NSWindowCollectionBehaviorMoveToActiveSpace |
            NSWindowCollectionBehaviorFullScreenAuxiliary;
        // Hidden until the first real Compose-tracker frame — ordering front an 800×600
        // placeholder covers the dock tab strip and eats clicks.
        andy_has_geometry = false;

        WKWebViewConfiguration *config = [[WKWebViewConfiguration alloc] init];
        config.preferences.javaScriptCanOpenWindowsAutomatically = NO;
        config.websiteDataStore = [WKWebsiteDataStore defaultDataStore];
        andy_web_view = [[WKWebView alloc] initWithFrame:content_rect configuration:config];
        andy_web_view.autoresizingMask = NSViewWidthSizable | NSViewHeightSizable;
        andy_web_view.navigationDelegate = andy_delegate;
        andy_web_view.customUserAgent = AndyBrowserUserAgent();
        [andy_web_view addObserver:andy_delegate forKeyPath:@"title" options:NSKeyValueObservingOptionNew context:NULL];
        [andy_web_view addObserver:andy_delegate forKeyPath:@"URL" options:NSKeyValueObservingOptionNew context:NULL];
        [andy_web_view addObserver:andy_delegate forKeyPath:@"loading" options:NSKeyValueObservingOptionNew context:NULL];
        [andy_web_view addObserver:andy_delegate forKeyPath:@"canGoBack" options:NSKeyValueObservingOptionNew context:NULL];
        [andy_web_view addObserver:andy_delegate forKeyPath:@"canGoForward" options:NSKeyValueObservingOptionNew context:NULL];

        andy_browser_window.contentView = andy_web_view;
        andy_browser_suppressed = false;
        andy_apply_bottom_clip();
        opened = true;
    };
    if ([NSThread isMainThread]) {
        open();
    } else {
        dispatch_sync(dispatch_get_main_queue(), open);
    }
    return opened;
}

@implementation AndyBrowserDelegate

- (void)observeValueForKeyPath:(NSString *)keyPath
                      ofObject:(id)object
                        change:(NSDictionary<NSKeyValueChangeKey,id> *)change
                       context:(void *)context {
    (void)keyPath;
    (void)object;
    (void)change;
    (void)context;
    andy_emit_nav_state();
}

- (void)webView:(WKWebView *)webView didStartProvisionalNavigation:(WKNavigation *)navigation {
    (void)webView;
    (void)navigation;
    andy_emit_nav_state();
}

- (void)webView:(WKWebView *)webView didFinishNavigation:(WKNavigation *)navigation {
    (void)webView;
    (void)navigation;
    andy_emit_nav_state();
}

- (void)webView:(WKWebView *)webView didFailNavigation:(WKNavigation *)navigation withError:(NSError *)error {
    (void)webView;
    (void)navigation;
    (void)error;
    andy_emit_nav_state();
}

- (void)webView:(WKWebView *)webView
didFailProvisionalNavigation:(WKNavigation *)navigation
      withError:(NSError *)error {
    (void)webView;
    (void)navigation;
    (void)error;
    andy_emit_nav_state();
}

@end

JNIEXPORT jboolean JNICALL
Java_app_andy_desktop_browser_WkBrowserJni_nativeInstall(
    JNIEnv *env, jobject bridge
) {
    if ((*env)->GetJavaVM(env, &andy_jvm) != JNI_OK) return JNI_FALSE;
    jclass cls = (*env)->GetObjectClass(env, bridge);
    jmethodID method = (*env)->GetMethodID(
        env, cls, "onNavStateFromNative",
        "(Ljava/lang/String;Ljava/lang/String;ZZZ)V"
    );
    if (method == NULL) {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        return JNI_FALSE;
    }
    pthread_mutex_lock(&andy_lock);
    if (andy_listener != NULL) {
        (*env)->DeleteGlobalRef(env, andy_listener);
        andy_listener = NULL;
    }
    andy_listener = (*env)->NewGlobalRef(env, bridge);
    andy_on_nav_state = method;
    pthread_mutex_unlock(&andy_lock);

    dispatch_sync(dispatch_get_main_queue(), ^{
        if (!andy_delegate) {
            andy_delegate = [[AndyBrowserDelegate alloc] init];
        }
    });
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_app_andy_desktop_browser_WkBrowserJni_nativeOpen(JNIEnv *env, jobject bridge) {
    (void)env;
    (void)bridge;
    return andy_open_window() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_app_andy_desktop_browser_WkBrowserJni_nativeSetBottomCornerRadius(
    JNIEnv *env, jobject bridge, jfloat radiusPx
) {
    (void)env;
    (void)bridge;
    andy_bottom_radius = MAX(0.0f, radiusPx);
    dispatch_async(dispatch_get_main_queue(), ^{
        andy_apply_bottom_clip();
    });
}

JNIEXPORT void JNICALL
Java_app_andy_desktop_browser_WkBrowserJni_nativeUpdateFrame(
    JNIEnv *env, jobject bridge,
    jint x, jint y, jint width, jint height, jdouble scale, jint parentWindowNumber
) {
    (void)env;
    (void)bridge;
    andy_schedule_frame(x, y, width, height, scale, parentWindowNumber);
}

JNIEXPORT void JNICALL
Java_app_andy_desktop_browser_WkBrowserJni_nativeSetVisible(
    JNIEnv *env, jobject bridge, jboolean visible
) {
    (void)env;
    (void)bridge;
    andy_browser_suppressed = !visible;
    void (^apply)(void) = ^{
        if (!andy_browser_window) return;
        if (visible) {
            if (andy_has_geometry && !andy_browser_window.isVisible) {
                [andy_browser_window orderFront:nil];
            }
        } else if (andy_browser_window.isVisible) {
            [andy_browser_window orderOut:nil];
        }
    };
    if ([NSThread isMainThread]) {
        apply();
    } else {
        dispatch_async(dispatch_get_main_queue(), apply);
    }
}

JNIEXPORT void JNICALL
Java_app_andy_desktop_browser_WkBrowserJni_nativeLoadUrl(
    JNIEnv *env, jobject bridge, jstring url
) {
    (void)bridge;
    NSString *target = AndyNSString(env, url);
    dispatch_async(dispatch_get_main_queue(), ^{
        if (!andy_web_view) return;
        NSURL *nsUrl = [NSURL URLWithString:target];
        if (!nsUrl) return;
        if (andy_web_view.customUserAgent.length == 0) {
            andy_web_view.customUserAgent = AndyBrowserUserAgent();
        }
        NSString *current = andy_web_view.URL.absoluteString;
        if (current.length > 0 && [current isEqualToString:target]) return;
        [andy_web_view loadRequest:[NSURLRequest requestWithURL:nsUrl]];
    });
}

JNIEXPORT void JNICALL
Java_app_andy_desktop_browser_WkBrowserJni_nativeFocus(
    JNIEnv *env, jobject bridge
) {
    (void)env;
    (void)bridge;
    dispatch_async(dispatch_get_main_queue(), ^{
        if (!andy_browser_window || !andy_web_view || !andy_has_geometry || andy_browser_suppressed) {
            return;
        }
        [andy_browser_window makeKeyAndOrderFront:nil];
        [andy_browser_window makeFirstResponder:andy_web_view];
    });
}

JNIEXPORT void JNICALL
Java_app_andy_desktop_browser_WkBrowserJni_nativeResignKey(
    JNIEnv *env, jobject bridge
) {
    (void)env;
    (void)bridge;
    dispatch_async(dispatch_get_main_queue(), ^{
        if (!andy_browser_window || !andy_browser_window.isKeyWindow) return;
        NSWindow *parent = andy_browser_window.parentWindow;
        if (parent) {
            [parent makeKeyAndOrderFront:nil];
        } else {
            [andy_browser_window resignKeyWindow];
        }
    });
}

JNIEXPORT void JNICALL
Java_app_andy_desktop_browser_WkBrowserJni_nativeGoBack(JNIEnv *env, jobject bridge) {
    (void)env;
    (void)bridge;
    dispatch_async(dispatch_get_main_queue(), ^{
        if (andy_web_view.canGoBack) [andy_web_view goBack];
    });
}

JNIEXPORT void JNICALL
Java_app_andy_desktop_browser_WkBrowserJni_nativeGoForward(JNIEnv *env, jobject bridge) {
    (void)env;
    (void)bridge;
    dispatch_async(dispatch_get_main_queue(), ^{
        if (andy_web_view.canGoForward) [andy_web_view goForward];
    });
}

JNIEXPORT void JNICALL
Java_app_andy_desktop_browser_WkBrowserJni_nativeReload(JNIEnv *env, jobject bridge) {
    (void)env;
    (void)bridge;
    dispatch_async(dispatch_get_main_queue(), ^{
        [andy_web_view reload];
    });
}

JNIEXPORT void JNICALL
Java_app_andy_desktop_browser_WkBrowserJni_nativeClose(JNIEnv *env, jobject bridge) {
    (void)env;
    (void)bridge;
    void (^close)(void) = ^{
        if (andy_web_view) {
            @try {
                [andy_web_view removeObserver:andy_delegate forKeyPath:@"title"];
                [andy_web_view removeObserver:andy_delegate forKeyPath:@"URL"];
                [andy_web_view removeObserver:andy_delegate forKeyPath:@"loading"];
                [andy_web_view removeObserver:andy_delegate forKeyPath:@"canGoBack"];
                [andy_web_view removeObserver:andy_delegate forKeyPath:@"canGoForward"];
            } @catch (NSException *ex) {
                (void)ex;
            }
            andy_web_view.navigationDelegate = nil;
            andy_web_view = nil;
        }
        if (andy_browser_window) {
            NSWindow *parent = andy_browser_window.parentWindow;
            if (parent) {
                [parent removeChildWindow:andy_browser_window];
            }
            [andy_browser_window orderOut:nil];
            andy_browser_window = nil;
        }
        andy_browser_suppressed = false;
        andy_has_geometry = false;
    };
    if ([NSThread isMainThread]) {
        close();
    } else {
        dispatch_sync(dispatch_get_main_queue(), close);
    }
}

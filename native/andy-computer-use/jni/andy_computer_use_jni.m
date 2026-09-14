/*
 * macOS Accessibility + CGEvent + capture bridge for Andy computer-use.
 *
 * Built as andy-computer-use-jni.dylib and loaded by MacOsComputerUseNative.
 */

#include <jni.h>
#include <unistd.h>
#include <stdint.h>
#include <string.h>
#import <AppKit/AppKit.h>
#import <ApplicationServices/ApplicationServices.h>
#import <CoreGraphics/CoreGraphics.h>
#import <Foundation/Foundation.h>

/** Private CGEventSource user-data tag — Andy windows reject events carrying this. */
static const int64_t kAndyEventTag = 0x414E445900000001LL; /* "ANDY" + 1 */

static JavaVM *g_jvm = NULL;
static NSMutableDictionary<NSString *, id> *g_elements = nil; /* id -> AXUIElement */
static CGEventSourceRef g_event_source = NULL;
static id g_local_monitor = nil;

static void ensure_globals(void) {
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        g_elements = [NSMutableDictionary dictionary];
        g_event_source = CGEventSourceCreate(kCGEventSourceStatePrivate);
        if (g_event_source != NULL) {
            CGEventSourceSetUserData(g_event_source, kAndyEventTag);
        }
        /* Reject our own injected events so the agent cannot drive Andy's UI. */
        g_local_monitor = [NSEvent addLocalMonitorForEventsMatchingMask:NSEventMaskAny
                                                                handler:^NSEvent *(NSEvent *event) {
            CGEventRef cge = event.CGEvent;
            if (cge == NULL) return event;
            int64_t tag = CGEventGetIntegerValueField(cge, kCGEventSourceUserData);
            if (tag == kAndyEventTag) {
                return nil; /* swallow */
            }
            return event;
        }];
    });
}

static NSString *ax_str(AXUIElementRef el, CFStringRef attr) {
    CFTypeRef value = NULL;
    if (AXUIElementCopyAttributeValue(el, attr, &value) != kAXErrorSuccess || value == NULL) {
        return nil;
    }
    NSString *result = nil;
    if (CFGetTypeID(value) == CFStringGetTypeID()) {
        result = (__bridge NSString *)value;
    } else if (CFGetTypeID(value) == CFNumberGetTypeID()) {
        result = [(__bridge NSNumber *)value stringValue];
    }
    if (value) CFRelease(value);
    return result;
}

static BOOL ax_frame(AXUIElementRef el, CGRect *out) {
    CFTypeRef value = NULL;
    if (AXUIElementCopyAttributeValue(el, CFSTR("AXFrame"), &value) != kAXErrorSuccess || value == NULL) {
        return NO;
    }
    BOOL ok = NO;
    if (CFGetTypeID(value) == AXValueGetTypeID()) {
        ok = AXValueGetValue((AXValueRef)value, kAXValueTypeCGRect, out);
    }
    CFRelease(value);
    return ok;
}

static NSArray<NSString *> *ax_actions(AXUIElementRef el) {
    CFArrayRef names = NULL;
    if (AXUIElementCopyActionNames(el, &names) != kAXErrorSuccess || names == NULL) {
        return @[];
    }
    NSArray *result = CFBridgingRelease(names);
    return result ?: @[];
}

static NSArray *ax_children(AXUIElementRef el) {
    CFTypeRef value = NULL;
    if (AXUIElementCopyAttributeValue(el, kAXChildrenAttribute, &value) != kAXErrorSuccess || value == NULL) {
        return @[];
    }
    NSArray *result = CFBridgingRelease(value);
    return result ?: @[];
}

static NSString *resolve_label(AXUIElementRef el) {
    NSArray<NSString *> *attrs = @[
        (__bridge NSString *)kAXTitleAttribute,
        (__bridge NSString *)kAXDescriptionAttribute,
        @"AXHelp",
        (__bridge NSString *)kAXValueAttribute,
        @"AXPlaceholderValue",
    ];
    for (NSString *attr in attrs) {
        NSString *v = ax_str(el, (__bridge CFStringRef)attr);
        if (v != nil) {
            NSString *trimmed = [v stringByTrimmingCharactersInSet:[NSCharacterSet whitespaceAndNewlineCharacterSet]];
            if (trimmed.length > 0) return trimmed;
        }
    }
    return nil;
}

static NSString *escape_json(NSString *s) {
    if (s == nil) return @"";
    NSMutableString *out = [NSMutableString stringWithCapacity:s.length + 8];
    for (NSUInteger i = 0; i < s.length; i++) {
        unichar c = [s characterAtIndex:i];
        switch (c) {
            case '"': [out appendString:@"\\\""]; break;
            case '\\': [out appendString:@"\\\\"]; break;
            case '\n': [out appendString:@"\\n"]; break;
            case '\r': [out appendString:@"\\r"]; break;
            case '\t': [out appendString:@"\\t"]; break;
            default:
                if (c < 0x20) {
                    [out appendFormat:@"\\u%04x", c];
                } else {
                    [out appendFormat:@"%C", c];
                }
        }
    }
    return out;
}

static AXUIElementRef find_app(NSString *appName) {
    NSArray<NSRunningApplication *> *apps = [[NSWorkspace sharedWorkspace] runningApplications];
    for (NSRunningApplication *app in apps) {
        NSString *name = app.localizedName ?: @"";
        NSString *bundle = app.bundleIdentifier ?: @"";
        if ([name caseInsensitiveCompare:appName] == NSOrderedSame ||
            [bundle caseInsensitiveCompare:appName] == NSOrderedSame) {
            AXUIElementRef ax = AXUIElementCreateApplication(app.processIdentifier);
            AXUIElementSetMessagingTimeout(ax, 10.0);
            /* Defensive: tolerate failure on Chromium (§0.7). */
            AXUIElementSetAttributeValue(ax, CFSTR("AXManualAccessibility"), kCFBooleanTrue);
            return ax;
        }
    }
    return NULL;
}

static BOOL cg_windows_exist_for_pid(pid_t pid) {
    CFArrayRef list = CGWindowListCopyWindowInfo(
        kCGWindowListOptionOnScreenOnly | kCGWindowListExcludeDesktopElements,
        kCGNullWindowID);
    if (list == NULL) return NO;
    BOOL found = NO;
    CFIndex count = CFArrayGetCount(list);
    for (CFIndex i = 0; i < count; i++) {
        NSDictionary *info = (__bridge NSDictionary *)CFArrayGetValueAtIndex(list, i);
        NSNumber *owner = info[(id)kCGWindowOwnerPID];
        if (owner != nil && owner.intValue == pid) {
            NSNumber *layer = info[(id)kCGWindowLayer];
            if (layer == nil || layer.intValue == 0) {
                found = YES;
                break;
            }
        }
    }
    CFRelease(list);
    return found;
}

static const NSUInteger kMaxPayloadBytes = 32 * 1024;
static const NSUInteger kLabelMax = 72;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    g_jvm = vm;
    ensure_globals();
    return JNI_VERSION_1_8;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM *vm, void *reserved) {
    (void)vm;
    (void)reserved;
    if (g_local_monitor != nil) {
        [NSEvent removeMonitor:g_local_monitor];
        g_local_monitor = nil;
    }
    if (g_event_source != NULL) {
        CFRelease(g_event_source);
        g_event_source = NULL;
    }
    g_jvm = NULL;
}

JNIEXPORT jboolean JNICALL
Java_app_andy_desktop_service_computeruse_MacOsComputerUseNative_nativeIsAccessibilityTrusted(
    JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return AXIsProcessTrusted() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_app_andy_desktop_service_computeruse_MacOsComputerUseNative_nativeIsScreenCaptureAllowed(
    JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    if (@available(macOS 10.15, *)) {
        return CGPreflightScreenCaptureAccess() ? JNI_TRUE : JNI_FALSE;
    }
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_app_andy_desktop_service_computeruse_MacOsComputerUseNative_nativeOpenAccessibilitySettings(
    JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    NSURL *url = [NSURL URLWithString:
        @"x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility"];
    [[NSWorkspace sharedWorkspace] openURL:url];
}

JNIEXPORT void JNICALL
Java_app_andy_desktop_service_computeruse_MacOsComputerUseNative_nativeOpenScreenRecordingSettings(
    JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    NSURL *url = [NSURL URLWithString:
        @"x-apple.systempreferences:com.apple.preference.security?Privacy_ScreenCapture"];
    [[NSWorkspace sharedWorkspace] openURL:url];
}

JNIEXPORT jstring JNICALL
Java_app_andy_desktop_service_computeruse_MacOsComputerUseNative_nativeListRunningApps(
    JNIEnv *env, jclass cls) {
    (void)cls;
    NSMutableArray *rows = [NSMutableArray array];
    for (NSRunningApplication *app in [[NSWorkspace sharedWorkspace] runningApplications]) {
        if (app.activationPolicy != NSApplicationActivationPolicyRegular) continue;
        NSString *name = app.localizedName ?: @"";
        NSString *bundle = app.bundleIdentifier ?: @"";
        [rows addObject:[NSString stringWithFormat:
            @"{\"name\":\"%@\",\"bundleId\":\"%@\",\"pid\":%d}",
            escape_json(name), escape_json(bundle), app.processIdentifier]];
    }
    NSString *json = [NSString stringWithFormat:@"[%@]", [rows componentsJoinedByString:@","]];
    return (*env)->NewStringUTF(env, json.UTF8String);
}

/**
 * Dump pruned accessibility tree for [appName].
 * menus=true walks AXMenuBarExtras / menus instead of windows.
 * Returns JSON: {app,windowCount,windowsOnScreen,truncated,elements:[...],payloadBytes}
 */
JNIEXPORT jstring JNICALL
Java_app_andy_desktop_service_computeruse_MacOsComputerUseNative_nativeDumpTree(
    JNIEnv *env, jclass cls, jstring jAppName, jboolean menus) {
    (void)cls;
    ensure_globals();
    const char *cName = (*env)->GetStringUTFChars(env, jAppName, NULL);
    NSString *appName = [NSString stringWithUTF8String:cName];
    (*env)->ReleaseStringUTFChars(env, jAppName, cName);

    AXUIElementRef ax = find_app(appName);
    if (ax == NULL) {
        NSString *err = [NSString stringWithFormat:
            @"{\"error\":\"App not running: %@\",\"elements\":[]}", escape_json(appName)];
        return (*env)->NewStringUTF(env, err.UTF8String);
    }

    pid_t pid = 0;
    AXUIElementGetPid(ax, &pid);
    BOOL onScreen = cg_windows_exist_for_pid(pid);

    [g_elements removeAllObjects];

    NSMutableArray<NSString *> *elementJson = [NSMutableArray array];
    __block NSUInteger payload = 0;
    __block NSUInteger nextId = 1;
    __block BOOL truncated = NO;
    __block NSUInteger windowCount = 0;

    void (^emit)(AXUIElementRef, NSString *, NSString *, CGRect, BOOL, BOOL, NSString *, NSString *) =
        ^(AXUIElementRef el, NSString *role, NSString *label, CGRect frame,
          BOOL pressable, BOOL secure, NSString *kind, NSString *subrole) {
        if (truncated || payload >= kMaxPayloadBytes) {
            truncated = YES;
            return;
        }
        NSString *eid = [NSString stringWithFormat:@"%lu", (unsigned long)nextId++];
        /* Retain element for later actuation. */
        g_elements[eid] = (__bridge id)el;
        CFRetain(el);

        NSString *lab = label;
        if (lab != nil && lab.length > kLabelMax) {
            lab = [lab substringToIndex:kLabelMax];
        }
        NSMutableString *obj = [NSMutableString stringWithFormat:
            @"{\"i\":\"%@\",\"r\":\"%@\",\"k\":\"%@\"", eid, escape_json(role), kind];
        if (lab != nil) {
            [obj appendFormat:@",\"l\":\"%@\"", escape_json(lab)];
        }
        [obj appendFormat:@",\"b\":\"%d,%d,%d,%d\"",
            (int)frame.origin.x, (int)frame.origin.y,
            (int)frame.size.width, (int)frame.size.height];
        if (pressable) [obj appendString:@",\"p\":true"];
        if (secure) [obj appendString:@",\"s\":true"];
        if (subrole != nil && subrole.length > 0) {
            [obj appendFormat:@",\"sr\":\"%@\"", escape_json(subrole)];
        }
        [obj appendString:@"}"];
        NSUInteger bytes = [obj lengthOfBytesUsingEncoding:NSUTF8StringEncoding] + 1;
        if (payload + bytes > kMaxPayloadBytes) {
            truncated = YES;
            return;
        }
        payload += bytes;
        [elementJson addObject:obj];
    };

    __block void (^walkBlock)(AXUIElementRef, CGRect, NSInteger) = nil;
    walkBlock = ^(AXUIElementRef el, CGRect viewport, NSInteger depth) {
        if (truncated || depth > 50 || walkBlock == nil) return;
        CGRect frame = CGRectZero;
        BOOL hasFrame = ax_frame(el, &frame);
        BOOL visible = hasFrame && CGRectIntersectsRect(frame, viewport) &&
            frame.size.width > 1 && frame.size.height > 1;

        NSArray<NSString *> *actions = ax_actions(el);
        BOOL pressable = [actions containsObject:@"AXPress"];
        NSString *role = ax_str(el, kAXRoleAttribute) ?: @"?";
        NSString *subrole = ax_str(el, kAXSubroleAttribute);
        BOOL secure = [role isEqualToString:@"AXTextField"] &&
            [subrole isEqualToString:@"AXSecureTextField"];
        if (!secure && [role isEqualToString:@"AXSecureTextField"]) secure = YES;
        NSString *label = resolve_label(el);

        if (visible && pressable) {
            emit(el, role, label, frame, YES, secure, @"control", subrole);
        } else if (visible && ([role isEqualToString:@"AXStaticText"] ||
                               [role isEqualToString:@"AXHeading"])) {
            if (label != nil) {
                emit(el, role, label, frame, NO, NO, @"text", subrole);
            }
        } else if (visible && secure) {
            emit(el, role, label, frame, pressable, YES, @"control", subrole);
        }

        void (^recurse)(AXUIElementRef, CGRect, NSInteger) = walkBlock;
        for (id child in ax_children(el)) {
            recurse((__bridge AXUIElementRef)child, viewport, depth + 1);
        }
    };
    void (^walk)(AXUIElementRef, CGRect, NSInteger) = walkBlock;

    if (menus) {
        CFTypeRef menuBar = NULL;
        if (AXUIElementCopyAttributeValue(ax, kAXMenuBarAttribute, &menuBar) == kAXErrorSuccess &&
            menuBar != NULL) {
            windowCount = 1;
            CGRect infinite = CGRectMake(-100000, -100000, 200000, 200000);
            walk((AXUIElementRef)menuBar, infinite, 0);
            CFRelease(menuBar);
        }
    } else {
        CFTypeRef winsRef = NULL;
        AXUIElementCopyAttributeValue(ax, kAXWindowsAttribute, &winsRef);
        NSArray *wins = winsRef ? CFBridgingRelease(winsRef) : @[];
        windowCount = wins.count;
        if (windowCount == 0 && onScreen) {
            /* §0.6: AX said no windows but CGWindowList disagrees — not inaccessible. */
        }
        for (id w in wins) {
            AXUIElementRef win = (__bridge AXUIElementRef)w;
            CGRect viewport = CGRectZero;
            if (!ax_frame(win, &viewport)) continue;
            walk(win, viewport, 0);
        }
    }

    CFRelease(ax);

    NSString *json = [NSString stringWithFormat:
        @"{\"app\":\"%@\",\"windowCount\":%lu,\"windowsOnScreen\":%@,\"truncated\":%@,"
         "\"payloadBytes\":%lu,\"elements\":[%@]}",
        escape_json(appName),
        (unsigned long)windowCount,
        onScreen ? @"true" : @"false",
        truncated ? @"true" : @"false",
        (unsigned long)payload,
        [elementJson componentsJoinedByString:@","]];
    return (*env)->NewStringUTF(env, json.UTF8String);
}

static NSString *read_state(AXUIElementRef el) {
    NSString *value = ax_str(el, kAXValueAttribute);
    if (value != nil) return [@"v:" stringByAppendingString:value];
    NSString *title = ax_str(el, kAXTitleAttribute);
    if (title != nil) return [@"t:" stringByAppendingString:title];
    /* Parent selection / window title as fallback. */
    CFTypeRef parent = NULL;
    if (AXUIElementCopyAttributeValue(el, kAXParentAttribute, &parent) == kAXErrorSuccess && parent) {
        NSString *pv = ax_str((AXUIElementRef)parent, kAXValueAttribute);
        CFRelease(parent);
        if (pv != nil) return [@"pv:" stringByAppendingString:pv];
    }
    return nil;
}

JNIEXPORT jstring JNICALL
Java_app_andy_desktop_service_computeruse_MacOsComputerUseNative_nativePressElement(
    JNIEnv *env, jclass cls, jstring jElementId, jint settleMs) {
    (void)cls;
    ensure_globals();
    const char *cid = (*env)->GetStringUTFChars(env, jElementId, NULL);
    NSString *eid = [NSString stringWithUTF8String:cid];
    (*env)->ReleaseStringUTFChars(env, jElementId, cid);

    id boxed = g_elements[eid];
    if (boxed == nil) {
        return (*env)->NewStringUTF(env,
            "{\"verdict\":\"failed\",\"message\":\"Unknown element id (dump again)\",\"axError\":null}");
    }
    AXUIElementRef el = (__bridge AXUIElementRef)boxed;
    NSString *before = read_state(el);
    AXError err = AXUIElementPerformAction(el, kAXPressAction);
    usleep((useconds_t)MAX(settleMs, 50) * 1000);
    NSString *after = read_state(el);

    NSString *verdict;
    NSString *message;
    if (before != nil && after != nil && ![before isEqualToString:after]) {
        verdict = @"succeeded";
        message = @"State changed after AXPress";
    } else if (before == nil || after == nil) {
        verdict = @"unverified";
        message = @"AXPress completed but state was unreadable; take a screenshot to verify";
    } else {
        /* State unchanged — may still have worked (e.g. open menu). Report unverified. */
        verdict = @"unverified";
        message = [NSString stringWithFormat:
            @"AXPress returned axError=%d; state unchanged — verify visually", (int)err];
    }
    NSString *json = [NSString stringWithFormat:
        @"{\"verdict\":\"%@\",\"message\":\"%@\",\"axError\":%d,\"elementId\":\"%@\"}",
        verdict, escape_json(message), (int)err, escape_json(eid)];
    return (*env)->NewStringUTF(env, json.UTF8String);
}

JNIEXPORT jstring JNICALL
Java_app_andy_desktop_service_computeruse_MacOsComputerUseNative_nativeSetElementValue(
    JNIEnv *env, jclass cls, jstring jElementId, jstring jValue) {
    (void)cls;
    ensure_globals();
    const char *cid = (*env)->GetStringUTFChars(env, jElementId, NULL);
    NSString *eid = [NSString stringWithUTF8String:cid];
    (*env)->ReleaseStringUTFChars(env, jElementId, cid);
    const char *cval = (*env)->GetStringUTFChars(env, jValue, NULL);
    NSString *value = [NSString stringWithUTF8String:cval];
    (*env)->ReleaseStringUTFChars(env, jValue, cval);

    id boxed = g_elements[eid];
    if (boxed == nil) {
        return (*env)->NewStringUTF(env,
            "{\"verdict\":\"failed\",\"message\":\"Unknown element id\"}");
    }
    AXUIElementRef el = (__bridge AXUIElementRef)boxed;
    NSString *role = ax_str(el, kAXRoleAttribute) ?: @"";
    NSString *subrole = ax_str(el, kAXSubroleAttribute) ?: @"";
    if ([role isEqualToString:@"AXSecureTextField"] ||
        [subrole isEqualToString:@"AXSecureTextField"]) {
        return (*env)->NewStringUTF(env,
            "{\"verdict\":\"denied\",\"message\":\"Typing into secure fields is blocked\"}");
    }
    AXError err = AXUIElementSetAttributeValue(el, kAXValueAttribute, (__bridge CFTypeRef)value);
    usleep(200 * 1000);
    NSString *readback = ax_str(el, kAXValueAttribute);
    NSString *verdict;
    NSString *message;
    if (readback != nil && [readback isEqualToString:value]) {
        verdict = @"succeeded";
        message = @"Value set and verified";
    } else if (err == kAXErrorSuccess) {
        verdict = @"unverified";
        message = @"SetValue returned success but read-back mismatched; prefer synthetic typing for web inputs";
    } else {
        verdict = @"failed";
        message = [NSString stringWithFormat:@"SetValue failed axError=%d", (int)err];
    }
    NSString *json = [NSString stringWithFormat:
        @"{\"verdict\":\"%@\",\"message\":\"%@\",\"axError\":%d}",
        verdict, escape_json(message), (int)err];
    return (*env)->NewStringUTF(env, json.UTF8String);
}

static void post_event(CGEventRef event) {
    ensure_globals();
    if (g_event_source != NULL) {
        CGEventSetSource(event, g_event_source);
        CGEventSetIntegerValueField(event, kCGEventSourceUserData, kAndyEventTag);
    }
    CGEventPost(kCGHIDEventTap, event);
}

JNIEXPORT void JNICALL
Java_app_andy_desktop_service_computeruse_MacOsComputerUseNative_nativeClick(
    JNIEnv *env, jclass cls, jint x, jint y) {
    (void)env; (void)cls;
    CGPoint pt = CGPointMake(x, y);
    CGEventRef down = CGEventCreateMouseEvent(NULL, kCGEventLeftMouseDown, pt, kCGMouseButtonLeft);
    CGEventRef up = CGEventCreateMouseEvent(NULL, kCGEventLeftMouseUp, pt, kCGMouseButtonLeft);
    post_event(down);
    post_event(up);
    CFRelease(down);
    CFRelease(up);
}

JNIEXPORT void JNICALL
Java_app_andy_desktop_service_computeruse_MacOsComputerUseNative_nativeScroll(
    JNIEnv *env, jclass cls, jint x, jint y, jint deltaX, jint deltaY) {
    (void)env; (void)cls;
    CGPoint pt = CGPointMake(x, y);
    CGEventRef move = CGEventCreateMouseEvent(NULL, kCGEventMouseMoved, pt, kCGMouseButtonLeft);
    post_event(move);
    CFRelease(move);
    CGEventRef scroll = CGEventCreateScrollWheelEvent(
        NULL, kCGScrollEventUnitLine, 2, deltaY, deltaX);
    post_event(scroll);
    CFRelease(scroll);
}

JNIEXPORT void JNICALL
Java_app_andy_desktop_service_computeruse_MacOsComputerUseNative_nativeDrag(
    JNIEnv *env, jclass cls, jint x1, jint y1, jint x2, jint y2, jint durationMs) {
    (void)env; (void)cls;
    int steps = MAX(durationMs / 16, 5);
    CGPoint start = CGPointMake(x1, y1);
    CGEventRef down = CGEventCreateMouseEvent(NULL, kCGEventLeftMouseDown, start, kCGMouseButtonLeft);
    post_event(down);
    CFRelease(down);
    for (int i = 1; i <= steps; i++) {
        double t = (double)i / (double)steps;
        CGPoint p = CGPointMake(x1 + (x2 - x1) * t, y1 + (y2 - y1) * t);
        CGEventRef drag = CGEventCreateMouseEvent(NULL, kCGEventLeftMouseDragged, p, kCGMouseButtonLeft);
        post_event(drag);
        CFRelease(drag);
        usleep(16 * 1000);
    }
    CGPoint end = CGPointMake(x2, y2);
    CGEventRef up = CGEventCreateMouseEvent(NULL, kCGEventLeftMouseUp, end, kCGMouseButtonLeft);
    post_event(up);
    CFRelease(up);
}

JNIEXPORT void JNICALL
Java_app_andy_desktop_service_computeruse_MacOsComputerUseNative_nativeTypeText(
    JNIEnv *env, jclass cls, jstring jText) {
    (void)cls;
    const char *ctext = (*env)->GetStringUTFChars(env, jText, NULL);
    NSString *text = [NSString stringWithUTF8String:ctext];
    (*env)->ReleaseStringUTFChars(env, jText, ctext);
    for (NSUInteger i = 0; i < text.length; i++) {
        UniChar c = [text characterAtIndex:i];
        CGEventRef down = CGEventCreateKeyboardEvent(NULL, 0, true);
        CGEventRef up = CGEventCreateKeyboardEvent(NULL, 0, false);
        CGEventKeyboardSetUnicodeString(down, 1, &c);
        CGEventKeyboardSetUnicodeString(up, 1, &c);
        post_event(down);
        post_event(up);
        CFRelease(down);
        CFRelease(up);
        usleep(8 * 1000);
    }
}

JNIEXPORT void JNICALL
Java_app_andy_desktop_service_computeruse_MacOsComputerUseNative_nativePressKey(
    JNIEnv *env, jclass cls, jint keyCode, jboolean shift, jboolean ctrl, jboolean alt, jboolean meta) {
    (void)env; (void)cls;
    CGEventRef down = CGEventCreateKeyboardEvent(NULL, (CGKeyCode)keyCode, true);
    CGEventRef up = CGEventCreateKeyboardEvent(NULL, (CGKeyCode)keyCode, false);
    CGEventFlags flags = 0;
    if (shift) flags |= kCGEventFlagMaskShift;
    if (ctrl) flags |= kCGEventFlagMaskControl;
    if (alt) flags |= kCGEventFlagMaskAlternate;
    if (meta) flags |= kCGEventFlagMaskCommand;
    CGEventSetFlags(down, flags);
    CGEventSetFlags(up, flags);
    post_event(down);
    post_event(up);
    CFRelease(down);
    CFRelease(up);
}

JNIEXPORT jbyteArray JNICALL
Java_app_andy_desktop_service_computeruse_MacOsComputerUseNative_nativeCapturePng(
    JNIEnv *env, jclass cls, jint x, jint y, jint w, jint h) {
    (void)cls;
    /*
     * CGWindowListCreateImage is unavailable on macOS 15+. Use screencapture(1)
     * for a reliable PNG; ScreenCaptureKit can replace this in a later pass.
     */
    NSString *path = [NSTemporaryDirectory() stringByAppendingPathComponent:
        [NSString stringWithFormat:@"andy-cu-%@.png", [[NSUUID UUID] UUIDString]]];
    NSMutableArray<NSString *> *taskArgs = [NSMutableArray array];
    [taskArgs addObject:@"-x"];
    [taskArgs addObject:@"-t"];
    [taskArgs addObject:@"png"];
    if (w > 0 && h > 0) {
        [taskArgs addObject:@"-R"];
        [taskArgs addObject:[NSString stringWithFormat:@"%d,%d,%d,%d", x, y, w, h]];
    }
    [taskArgs addObject:path];

    NSTask *task = [[NSTask alloc] init];
    NSString *bin = @"/usr/sbin/screencapture";
    if (![[NSFileManager defaultManager] isExecutableFileAtPath:bin]) {
        bin = @"/usr/bin/screencapture";
    }
    task.executableURL = [NSURL fileURLWithPath:bin];
    task.arguments = taskArgs;
    NSError *launchErr = nil;
    if (![task launchAndReturnError:&launchErr]) {
        return NULL;
    }
    [task waitUntilExit];
    if (task.terminationStatus != 0) {
        [[NSFileManager defaultManager] removeItemAtPath:path error:nil];
        return NULL;
    }
    NSData *png = [NSData dataWithContentsOfFile:path];
    [[NSFileManager defaultManager] removeItemAtPath:path error:nil];
    if (png == nil || png.length == 0) return NULL;
    jbyteArray arr = (*env)->NewByteArray(env, (jsize)png.length);
    (*env)->SetByteArrayRegion(env, arr, 0, (jsize)png.length, (const jbyte *)png.bytes);
    return arr;
}

JNIEXPORT jstring JNICALL
Java_app_andy_desktop_service_computeruse_MacOsComputerUseNative_nativeDisplayGeometry(
    JNIEnv *env, jclass cls) {
    (void)cls;
    CGDirectDisplayID displays[16];
    uint32_t count = 0;
    CGGetActiveDisplayList(16, displays, &count);
    NSMutableArray *rows = [NSMutableArray array];
    for (uint32_t i = 0; i < count; i++) {
        CGRect b = CGDisplayBounds(displays[i]);
        CGFloat scale = 1.0;
        if (@available(macOS 10.11, *)) {
            scale = CGDisplayScreenSize(displays[i]).width > 0
                ? (CGFloat)CGDisplayPixelsWide(displays[i]) / b.size.width
                : 1.0;
        }
        NSScreen *match = nil;
        for (NSScreen *screen in [NSScreen screens]) {
            NSDictionary *dev = screen.deviceDescription;
            NSNumber *num = dev[@"NSScreenNumber"];
            if (num != nil && num.unsignedIntValue == displays[i]) {
                match = screen;
                break;
            }
        }
        if (match != nil) scale = match.backingScaleFactor;
        [rows addObject:[NSString stringWithFormat:
            @"{\"id\":%u,\"x\":%d,\"y\":%d,\"w\":%d,\"h\":%d,\"scale\":%.2f,\"primary\":%@}",
            displays[i],
            (int)b.origin.x, (int)b.origin.y, (int)b.size.width, (int)b.size.height,
            scale, i == 0 ? @"true" : @"false"]];
    }
    NSString *json = [NSString stringWithFormat:@"[%@]", [rows componentsJoinedByString:@","]];
    return (*env)->NewStringUTF(env, json.UTF8String);
}

JNIEXPORT jboolean JNICALL
Java_app_andy_desktop_service_computeruse_MacOsComputerUseNative_nativeFocusElement(
    JNIEnv *env, jclass cls, jstring jElementId) {
    (void)cls;
    ensure_globals();
    const char *cid = (*env)->GetStringUTFChars(env, jElementId, NULL);
    NSString *eid = [NSString stringWithUTF8String:cid];
    (*env)->ReleaseStringUTFChars(env, jElementId, cid);
    id boxed = g_elements[eid];
    if (boxed == nil) return JNI_FALSE;
    AXUIElementRef el = (__bridge AXUIElementRef)boxed;
    AXError err = AXUIElementSetAttributeValue(el, kAXFocusedAttribute, kCFBooleanTrue);
    return err == kAXErrorSuccess ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_app_andy_desktop_service_computeruse_MacOsComputerUseNative_nativeElementMeta(
    JNIEnv *env, jclass cls, jstring jElementId) {
    (void)cls;
    ensure_globals();
    const char *cid = (*env)->GetStringUTFChars(env, jElementId, NULL);
    NSString *eid = [NSString stringWithUTF8String:cid];
    (*env)->ReleaseStringUTFChars(env, jElementId, cid);
    id boxed = g_elements[eid];
    if (boxed == nil) {
        return (*env)->NewStringUTF(env, "{\"error\":\"unknown\"}");
    }
    AXUIElementRef el = (__bridge AXUIElementRef)boxed;
    NSString *role = ax_str(el, kAXRoleAttribute) ?: @"?";
    NSString *subrole = ax_str(el, kAXSubroleAttribute) ?: @"";
    NSString *label = resolve_label(el) ?: @"";
    BOOL secure = [role isEqualToString:@"AXSecureTextField"] ||
        [subrole isEqualToString:@"AXSecureTextField"];
    CGRect frame = CGRectZero;
    ax_frame(el, &frame);
    NSString *json = [NSString stringWithFormat:
        @"{\"role\":\"%@\",\"subrole\":\"%@\",\"label\":\"%@\",\"secure\":%@,\"b\":\"%d,%d,%d,%d\"}",
        escape_json(role), escape_json(subrole), escape_json(label),
        secure ? @"true" : @"false",
        (int)frame.origin.x, (int)frame.origin.y,
        (int)frame.size.width, (int)frame.size.height];
    return (*env)->NewStringUTF(env, json.UTF8String);
}

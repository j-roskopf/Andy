#include "andy_mirror_internal.h"

#include <X11/Xatom.h>
#include <X11/Xutil.h>
#include <X11/extensions/Xfixes.h>
#include <X11/extensions/shape.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>

static pthread_mutex_t x11_lock = PTHREAD_MUTEX_INITIALIZER;
static Display *display = NULL;
static int screen = 0;
static Window root = None;
static Atom wm_delete = None;
static Atom net_wm_desktop = None;
static Atom net_wm_state = None;
static Atom net_wm_state_skip_taskbar = None;
static Atom net_wm_state_skip_pager = None;
static Atom net_wm_window_type = None;
static Atom net_wm_window_type_utility = None;
static Atom motif_wm_hints = None;
static bool threads_ready = false;

typedef struct {
    unsigned long flags;
    unsigned long functions;
    unsigned long decorations;
    long input_mode;
    unsigned long status;
} MotifWmHints;

static void ensure_wm_atoms(void) {
    if (net_wm_desktop != None) return;
    net_wm_desktop = XInternAtom(display, "_NET_WM_DESKTOP", False);
    net_wm_state = XInternAtom(display, "_NET_WM_STATE", False);
    net_wm_state_skip_taskbar = XInternAtom(display, "_NET_WM_STATE_SKIP_TASKBAR", False);
    net_wm_state_skip_pager = XInternAtom(display, "_NET_WM_STATE_SKIP_PAGER", False);
    net_wm_window_type = XInternAtom(display, "_NET_WM_WINDOW_TYPE", False);
    net_wm_window_type_utility = XInternAtom(display, "_NET_WM_WINDOW_TYPE_UTILITY", False);
    motif_wm_hints = XInternAtom(display, "_MOTIF_WM_HINTS", False);
}

static bool read_cardinal(Window window, Atom property, unsigned long *out) {
    if (!out) return false;
    *out = 0;
    ensure_wm_atoms();
    Atom actual_type = None;
    int actual_format = 0;
    unsigned long nitems = 0;
    unsigned long bytes_after = 0;
    unsigned char *prop = NULL;
    if (XGetWindowProperty(display, window, property, 0, 1, False, XA_CARDINAL, &actual_type, &actual_format,
                           &nitems, &bytes_after, &prop) != Success ||
        !prop || nitems == 0) {
        if (prop) XFree(prop);
        return false;
    }
    *out = *(unsigned long *) prop;
    XFree(prop);
    return true;
}

static bool window_is_viewable(Window window) {
    if (!window) return false;
    XWindowAttributes attrs;
    if (!XGetWindowAttributes(display, window, &attrs)) return false;
    return attrs.map_state == IsViewable;
}

static void mark_overlay_chromeless(Window overlay) {
    ensure_wm_atoms();
    Atom states[2] = {net_wm_state_skip_taskbar, net_wm_state_skip_pager};
    XChangeProperty(display, overlay, net_wm_state, XA_ATOM, 32, PropModeReplace, (unsigned char *) states, 2);
    XChangeProperty(display, overlay, net_wm_window_type, XA_ATOM, 32, PropModeReplace,
                    (unsigned char *) &net_wm_window_type_utility, 1);
    MotifWmHints hints = {.flags = 2, .functions = 0, .decorations = 0, .input_mode = 0, .status = 0};
    XChangeProperty(display, overlay, motif_wm_hints, motif_wm_hints, 32, PropModeReplace, (unsigned char *) &hints, 5);
}

static void sync_desktop_from_parent(Window overlay, Window parent) {
    if (!overlay || !parent) return;
    ensure_wm_atoms();
    Atom actual_type = None;
    int actual_format = 0;
    unsigned long nitems = 0;
    unsigned long bytes_after = 0;
    unsigned char *prop = NULL;
    if (XGetWindowProperty(display, parent, net_wm_desktop, 0, 1, False, XA_CARDINAL, &actual_type,
                           &actual_format, &nitems, &bytes_after, &prop) != Success ||
        !prop || nitems == 0) {
        if (prop) XFree(prop);
        return;
    }
    XChangeProperty(display, overlay, net_wm_desktop, XA_CARDINAL, 32, PropModeReplace, prop, 1);
    XFree(prop);
}

bool andy_x11_init(void) {
    pthread_mutex_lock(&x11_lock);
    if (display) {
        pthread_mutex_unlock(&x11_lock);
        return true;
    }
    if (!threads_ready) {
        XInitThreads();
        threads_ready = true;
    }
    display = XOpenDisplay(NULL);
    if (!display) {
        pthread_mutex_unlock(&x11_lock);
        return false;
    }
    screen = DefaultScreen(display);
    root = RootWindow(display, screen);
    wm_delete = XInternAtom(display, "WM_DELETE_WINDOW", False);
    ensure_wm_atoms();
    pthread_mutex_unlock(&x11_lock);
    return true;
}

Display *andy_x11_display(void) {
    return display;
}

int andy_x11_window_desktop(unsigned long window_id) {
    if (!window_id || !andy_x11_init()) return -1;
    pthread_mutex_lock(&x11_lock);
    unsigned long desktop = 0;
    const bool ok = read_cardinal((Window) window_id, net_wm_desktop, &desktop);
    pthread_mutex_unlock(&x11_lock);
    if (!ok) return -1;
    if (desktop == 0xFFFFFFFFUL) return -2;
    return (int) desktop;
}

static void apply_input_shape(Window window) {
    if (!display || !window) return;
    XserverRegion region = XFixesCreateRegion(display, NULL, 0);
    XFixesSetWindowShapeRegion(display, window, ShapeInput, 0, 0, region);
    XFixesDestroyRegion(display, region);
}

bool andy_x11_create_overlay(Window *out_window) {
    if (!out_window || !andy_x11_init()) return false;
    pthread_mutex_lock(&x11_lock);
    XVisualInfo vinfo;
    if (!XMatchVisualInfo(display, screen, 24, TrueColor, &vinfo) &&
        !XMatchVisualInfo(display, screen, DefaultDepth(display, screen), TrueColor, &vinfo)) {
        pthread_mutex_unlock(&x11_lock);
        return false;
    }
    XSetWindowAttributes swa;
    memset(&swa, 0, sizeof(swa));
    swa.colormap = XCreateColormap(display, root, vinfo.visual, AllocNone);
    swa.override_redirect = True;
    swa.border_pixel = 0;
    swa.background_pixel = 0;
    swa.event_mask = ExposureMask | StructureNotifyMask;
    Window window = XCreateWindow(
        display, root, 0, 0, 390, 844, 0, vinfo.depth, InputOutput, vinfo.visual,
        CWColormap | CWOverrideRedirect | CWBorderPixel | CWBackPixel | CWEventMask, &swa);
    if (!window) {
        pthread_mutex_unlock(&x11_lock);
        return false;
    }
    XStoreName(display, window, "Andy Live overlay");
    XSetWMProtocols(display, window, &wm_delete, 1);
    mark_overlay_chromeless(window);
    apply_input_shape(window);
    XFlush(display);
    pthread_mutex_unlock(&x11_lock);
    *out_window = window;
    return true;
}

void andy_x11_destroy_overlay(Window window) {
    if (!display || !window) return;
    pthread_mutex_lock(&x11_lock);
    XUnmapWindow(display, window);
    XDestroyWindow(display, window);
    XFlush(display);
    pthread_mutex_unlock(&x11_lock);
}

static void restack_above_parent(Window window, Window parent) {
    if (parent && parent != window) {
        XWindowChanges changes;
        memset(&changes, 0, sizeof(changes));
        changes.sibling = parent;
        changes.stack_mode = Above;
        XConfigureWindow(display, window, CWSibling | CWStackMode, &changes);
    }
    XRaiseWindow(display, window);
}

bool andy_x11_window_viewable(unsigned long window_id) {
    if (!window_id || !andy_x11_init()) return false;
    pthread_mutex_lock(&x11_lock);
    const bool viewable = window_is_viewable((Window) window_id);
    pthread_mutex_unlock(&x11_lock);
    return viewable;
}

bool andy_x11_should_map(Window window, Window parent, bool visible) {
    (void) window;
    (void) parent;
    // Do not gate on parent viewability: XWayland often reports Andy's window as
    // non-viewable during layout / desktop transitions, which left the overlay
    // permanently unmapped (black Live). Virtual-desktop floating is handled by
    // andy_hub_suppress_presenters_for_desktop_switch instead.
    return visible && display != NULL;
}

bool andy_x11_configure(Window window, int x, int y, int width, int height, unsigned long parent, bool visible,
                        bool restack) {
    if (!display || !window) return false;
    if (width < 1) width = 1;
    if (height < 1) height = 1;
    pthread_mutex_lock(&x11_lock);
    const Window parent_window = parent ? (Window) parent : None;
    XMoveResizeWindow(display, window, x, y, (unsigned) width, (unsigned) height);
    apply_input_shape(window);
    const bool map = andy_x11_should_map(window, parent_window, visible);
    if (map) {
        sync_desktop_from_parent(window, parent_window);
        XMapWindow(display, window);
        if (restack) restack_above_parent(window, parent_window);
    } else {
        XUnmapWindow(display, window);
    }
    XFlush(display);
    pthread_mutex_unlock(&x11_lock);
    return map;
}

void andy_x11_set_visible(Window window, bool visible) {
    if (!display || !window) return;
    pthread_mutex_lock(&x11_lock);
    if (visible) {
        XMapWindow(display, window);
        XRaiseWindow(display, window);
    } else {
        XUnmapWindow(display, window);
    }
    XFlush(display);
    pthread_mutex_unlock(&x11_lock);
}

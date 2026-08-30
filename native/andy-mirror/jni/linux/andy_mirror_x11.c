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
static bool threads_ready = false;

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
    pthread_mutex_unlock(&x11_lock);
    return true;
}

Display *andy_x11_display(void) {
    return display;
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

static void restack_above_parent(Window window, unsigned long parent) {
    if (parent && parent != window) {
        XWindowChanges changes;
        memset(&changes, 0, sizeof(changes));
        changes.sibling = (Window) parent;
        changes.stack_mode = Above;
        XConfigureWindow(display, window, CWSibling | CWStackMode, &changes);
    }
    XRaiseWindow(display, window);
}

void andy_x11_configure(Window window, int x, int y, int width, int height, unsigned long parent, bool visible) {
    if (!display || !window) return;
    if (width < 1) width = 1;
    if (height < 1) height = 1;
    pthread_mutex_lock(&x11_lock);
    XMoveResizeWindow(display, window, x, y, (unsigned) width, (unsigned) height);
    apply_input_shape(window);
    if (visible) {
        XMapWindow(display, window);
        restack_above_parent(window, parent);
    }
    XFlush(display);
    pthread_mutex_unlock(&x11_lock);
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

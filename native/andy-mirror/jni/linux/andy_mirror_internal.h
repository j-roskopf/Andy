#ifndef ANDY_MIRROR_INTERNAL_H
#define ANDY_MIRROR_INTERNAL_H

#include "andy_mirror_hub.h"

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include <X11/Xlib.h>

#define ANDY_MAX_DECODERS 8
#define ANDY_MAX_PRESENTERS 16
#define ANDY_MAX_PRESENTERS_PER_DECODER 8

typedef struct {
    float grid[4];
    float ruler[4];
    float highlight[4];
    float grid_color[4];
    float ruler_color[4];
    float picker[4];
    float source_size[4];
    float format_flags[4];
} AndyMirrorOverlay;

typedef struct {
    int width;
    int height;
    bool is_bgra;
    bool full_range_yuv;
    uint8_t *plane0;
    size_t stride0;
    uint8_t *plane1;
    size_t stride1;
} AndyFrameStore;

void andy_frame_store_clear(AndyFrameStore *store);
bool andy_frame_store_set_bgra(AndyFrameStore *store, int width, int height, uint8_t blue, uint8_t green,
                               uint8_t red, uint8_t alpha);
bool andy_frame_store_copy_nv12(AndyFrameStore *store, int width, int height, const uint8_t *y, int y_stride,
                                const uint8_t *uv, int uv_stride, bool full_range);
bool andy_frame_store_copy_yuv420p(AndyFrameStore *store, int width, int height, const uint8_t *y, int y_stride,
                                   const uint8_t *u, int u_stride, const uint8_t *v, int v_stride, bool full_range);
bool andy_frame_store_clone(AndyFrameStore *dest, const AndyFrameStore *src);

bool andy_x11_init(void);
Display *andy_x11_display(void);
bool andy_x11_create_overlay(Window *out_window);
void andy_x11_destroy_overlay(Window window);
/** Returns true when the overlay is mapped after the configure. */
bool andy_x11_configure(Window window, int x, int y, int width, int height, unsigned long parent, bool visible,
                        bool restack);
void andy_x11_set_visible(Window window, bool visible);
bool andy_x11_should_map(Window window, Window parent, bool visible);
bool andy_x11_window_viewable(unsigned long window_id);
int andy_x11_window_desktop(unsigned long window_id);

typedef struct AndyVkSwapchain AndyVkSwapchain;

bool andy_vk_init(void);
AndyVkSwapchain *andy_vk_attach(Window window);
void andy_vk_detach(AndyVkSwapchain *swapchain);
void andy_vk_note_resize(AndyVkSwapchain *swapchain, int width, int height);
bool andy_vk_present(AndyVkSwapchain *swapchain, const AndyFrameStore *frame, const AndyMirrorOverlay *overlay);

typedef struct AndyNvdec AndyNvdec;

AndyNvdec *andy_nvdec_open(const uint8_t *sps, size_t sps_size, const uint8_t *pps, size_t pps_size);
void andy_nvdec_close(AndyNvdec *decoder);
void andy_nvdec_reset(AndyNvdec *decoder);
bool andy_nvdec_is_hardware(const AndyNvdec *decoder);
bool andy_nvdec_decode(AndyNvdec *decoder, const uint8_t *annexb, size_t length, AndyFrameStore *out);

#endif

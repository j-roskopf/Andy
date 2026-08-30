#include "andy_mirror_internal.h"

#include <stdlib.h>
#include <string.h>

void andy_frame_store_clear(AndyFrameStore *store) {
    if (!store) return;
    free(store->plane0);
    free(store->plane1);
    memset(store, 0, sizeof(*store));
}

static bool alloc_planes(AndyFrameStore *store, int width, int height, bool is_bgra) {
    andy_frame_store_clear(store);
    if (width <= 0 || height <= 0 || width > 8192 || height > 8192) return false;
    store->width = width;
    store->height = height;
    store->is_bgra = is_bgra;
    if (is_bgra) {
        store->stride0 = (size_t) width * 4;
        store->plane0 = (uint8_t *) malloc(store->stride0 * (size_t) height);
        return store->plane0 != NULL;
    }
    store->stride0 = (size_t) width;
    store->stride1 = (size_t) width;
    store->plane0 = (uint8_t *) malloc(store->stride0 * (size_t) height);
    store->plane1 = (uint8_t *) malloc(store->stride1 * (size_t) (height / 2));
    if (!store->plane0 || !store->plane1) {
        andy_frame_store_clear(store);
        return false;
    }
    return true;
}

bool andy_frame_store_set_bgra(AndyFrameStore *store, int width, int height, uint8_t blue, uint8_t green,
                               uint8_t red, uint8_t alpha) {
    if (!alloc_planes(store, width, height, true)) return false;
    for (int y = 0; y < height; ++y) {
        uint8_t *row = store->plane0 + (size_t) y * store->stride0;
        for (int x = 0; x < width; ++x) {
            row[x * 4 + 0] = blue;
            row[x * 4 + 1] = green;
            row[x * 4 + 2] = red;
            row[x * 4 + 3] = alpha;
        }
    }
    return true;
}

bool andy_frame_store_copy_nv12(AndyFrameStore *store, int width, int height, const uint8_t *y, int y_stride,
                                const uint8_t *uv, int uv_stride, bool full_range) {
    if (!y || !uv || y_stride <= 0 || uv_stride <= 0) return false;
    if (!alloc_planes(store, width, height, false)) return false;
    store->full_range_yuv = full_range;
    for (int row = 0; row < height; ++row) {
        memcpy(store->plane0 + (size_t) row * store->stride0, y + (size_t) row * (size_t) y_stride, (size_t) width);
    }
    for (int row = 0; row < height / 2; ++row) {
        memcpy(store->plane1 + (size_t) row * store->stride1, uv + (size_t) row * (size_t) uv_stride, (size_t) width);
    }
    return true;
}

bool andy_frame_store_copy_yuv420p(AndyFrameStore *store, int width, int height, const uint8_t *y, int y_stride,
                                   const uint8_t *u, int u_stride, const uint8_t *v, int v_stride, bool full_range) {
    if (!y || !u || !v || y_stride <= 0 || u_stride <= 0 || v_stride <= 0) return false;
    if (!alloc_planes(store, width, height, false)) return false;
    store->full_range_yuv = full_range;
    for (int row = 0; row < height; ++row) {
        memcpy(store->plane0 + (size_t) row * store->stride0, y + (size_t) row * (size_t) y_stride, (size_t) width);
    }
    for (int row = 0; row < height / 2; ++row) {
        uint8_t *dst = store->plane1 + (size_t) row * store->stride1;
        const uint8_t *u_row = u + (size_t) row * (size_t) u_stride;
        const uint8_t *v_row = v + (size_t) row * (size_t) v_stride;
        for (int x = 0; x < width / 2; ++x) {
            dst[x * 2] = u_row[x];
            dst[x * 2 + 1] = v_row[x];
        }
    }
    return true;
}

bool andy_frame_store_clone(AndyFrameStore *dest, const AndyFrameStore *src) {
    if (!dest || !src || !src->plane0 || src->width <= 0 || src->height <= 0) return false;
    if (src->is_bgra) {
        if (!alloc_planes(dest, src->width, src->height, true)) return false;
        memcpy(dest->plane0, src->plane0, dest->stride0 * (size_t) dest->height);
        return true;
    }
    if (!src->plane1) return false;
    if (!alloc_planes(dest, src->width, src->height, false)) return false;
    dest->full_range_yuv = src->full_range_yuv;
    memcpy(dest->plane0, src->plane0, dest->stride0 * (size_t) dest->height);
    memcpy(dest->plane1, src->plane1, dest->stride1 * (size_t) (dest->height / 2));
    return true;
}

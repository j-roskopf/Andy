#include "andy_mirror_av.h"

#include <dlfcn.h>
#include <pthread.h>
#include <stdio.h>
#include <string.h>

static pthread_mutex_t load_lock = PTHREAD_MUTEX_INITIALIZER;
static bool loaded = false;
static bool load_ok = false;
static void *libavutil_handle = NULL;
static void *libavcodec_handle = NULL;

static void *(*p_av_malloc)(size_t) = NULL;
static void (*p_avcodec_free_context)(AVCodecContext **) = NULL;
static void (*p_av_buffer_unref)(AVBufferRef **) = NULL;
static const AVCodec *(*p_avcodec_find_decoder_by_name)(const char *) = NULL;
static AVCodecContext *(*p_avcodec_alloc_context3)(const AVCodec *) = NULL;
static int (*p_av_hwdevice_ctx_create)(AVBufferRef **, enum AVHWDeviceType, const char *, AVDictionary *, int) = NULL;
static AVBufferRef *(*p_av_buffer_ref)(const AVBufferRef *) = NULL;
static int (*p_av_opt_set)(void *, const char *, const char *, int) = NULL;
static int (*p_avcodec_open2)(AVCodecContext *, const AVCodec *, AVDictionary **) = NULL;
static AVPacket *(*p_av_packet_alloc)(void) = NULL;
static AVFrame *(*p_av_frame_alloc)(void) = NULL;
static void (*p_av_packet_free)(AVPacket **) = NULL;
static void (*p_av_frame_free)(AVFrame **) = NULL;
static void (*p_avcodec_flush_buffers)(AVCodecContext *) = NULL;
static void (*p_av_packet_unref)(AVPacket *) = NULL;
static int (*p_av_new_packet)(AVPacket *, int) = NULL;
static int (*p_avcodec_send_packet)(AVCodecContext *, const AVPacket *) = NULL;
static int (*p_avcodec_receive_frame)(AVCodecContext *, AVFrame *) = NULL;
static void (*p_av_frame_unref)(AVFrame *) = NULL;
static int (*p_av_hwframe_transfer_data)(AVFrame *, const AVFrame *, int) = NULL;
static AVCodecParameters *(*p_avcodec_parameters_alloc)(void) = NULL;
static void (*p_avcodec_parameters_free)(AVCodecParameters **) = NULL;
static int (*p_avcodec_parameters_to_context)(AVCodecContext *, const AVCodecParameters *) = NULL;

static void *open_sonames(const char *const *names) {
    for (int i = 0; names[i]; ++i) {
        void *handle = dlopen(names[i], RTLD_NOW | RTLD_GLOBAL);
        if (handle) return handle;
    }
    return NULL;
}

static bool lookup(void *handle, const char *name, void **out) {
    if (!handle) return false;
    dlerror();
    void *sym = dlsym(handle, name);
    if (!sym) return false;
    *out = sym;
    return true;
}

static bool lookup_either(const char *name, void **out) {
    return lookup(libavcodec_handle, name, out) || lookup(libavutil_handle, name, out);
}

bool andy_av_ensure(void) {
    pthread_mutex_lock(&load_lock);
    if (loaded) {
        const bool ok = load_ok;
        pthread_mutex_unlock(&load_lock);
        return ok;
    }
    loaded = true;
    /* Struct layouts (AVCodecContext/AVFrame/AVPacket) are ABI-tied to the header
     * majors used at compile time. Loading a different SONAME can resolve symbols
     * while misreading fields — only accept the build-matched majors. */
    char util_soname[32];
    char codec_soname[32];
    snprintf(util_soname, sizeof(util_soname), "libavutil.so.%d", LIBAVUTIL_VERSION_MAJOR);
    snprintf(codec_soname, sizeof(codec_soname), "libavcodec.so.%d", LIBAVCODEC_VERSION_MAJOR);
    const char *util_names[] = { util_soname, NULL };
    const char *codec_names[] = { codec_soname, NULL };
    libavutil_handle = open_sonames(util_names);
    libavcodec_handle = open_sonames(codec_names);
    load_ok = libavutil_handle && libavcodec_handle &&
        lookup_either("av_malloc", (void **) &p_av_malloc) &&
        lookup_either("avcodec_free_context", (void **) &p_avcodec_free_context) &&
        lookup_either("av_buffer_unref", (void **) &p_av_buffer_unref) &&
        lookup_either("avcodec_find_decoder_by_name", (void **) &p_avcodec_find_decoder_by_name) &&
        lookup_either("avcodec_alloc_context3", (void **) &p_avcodec_alloc_context3) &&
        lookup_either("av_hwdevice_ctx_create", (void **) &p_av_hwdevice_ctx_create) &&
        lookup_either("av_buffer_ref", (void **) &p_av_buffer_ref) &&
        lookup_either("av_opt_set", (void **) &p_av_opt_set) &&
        lookup_either("avcodec_open2", (void **) &p_avcodec_open2) &&
        lookup_either("av_packet_alloc", (void **) &p_av_packet_alloc) &&
        lookup_either("av_frame_alloc", (void **) &p_av_frame_alloc) &&
        lookup_either("av_packet_free", (void **) &p_av_packet_free) &&
        lookup_either("av_frame_free", (void **) &p_av_frame_free) &&
        lookup_either("avcodec_flush_buffers", (void **) &p_avcodec_flush_buffers) &&
        lookup_either("av_packet_unref", (void **) &p_av_packet_unref) &&
        lookup_either("av_new_packet", (void **) &p_av_new_packet) &&
        lookup_either("avcodec_send_packet", (void **) &p_avcodec_send_packet) &&
        lookup_either("avcodec_receive_frame", (void **) &p_avcodec_receive_frame) &&
        lookup_either("av_frame_unref", (void **) &p_av_frame_unref) &&
        lookup_either("av_hwframe_transfer_data", (void **) &p_av_hwframe_transfer_data) &&
        lookup_either("avcodec_parameters_alloc", (void **) &p_avcodec_parameters_alloc) &&
        lookup_either("avcodec_parameters_free", (void **) &p_avcodec_parameters_free) &&
        lookup_either("avcodec_parameters_to_context", (void **) &p_avcodec_parameters_to_context);
    if (!load_ok) {
        fprintf(
            stderr,
            "andy-mirror: failed to dlopen %s / %s (%s)\n",
            codec_soname,
            util_soname,
            dlerror(),
        );
        if (libavcodec_handle) dlclose(libavcodec_handle);
        if (libavutil_handle) dlclose(libavutil_handle);
        libavcodec_handle = NULL;
        libavutil_handle = NULL;
    }
    pthread_mutex_unlock(&load_lock);
    return load_ok;
}

void *andy_av_malloc(size_t size) {
    return andy_av_ensure() ? p_av_malloc(size) : NULL;
}

void andy_avcodec_free_context(AVCodecContext **avctx) {
    if (andy_av_ensure() && p_avcodec_free_context) p_avcodec_free_context(avctx);
}

void andy_av_buffer_unref(AVBufferRef **buf) {
    if (andy_av_ensure() && p_av_buffer_unref) p_av_buffer_unref(buf);
}

const AVCodec *andy_avcodec_find_decoder_by_name(const char *name) {
    return andy_av_ensure() ? p_avcodec_find_decoder_by_name(name) : NULL;
}

AVCodecContext *andy_avcodec_alloc_context3(const AVCodec *codec) {
    return andy_av_ensure() ? p_avcodec_alloc_context3(codec) : NULL;
}

int andy_av_hwdevice_ctx_create(AVBufferRef **device_ctx, enum AVHWDeviceType type, const char *device,
                                AVDictionary *opts, int flags) {
    return andy_av_ensure() ? p_av_hwdevice_ctx_create(device_ctx, type, device, opts, flags) : -1;
}

AVBufferRef *andy_av_buffer_ref(const AVBufferRef *buf) {
    return andy_av_ensure() ? p_av_buffer_ref(buf) : NULL;
}

int andy_av_opt_set(void *obj, const char *name, const char *val, int search_flags) {
    return andy_av_ensure() ? p_av_opt_set(obj, name, val, search_flags) : -1;
}

int andy_avcodec_open2(AVCodecContext *avctx, const AVCodec *codec, AVDictionary **options) {
    return andy_av_ensure() ? p_avcodec_open2(avctx, codec, options) : -1;
}

AVPacket *andy_av_packet_alloc(void) {
    return andy_av_ensure() ? p_av_packet_alloc() : NULL;
}

AVFrame *andy_av_frame_alloc(void) {
    return andy_av_ensure() ? p_av_frame_alloc() : NULL;
}

void andy_av_packet_free(AVPacket **pkt) {
    if (andy_av_ensure() && p_av_packet_free) p_av_packet_free(pkt);
}

void andy_av_frame_free(AVFrame **frame) {
    if (andy_av_ensure() && p_av_frame_free) p_av_frame_free(frame);
}

void andy_avcodec_flush_buffers(AVCodecContext *avctx) {
    if (andy_av_ensure() && p_avcodec_flush_buffers) p_avcodec_flush_buffers(avctx);
}

void andy_av_packet_unref(AVPacket *pkt) {
    if (andy_av_ensure() && p_av_packet_unref) p_av_packet_unref(pkt);
}

int andy_av_new_packet(AVPacket *pkt, int size) {
    return andy_av_ensure() ? p_av_new_packet(pkt, size) : -1;
}

int andy_avcodec_send_packet(AVCodecContext *avctx, const AVPacket *avpkt) {
    return andy_av_ensure() ? p_avcodec_send_packet(avctx, avpkt) : -1;
}

int andy_avcodec_receive_frame(AVCodecContext *avctx, AVFrame *frame) {
    return andy_av_ensure() ? p_avcodec_receive_frame(avctx, frame) : -1;
}

void andy_av_frame_unref(AVFrame *frame) {
    if (andy_av_ensure() && p_av_frame_unref) p_av_frame_unref(frame);
}

int andy_av_hwframe_transfer_data(AVFrame *dst, const AVFrame *src, int flags) {
    return andy_av_ensure() ? p_av_hwframe_transfer_data(dst, src, flags) : -1;
}

int andy_avcodec_parameters_from_extradata(AVCodecContext *avctx, const uint8_t *extradata, int extradata_size) {
    if (!andy_av_ensure() || !avctx || !extradata || extradata_size <= 0) return -1;
    AVCodecParameters *par = p_avcodec_parameters_alloc();
    if (!par) return -1;
    par->codec_type = AVMEDIA_TYPE_VIDEO;
    par->codec_id = AV_CODEC_ID_H264;
    uint8_t *copy = (uint8_t *) p_av_malloc((size_t) extradata_size + AV_INPUT_BUFFER_PADDING_SIZE);
    if (!copy) {
        p_avcodec_parameters_free(&par);
        return -1;
    }
    memcpy(copy, extradata, (size_t) extradata_size);
    memset(copy + extradata_size, 0, AV_INPUT_BUFFER_PADDING_SIZE);
    par->extradata = copy;
    par->extradata_size = extradata_size;
    const int rc = p_avcodec_parameters_to_context(avctx, par);
    p_avcodec_parameters_free(&par);
    return rc;
}

#include "andy_mirror_internal.h"
#include "andy_mirror_av.h"

#include <stdlib.h>
#include <string.h>

struct AndyNvdec {
    AVCodecContext *ctx;
    AVBufferRef *hw_device;
    AVPacket *packet;
    AVFrame *frame;
    AVFrame *sw_frame;
    bool hardware;
};

static enum AVPixelFormat pick_hw_format(AVCodecContext *ctx, const enum AVPixelFormat *pix_fmts) {
    (void) ctx;
    for (const enum AVPixelFormat *p = pix_fmts; *p != AV_PIX_FMT_NONE; ++p) {
        if (*p == AV_PIX_FMT_CUDA || *p == AV_PIX_FMT_NV12) return *p;
    }
    return pix_fmts[0];
}

static uint8_t *make_annexb_extradata(const uint8_t *sps, size_t sps_size, const uint8_t *pps, size_t pps_size,
                                      int *out_size) {
    const uint8_t start[4] = {0, 0, 0, 1};
    const int size = (int) (8 + sps_size + pps_size);
    uint8_t *data = (uint8_t *) malloc((size_t) size + AV_INPUT_BUFFER_PADDING_SIZE);
    if (!data) return NULL;
    memset(data, 0, (size_t) size + AV_INPUT_BUFFER_PADDING_SIZE);
    memcpy(data, start, 4);
    memcpy(data + 4, sps, sps_size);
    memcpy(data + 4 + sps_size, start, 4);
    memcpy(data + 8 + sps_size, pps, pps_size);
    *out_size = size;
    return data;
}

static void close_codec(AndyNvdec *decoder) {
    if (!decoder) return;
    if (decoder->ctx) andy_avcodec_free_context(&decoder->ctx);
    if (decoder->hw_device) andy_av_buffer_unref(&decoder->hw_device);
    decoder->ctx = NULL;
    decoder->hw_device = NULL;
    decoder->hardware = false;
}

static bool open_named(AndyNvdec *decoder, const char *name, enum AVHWDeviceType hw, const uint8_t *sps,
                       size_t sps_size, const uint8_t *pps, size_t pps_size) {
    const AVCodec *codec = andy_avcodec_find_decoder_by_name(name);
    if (!codec) return false;
    close_codec(decoder);
    decoder->ctx = andy_avcodec_alloc_context3(codec);
    if (!decoder->ctx) return false;
    andy_av_opt_set(decoder->ctx, "flags", "+low_delay", 0);
    andy_av_opt_set(decoder->ctx, "flags2", "+fast", 0);
    int extra_size = 0;
    uint8_t *extra = make_annexb_extradata(sps, sps_size, pps, pps_size, &extra_size);
    if (!extra) {
        close_codec(decoder);
        return false;
    }
    if (andy_avcodec_parameters_from_extradata(decoder->ctx, extra, extra_size) < 0) {
        free(extra);
        close_codec(decoder);
        return false;
    }
    free(extra);
    if (hw != AV_HWDEVICE_TYPE_NONE) {
        if (andy_av_hwdevice_ctx_create(&decoder->hw_device, hw, NULL, NULL, 0) < 0) {
            close_codec(decoder);
            return false;
        }
        decoder->ctx->hw_device_ctx = andy_av_buffer_ref(decoder->hw_device);
        decoder->ctx->get_format = pick_hw_format;
    }
    andy_av_opt_set(decoder->ctx, "tune", "zerolatency", AV_OPT_SEARCH_CHILDREN);
    andy_av_opt_set(decoder->ctx, "deint", "weave", AV_OPT_SEARCH_CHILDREN);
    if (andy_avcodec_open2(decoder->ctx, codec, NULL) < 0) {
        close_codec(decoder);
        return false;
    }
    decoder->hardware = hw != AV_HWDEVICE_TYPE_NONE;
    return true;
}

AndyNvdec *andy_nvdec_open(const uint8_t *sps, size_t sps_size, const uint8_t *pps, size_t pps_size) {
    if (!sps || !pps || !sps_size || !pps_size || !andy_av_ensure()) return NULL;
    AndyNvdec *decoder = (AndyNvdec *) calloc(1, sizeof(AndyNvdec));
    if (!decoder) return NULL;
    decoder->packet = andy_av_packet_alloc();
    decoder->frame = andy_av_frame_alloc();
    decoder->sw_frame = andy_av_frame_alloc();
    if (!decoder->packet || !decoder->frame || !decoder->sw_frame) {
        andy_nvdec_close(decoder);
        return NULL;
    }
    if (open_named(decoder, "h264_nvdec", AV_HWDEVICE_TYPE_CUDA, sps, sps_size, pps, pps_size) ||
        open_named(decoder, "h264_cuvid", AV_HWDEVICE_TYPE_CUDA, sps, sps_size, pps, pps_size) ||
        open_named(decoder, "h264", AV_HWDEVICE_TYPE_NONE, sps, sps_size, pps, pps_size)) {
        return decoder;
    }
    andy_nvdec_close(decoder);
    return NULL;
}

void andy_nvdec_close(AndyNvdec *decoder) {
    if (!decoder) return;
    close_codec(decoder);
    andy_av_packet_free(&decoder->packet);
    andy_av_frame_free(&decoder->frame);
    andy_av_frame_free(&decoder->sw_frame);
    free(decoder);
}

void andy_nvdec_reset(AndyNvdec *decoder) {
    if (!decoder || !decoder->ctx) return;
    andy_avcodec_flush_buffers(decoder->ctx);
}

bool andy_nvdec_is_hardware(const AndyNvdec *decoder) {
    return decoder && decoder->hardware;
}

static bool copy_decoded_frame(const AVFrame *frame, AndyFrameStore *out) {
    if (!frame || !out || frame->width <= 0 || frame->height <= 0) return false;
    if (frame->format == AV_PIX_FMT_NV12 || frame->format == AV_PIX_FMT_P010LE) {
        return andy_frame_store_copy_nv12(out, frame->width, frame->height, frame->data[0], frame->linesize[0],
                                          frame->data[1], frame->linesize[1], false);
    }
    if (frame->format == AV_PIX_FMT_YUV420P || frame->format == AV_PIX_FMT_YUVJ420P) {
        const bool full = frame->format == AV_PIX_FMT_YUVJ420P ||
            frame->color_range == AVCOL_RANGE_JPEG;
        return andy_frame_store_copy_yuv420p(out, frame->width, frame->height, frame->data[0], frame->linesize[0],
                                             frame->data[1], frame->linesize[1], frame->data[2], frame->linesize[2],
                                             full);
    }
    return false;
}

bool andy_nvdec_decode(AndyNvdec *decoder, const uint8_t *annexb, size_t length, AndyFrameStore *out) {
    if (!decoder || !decoder->ctx || !annexb || !length || !out) return false;
    andy_av_packet_unref(decoder->packet);
    if (andy_av_new_packet(decoder->packet, (int) length) < 0) return false;
    memcpy(decoder->packet->data, annexb, length);
    decoder->packet->flags |= AV_PKT_FLAG_KEY;
    int send = andy_avcodec_send_packet(decoder->ctx, decoder->packet);
    if (send < 0 && send != AVERROR(EAGAIN)) return false;
    bool got = false;
    while (true) {
        andy_av_frame_unref(decoder->frame);
        int rec = andy_avcodec_receive_frame(decoder->ctx, decoder->frame);
        if (rec == AVERROR(EAGAIN) || rec == AVERROR_EOF) break;
        if (rec < 0) return got;
        const AVFrame *usable = decoder->frame;
        if (decoder->frame->hw_frames_ctx || decoder->frame->format == AV_PIX_FMT_CUDA) {
            andy_av_frame_unref(decoder->sw_frame);
            if (andy_av_hwframe_transfer_data(decoder->sw_frame, decoder->frame, 0) < 0) continue;
            usable = decoder->sw_frame;
        }
        if (copy_decoded_frame(usable, out)) got = true;
    }
    return got;
}

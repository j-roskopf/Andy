#ifndef ANDY_MIRROR_AV_H
#define ANDY_MIRROR_AV_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include <libavcodec/avcodec.h>
#include <libavutil/dict.h>
#include <libavutil/buffer.h>
#include <libavutil/frame.h>
#include <libavutil/hwcontext.h>
#include <libavutil/mem.h>
#include <libavutil/opt.h>
#include <libavutil/pixfmt.h>
#include <libavutil/rational.h>

/** Loads libavutil/libavcodec via dlopen so the JNI .so has no FFmpeg DT_NEEDED. */
bool andy_av_ensure(void);

void *andy_av_malloc(size_t size);
void andy_avcodec_free_context(AVCodecContext **avctx);
void andy_av_buffer_unref(AVBufferRef **buf);
const AVCodec *andy_avcodec_find_decoder_by_name(const char *name);
AVCodecContext *andy_avcodec_alloc_context3(const AVCodec *codec);
int andy_av_hwdevice_ctx_create(AVBufferRef **device_ctx, enum AVHWDeviceType type, const char *device,
                                AVDictionary *opts, int flags);
AVBufferRef *andy_av_buffer_ref(const AVBufferRef *buf);
int andy_av_opt_set(void *obj, const char *name, const char *val, int search_flags);
int andy_avcodec_open2(AVCodecContext *avctx, const AVCodec *codec, AVDictionary **options);
AVPacket *andy_av_packet_alloc(void);
AVFrame *andy_av_frame_alloc(void);
void andy_av_packet_free(AVPacket **pkt);
void andy_av_frame_free(AVFrame **frame);
void andy_avcodec_flush_buffers(AVCodecContext *avctx);
void andy_av_packet_unref(AVPacket *pkt);
int andy_av_new_packet(AVPacket *pkt, int size);
int andy_avcodec_send_packet(AVCodecContext *avctx, const AVPacket *avpkt);
int andy_avcodec_receive_frame(AVCodecContext *avctx, AVFrame *frame);
void andy_av_frame_unref(AVFrame *frame);
int andy_av_hwframe_transfer_data(AVFrame *dst, const AVFrame *src, int flags);
int andy_avcodec_parameters_from_extradata(AVCodecContext *avctx, const uint8_t *extradata, int extradata_size);

#endif

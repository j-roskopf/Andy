#define _POSIX_C_SOURCE 200809L
#include "andy_mirror_internal.h"

#include <math.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

typedef struct {
    bool active;
    int64_t id;
    AndyNvdec *nvdec;
    bool decoder_is_hardware;
    uint8_t *sps;
    size_t sps_size;
    uint8_t *pps;
    size_t pps_size;
    uint64_t frames_presented;
    uint64_t dropped_frames;
    uint64_t pending_input_ticks;
    uint64_t transport_ingress_ticks;
    double input_to_present_millis[120];
    size_t input_to_present_count;
    double packet_to_present_millis[120];
    size_t packet_to_present_count;
    double transport_to_present_millis[120];
    size_t transport_to_present_count;
    bool latency_probe_enabled;
    float probe_left;
    float probe_top;
    float probe_width;
    float probe_height;
    uint64_t probe_transitions;
    bool ios_source_active;
    AndyFrameStore latest;
    pthread_mutex_t latest_lock;
    pthread_mutex_t decoder_lock;
    pthread_mutex_t stats_lock;
    int64_t presenter_ids[ANDY_MAX_PRESENTERS_PER_DECODER];
    int presenter_count;
} GpuDecoder;

typedef struct {
    bool active;
    int64_t id;
    int64_t decoder_id;
    Window window;
    AndyVkSwapchain *vk;
    AndyMirrorOverlay overlay;
    bool overlay_open;
    bool visible;
    bool fill_host;
    int content_width;
    int content_height;
    int pending_x;
    int pending_y;
    int pending_w;
    int pending_h;
    double pending_scale;
    int pending_parent_window;
} GpuPresenter;

static GpuDecoder decoders[ANDY_MAX_DECODERS];
static GpuPresenter presenters[ANDY_MAX_PRESENTERS];
static pthread_mutex_t hub_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t render_lock = PTHREAD_MUTEX_INITIALIZER;
static int64_t next_id = 1;
static int64_t ios_decoder_id = ANDY_HUB_INVALID_ID;

static uint64_t andy_now_ns(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t) ts.tv_sec * 1000000000ull + (uint64_t) ts.tv_nsec;
}

static int64_t allocate_id(void) {
    return next_id++;
}

static GpuDecoder *find_decoder(int64_t id) {
    for (int i = 0; i < ANDY_MAX_DECODERS; ++i) {
        if (decoders[i].active && decoders[i].id == id) return &decoders[i];
    }
    return NULL;
}

static GpuPresenter *find_presenter(int64_t id) {
    for (int i = 0; i < ANDY_MAX_PRESENTERS; ++i) {
        if (presenters[i].active && presenters[i].id == id) return &presenters[i];
    }
    return NULL;
}

static uint8_t *copy_bytes(const uint8_t *source, size_t length) {
    uint8_t *result = (uint8_t *) malloc(length);
    if (result) memcpy(result, source, length);
    return result;
}

static bool replace_parameter_set(uint8_t **target, size_t *target_size, const uint8_t *source, size_t length) {
    uint8_t *copy = copy_bytes(source, length);
    if (!copy) return false;
    free(*target);
    *target = copy;
    *target_size = length;
    return true;
}

static size_t start_code_length(const uint8_t *bytes, size_t offset, size_t length) {
    if (offset + 3 <= length && bytes[offset] == 0 && bytes[offset + 1] == 0 && bytes[offset + 2] == 1) return 3;
    if (offset + 4 <= length && bytes[offset] == 0 && bytes[offset + 1] == 0 && bytes[offset + 2] == 0 &&
        bytes[offset + 3] == 1) {
        return 4;
    }
    return 0;
}

static size_t find_start_code(const uint8_t *bytes, size_t offset, size_t length) {
    for (size_t i = offset; i + 3 <= length; ++i) {
        if (start_code_length(bytes, i, length)) return i;
    }
    return length;
}

static void push_sample(double *samples, size_t *count, double value) {
    if (*count < 120) {
        samples[(*count)++] = value;
    } else {
        memmove(samples, samples + 1, sizeof(double) * 119);
        samples[119] = value;
    }
}

static void record_packet_to_present(GpuDecoder *decoder, uint64_t packet_ticks) {
    if (!packet_ticks) return;
    const double elapsed = (double) (andy_now_ns() - packet_ticks) / 1000000.0;
    pthread_mutex_lock(&decoder->stats_lock);
    push_sample(decoder->packet_to_present_millis, &decoder->packet_to_present_count, elapsed);
    pthread_mutex_unlock(&decoder->stats_lock);
}

static void record_transport_to_present(GpuDecoder *decoder, uint64_t transport_ticks) {
    if (!transport_ticks) return;
    const double elapsed = (double) (andy_now_ns() - transport_ticks) / 1000000.0;
    pthread_mutex_lock(&decoder->stats_lock);
    push_sample(decoder->transport_to_present_millis, &decoder->transport_to_present_count, elapsed);
    pthread_mutex_unlock(&decoder->stats_lock);
}

static void record_input_to_present(GpuDecoder *decoder) {
    pthread_mutex_lock(&decoder->stats_lock);
    if (!decoder->pending_input_ticks) {
        pthread_mutex_unlock(&decoder->stats_lock);
        return;
    }
    const double elapsed = (double) (andy_now_ns() - decoder->pending_input_ticks) / 1000000.0;
    decoder->pending_input_ticks = 0;
    push_sample(decoder->input_to_present_millis, &decoder->input_to_present_count, elapsed);
    pthread_mutex_unlock(&decoder->stats_lock);
}

static float p95_from_samples(const double *samples, size_t count) {
    if (!count) return -1.0f;
    double sorted[120];
    memcpy(sorted, samples, count * sizeof(double));
    for (size_t i = 1; i < count; ++i) {
        double key = sorted[i];
        size_t j = i;
        while (j > 0 && sorted[j - 1] > key) {
            sorted[j] = sorted[j - 1];
            --j;
        }
        sorted[j] = key;
    }
    const size_t index = (size_t) fmin((double) count - 1.0, floor((double) count * 0.95));
    return (float) sorted[index];
}

static void remember_latest(GpuDecoder *decoder, const AndyFrameStore *frame) {
    pthread_mutex_lock(&decoder->latest_lock);
    andy_frame_store_clone(&decoder->latest, frame);
    pthread_mutex_unlock(&decoder->latest_lock);
}

static bool render_to_presenter(GpuPresenter *presenter, GpuDecoder *decoder, const AndyFrameStore *frame,
                                bool input_changed_probe, uint64_t packet_ticks, uint64_t transport_ticks,
                                bool record_presentation_metrics) {
    if (!presenter->overlay_open || !presenter->visible || !presenter->vk || !frame || !frame->plane0) return false;
    pthread_mutex_lock(&render_lock);
    if (!presenter->overlay_open || !presenter->vk) {
        pthread_mutex_unlock(&render_lock);
        return false;
    }
    AndyMirrorOverlay overlay = presenter->overlay;
    const bool ok = andy_vk_present(presenter->vk, frame, &overlay);
    pthread_mutex_unlock(&render_lock);
    if (!ok) return false;
    if (record_presentation_metrics) {
        pthread_mutex_lock(&decoder->stats_lock);
        decoder->frames_presented++;
        pthread_mutex_unlock(&decoder->stats_lock);
        record_packet_to_present(decoder, packet_ticks);
        record_transport_to_present(decoder, transport_ticks);
        if (input_changed_probe) record_input_to_present(decoder);
    }
    return true;
}

static void fan_out_render(GpuDecoder *decoder, const AndyFrameStore *frame, bool input_changed_probe,
                           uint64_t packet_ticks, uint64_t transport_ticks, bool record_presentation_metrics) {
    for (int i = 0; i < decoder->presenter_count; ++i) {
        GpuPresenter *presenter = find_presenter(decoder->presenter_ids[i]);
        if (presenter) {
            render_to_presenter(presenter, decoder, frame, input_changed_probe, packet_ticks, transport_ticks,
                                record_presentation_metrics);
        }
    }
}

static void apply_presenter_frame(GpuPresenter *presenter) {
    if (!presenter->overlay_open || !presenter->window) return;
    const int w = presenter->pending_w > 0 ? presenter->pending_w : 1;
    const int h = presenter->pending_h > 0 ? presenter->pending_h : 1;
    andy_x11_configure(presenter->window, presenter->pending_x, presenter->pending_y, w, h,
                       (unsigned long) (uint32_t) presenter->pending_parent_window, presenter->visible);
    if (presenter->vk) andy_vk_note_resize(presenter->vk, w, h);
}

static bool open_presenter_window(GpuPresenter *presenter) {
    if (presenter->overlay_open) return true;
    if (!andy_x11_init() || !andy_vk_init()) return false;
    Window window = None;
    if (!andy_x11_create_overlay(&window)) return false;
    AndyVkSwapchain *swapchain = andy_vk_attach(window);
    if (!swapchain) {
        andy_x11_destroy_overlay(window);
        return false;
    }
    presenter->window = window;
    presenter->vk = swapchain;
    presenter->overlay_open = true;
    presenter->visible = false;
    return true;
}

static void close_presenter_window(GpuPresenter *presenter) {
    pthread_mutex_lock(&render_lock);
    if (presenter->vk) {
        andy_vk_detach(presenter->vk);
        presenter->vk = NULL;
    }
    if (presenter->window) {
        andy_x11_destroy_overlay(presenter->window);
        presenter->window = None;
    }
    presenter->overlay_open = false;
    presenter->visible = false;
    pthread_mutex_unlock(&render_lock);
}

static void destroy_decoder_codec_locked(GpuDecoder *decoder) {
    andy_nvdec_close(decoder->nvdec);
    decoder->nvdec = NULL;
    decoder->decoder_is_hardware = false;
}

static void destroy_decoder_locked(GpuDecoder *decoder) {
    destroy_decoder_codec_locked(decoder);
    free(decoder->sps);
    free(decoder->pps);
    decoder->sps = NULL;
    decoder->pps = NULL;
    decoder->sps_size = 0;
    decoder->pps_size = 0;
    pthread_mutex_lock(&decoder->latest_lock);
    andy_frame_store_clear(&decoder->latest);
    pthread_mutex_unlock(&decoder->latest_lock);
}

static bool ensure_decoder_session(GpuDecoder *decoder) {
    if (decoder->nvdec) return true;
    if (!decoder->sps || !decoder->pps) return false;
    decoder->nvdec = andy_nvdec_open(decoder->sps, decoder->sps_size, decoder->pps, decoder->pps_size);
    decoder->decoder_is_hardware = decoder->nvdec && andy_nvdec_is_hardware(decoder->nvdec);
    return decoder->nvdec != NULL;
}

int64_t andy_hub_create_decoder(void) {
    pthread_mutex_lock(&hub_lock);
    for (int i = 0; i < ANDY_MAX_DECODERS; ++i) {
        if (!decoders[i].active) {
            memset(&decoders[i], 0, sizeof(GpuDecoder));
            decoders[i].active = true;
            decoders[i].id = allocate_id();
            pthread_mutex_init(&decoders[i].latest_lock, NULL);
            pthread_mutex_init(&decoders[i].decoder_lock, NULL);
            pthread_mutex_init(&decoders[i].stats_lock, NULL);
            const int64_t id = decoders[i].id;
            pthread_mutex_unlock(&hub_lock);
            return id;
        }
    }
    pthread_mutex_unlock(&hub_lock);
    return ANDY_HUB_INVALID_ID;
}

static void destroy_presenter_locked(GpuPresenter *presenter);

void andy_hub_destroy_decoder(int64_t decoder_id) {
    pthread_mutex_lock(&hub_lock);
    GpuDecoder *decoder = find_decoder(decoder_id);
    if (!decoder) {
        pthread_mutex_unlock(&hub_lock);
        return;
    }
    for (int i = 0; i < ANDY_MAX_PRESENTERS; ++i) {
        if (presenters[i].active && presenters[i].decoder_id == decoder_id) {
            destroy_presenter_locked(&presenters[i]);
        }
    }
    if (ios_decoder_id == decoder_id) ios_decoder_id = ANDY_HUB_INVALID_ID;
    pthread_mutex_lock(&decoder->decoder_lock);
    destroy_decoder_locked(decoder);
    pthread_mutex_unlock(&decoder->decoder_lock);
    pthread_mutex_destroy(&decoder->latest_lock);
    pthread_mutex_destroy(&decoder->decoder_lock);
    pthread_mutex_destroy(&decoder->stats_lock);
    decoder->active = false;
    pthread_mutex_unlock(&hub_lock);
}

int64_t andy_hub_create_presenter(int64_t decoder_id) {
    pthread_mutex_lock(&hub_lock);
    GpuDecoder *decoder = find_decoder(decoder_id);
    if (!decoder || decoder->presenter_count >= ANDY_MAX_PRESENTERS_PER_DECODER) {
        pthread_mutex_unlock(&hub_lock);
        return ANDY_HUB_INVALID_ID;
    }
    for (int i = 0; i < ANDY_MAX_PRESENTERS; ++i) {
        if (!presenters[i].active) {
            memset(&presenters[i], 0, sizeof(GpuPresenter));
            presenters[i].active = true;
            presenters[i].id = allocate_id();
            presenters[i].decoder_id = decoder_id;
            presenters[i].pending_w = 1;
            presenters[i].pending_h = 1;
            decoder->presenter_ids[decoder->presenter_count++] = presenters[i].id;
            const int64_t id = presenters[i].id;
            pthread_mutex_unlock(&hub_lock);
            return id;
        }
    }
    pthread_mutex_unlock(&hub_lock);
    return ANDY_HUB_INVALID_ID;
}

static void destroy_presenter_locked(GpuPresenter *presenter) {
    if (!presenter || !presenter->active) return;
    GpuDecoder *decoder = find_decoder(presenter->decoder_id);
    if (decoder) {
        for (int i = 0; i < decoder->presenter_count; ++i) {
            if (decoder->presenter_ids[i] == presenter->id) {
                decoder->presenter_ids[i] = decoder->presenter_ids[decoder->presenter_count - 1];
                decoder->presenter_count--;
                break;
            }
        }
    }
    close_presenter_window(presenter);
    presenter->active = false;
}

void andy_hub_destroy_presenter(int64_t presenter_id) {
    pthread_mutex_lock(&hub_lock);
    destroy_presenter_locked(find_presenter(presenter_id));
    pthread_mutex_unlock(&hub_lock);
}

bool andy_hub_open_presenter_overlay(int64_t presenter_id) {
    GpuPresenter *presenter = find_presenter(presenter_id);
    if (!presenter) return false;
    if (presenter->overlay_open) return true;
    return open_presenter_window(presenter);
}

void andy_hub_set_presenter_visible(int64_t presenter_id, bool visible) {
    GpuPresenter *presenter = find_presenter(presenter_id);
    if (!presenter || !presenter->window) return;
    presenter->visible = visible;
    andy_x11_set_visible(presenter->window, visible);
    if (visible) apply_presenter_frame(presenter);
}

void andy_hub_update_presenter_geometry(int64_t presenter_id, int x, int y, int width, int height, double scale,
                                        int parent_window_number) {
    GpuPresenter *presenter = find_presenter(presenter_id);
    if (!presenter || !presenter->overlay_open) return;
    presenter->pending_x = x;
    presenter->pending_y = y;
    presenter->pending_w = width > 0 ? width : 1;
    presenter->pending_h = height > 0 ? height : 1;
    presenter->pending_scale = scale;
    presenter->pending_parent_window = parent_window_number;
    apply_presenter_frame(presenter);
}

void andy_hub_set_presenter_fill_host(int64_t presenter_id, bool fill_host) {
    GpuPresenter *presenter = find_presenter(presenter_id);
    if (presenter) presenter->fill_host = fill_host;
}

void andy_hub_set_presenter_content_size(int64_t presenter_id, int width, int height) {
    GpuPresenter *presenter = find_presenter(presenter_id);
    if (!presenter) return;
    presenter->content_width = width;
    presenter->content_height = height;
}

bool andy_hub_present_solid_bgra(int64_t decoder_id, int width, int height, uint8_t blue, uint8_t green, uint8_t red,
                                 uint8_t alpha) {
    if (width <= 0 || height <= 0) return false;
    GpuDecoder *decoder = find_decoder(decoder_id);
    if (!decoder) return false;
    AndyFrameStore frame = {0};
    if (!andy_frame_store_set_bgra(&frame, width, height, blue, green, red, alpha)) return false;
    remember_latest(decoder, &frame);
    fan_out_render(decoder, &frame, false, 0, 0, true);
    andy_frame_store_clear(&frame);
    return true;
}

void andy_hub_repaint_presenter(int64_t presenter_id) {
    GpuPresenter *presenter = find_presenter(presenter_id);
    if (!presenter) return;
    GpuDecoder *decoder = find_decoder(presenter->decoder_id);
    if (!decoder) return;
    pthread_mutex_lock(&decoder->latest_lock);
    AndyFrameStore copy = {0};
    const bool ok = andy_frame_store_clone(&copy, &decoder->latest);
    pthread_mutex_unlock(&decoder->latest_lock);
    if (!ok) return;
    render_to_presenter(presenter, decoder, &copy, false, 0, 0, false);
    andy_frame_store_clear(&copy);
}

void andy_hub_repaint_decoder(int64_t decoder_id) {
    GpuDecoder *decoder = find_decoder(decoder_id);
    if (!decoder) return;
    pthread_mutex_lock(&decoder->latest_lock);
    AndyFrameStore copy = {0};
    const bool ok = andy_frame_store_clone(&copy, &decoder->latest);
    pthread_mutex_unlock(&decoder->latest_lock);
    if (!ok) return;
    fan_out_render(decoder, &copy, false, 0, 0, false);
    andy_frame_store_clear(&copy);
}

void andy_hub_reset_decoder_stream(int64_t decoder_id) {
    GpuDecoder *decoder = find_decoder(decoder_id);
    if (!decoder) return;
    pthread_mutex_lock(&decoder->decoder_lock);
    destroy_decoder_locked(decoder);
    pthread_mutex_unlock(&decoder->decoder_lock);
    pthread_mutex_lock(&decoder->stats_lock);
    decoder->frames_presented = 0;
    decoder->dropped_frames = 0;
    pthread_mutex_unlock(&decoder->stats_lock);
}

bool andy_hub_consume_h264(int64_t decoder_id, const uint8_t *bytes, size_t length) {
    GpuDecoder *decoder = find_decoder(decoder_id);
    if (!decoder || !bytes || !length) return false;
    pthread_mutex_lock(&decoder->decoder_lock);
    size_t first = find_start_code(bytes, 0, length);
    if (first == length) {
        pthread_mutex_unlock(&decoder->decoder_lock);
        return false;
    }
    bool reopen = false;
    for (size_t marker = first; marker < length;) {
        size_t marker_length = start_code_length(bytes, marker, length);
        size_t nal_start = marker + marker_length;
        size_t next_marker = find_start_code(bytes, nal_start, length);
        size_t nal_length = next_marker - nal_start;
        if (nal_length) {
            uint8_t type = bytes[nal_start] & 0x1f;
            if (type == 7) {
                if (!replace_parameter_set(&decoder->sps, &decoder->sps_size, bytes + nal_start, nal_length)) {
                    pthread_mutex_unlock(&decoder->decoder_lock);
                    return false;
                }
                reopen = true;
            } else if (type == 8) {
                if (!replace_parameter_set(&decoder->pps, &decoder->pps_size, bytes + nal_start, nal_length)) {
                    pthread_mutex_unlock(&decoder->decoder_lock);
                    return false;
                }
                reopen = true;
            }
        }
        marker = next_marker;
    }
    if (reopen && decoder->nvdec) {
        destroy_decoder_codec_locked(decoder);
    }
    if (!ensure_decoder_session(decoder)) {
        pthread_mutex_unlock(&decoder->decoder_lock);
        return true;
    }
    AndyFrameStore frame = {0};
    const uint64_t packet_ticks = andy_now_ns();
    const bool decoded = andy_nvdec_decode(decoder->nvdec, bytes, length, &frame);
    pthread_mutex_unlock(&decoder->decoder_lock);
    if (!decoded) return true;
    remember_latest(decoder, &frame);
    pthread_mutex_lock(&decoder->stats_lock);
    const uint64_t transport_ticks = decoder->transport_ingress_ticks;
    const bool probe = decoder->pending_input_ticks != 0;
    pthread_mutex_unlock(&decoder->stats_lock);
    fan_out_render(decoder, &frame, probe, packet_ticks, transport_ticks, true);
    andy_frame_store_clear(&frame);
    return true;
}

void andy_hub_record_input(int64_t decoder_id) {
    GpuDecoder *decoder = find_decoder(decoder_id);
    if (!decoder) return;
    pthread_mutex_lock(&decoder->stats_lock);
    decoder->pending_input_ticks = andy_now_ns();
    pthread_mutex_unlock(&decoder->stats_lock);
}

void andy_hub_record_transport_ingress(int64_t decoder_id) {
    GpuDecoder *decoder = find_decoder(decoder_id);
    if (!decoder) return;
    pthread_mutex_lock(&decoder->stats_lock);
    decoder->transport_ingress_ticks = andy_now_ns();
    pthread_mutex_unlock(&decoder->stats_lock);
}

uint64_t andy_hub_frames_presented(int64_t decoder_id) {
    GpuDecoder *decoder = find_decoder(decoder_id);
    if (!decoder) return 0;
    pthread_mutex_lock(&decoder->stats_lock);
    const uint64_t count = decoder->frames_presented;
    pthread_mutex_unlock(&decoder->stats_lock);
    return count;
}

bool andy_hub_has_decoded_frame(int64_t decoder_id) {
    GpuDecoder *decoder = find_decoder(decoder_id);
    if (!decoder) return false;
    pthread_mutex_lock(&decoder->latest_lock);
    const bool ready = decoder->latest.plane0 != NULL;
    pthread_mutex_unlock(&decoder->latest_lock);
    return ready;
}

bool andy_hub_is_hardware_ready(int64_t decoder_id) {
    GpuDecoder *decoder = find_decoder(decoder_id);
    if (!decoder) return false;
    pthread_mutex_lock(&decoder->decoder_lock);
    const bool ready = decoder->nvdec != NULL && decoder->decoder_is_hardware;
    pthread_mutex_unlock(&decoder->decoder_lock);
    return ready;
}

void andy_hub_configure_latency_probe(int64_t decoder_id, float left, float top, float width, float height) {
    GpuDecoder *decoder = find_decoder(decoder_id);
    if (!decoder) return;
    pthread_mutex_lock(&decoder->stats_lock);
    decoder->latency_probe_enabled = true;
    decoder->probe_left = left;
    decoder->probe_top = top;
    decoder->probe_width = width;
    decoder->probe_height = height;
    pthread_mutex_unlock(&decoder->stats_lock);
}

uint64_t andy_hub_latency_probe_transitions(int64_t decoder_id) {
    GpuDecoder *decoder = find_decoder(decoder_id);
    if (!decoder) return 0;
    pthread_mutex_lock(&decoder->stats_lock);
    const uint64_t count = decoder->probe_transitions;
    pthread_mutex_unlock(&decoder->stats_lock);
    return count;
}

void andy_hub_update_overlay(int64_t presenter_id, bool grid_enabled, float grid_step_x, float grid_step_y,
                             float grid_r, float grid_g, float grid_b, float grid_a, bool ruler_enabled,
                             float ruler_x, float ruler_y, float ruler_r, float ruler_g, float ruler_b, float ruler_a,
                             float source_width, float source_height, bool picker_enabled, float highlight_left,
                             float highlight_top, float highlight_right, float highlight_bottom, float content_zoom,
                             float content_pan_x, float content_pan_y) {
    GpuPresenter *presenter = find_presenter(presenter_id);
    if (!presenter) return;
    presenter->overlay.grid[0] = grid_enabled ? 1.0f : 0.0f;
    presenter->overlay.grid[1] = grid_step_x;
    presenter->overlay.grid[2] = grid_step_y;
    presenter->overlay.grid_color[0] = grid_r;
    presenter->overlay.grid_color[1] = grid_g;
    presenter->overlay.grid_color[2] = grid_b;
    presenter->overlay.grid_color[3] = grid_a;
    presenter->overlay.ruler[0] = ruler_enabled ? 1.0f : 0.0f;
    presenter->overlay.ruler[1] = ruler_x;
    presenter->overlay.ruler[2] = ruler_y;
    presenter->overlay.ruler_color[0] = ruler_r;
    presenter->overlay.ruler_color[1] = ruler_g;
    presenter->overlay.ruler_color[2] = ruler_b;
    presenter->overlay.ruler_color[3] = ruler_a;
    presenter->overlay.source_size[0] = source_width;
    presenter->overlay.source_size[1] = source_height;
    presenter->overlay.source_size[2] = fmaxf(1.0f, content_zoom);
    presenter->overlay.source_size[3] = 0.0f;
    presenter->overlay.format_flags[2] = fmaxf(0.0f, fminf(1.0f, content_pan_x));
    presenter->overlay.format_flags[3] = fmaxf(0.0f, fminf(1.0f, content_pan_y));
    presenter->overlay.picker[0] = picker_enabled ? 1.0f : 0.0f;
    if (!picker_enabled) presenter->overlay.picker[3] = 0.0f;
    presenter->overlay.highlight[0] = highlight_left;
    presenter->overlay.highlight[1] = highlight_top;
    presenter->overlay.highlight[2] = highlight_right;
    presenter->overlay.highlight[3] = highlight_bottom;
    andy_hub_repaint_presenter(presenter_id);
}

void andy_hub_update_picker_point(int64_t presenter_id, float normalized_x, float normalized_y, bool visible) {
    GpuPresenter *presenter = find_presenter(presenter_id);
    if (!presenter) return;
    presenter->overlay.picker[1] = normalized_x;
    presenter->overlay.picker[2] = normalized_y;
    presenter->overlay.picker[3] = visible ? 1.0f : 0.0f;
    andy_hub_repaint_presenter(presenter_id);
}

static int yuv_to_argb_pixel(int yy, int uu, int vv) {
    int red = (298 * yy + 409 * vv + 128) >> 8;
    int green = (298 * yy - 100 * uu - 208 * vv + 128) >> 8;
    int blue = (298 * yy + 516 * uu + 128) >> 8;
    if (red < 0) red = 0; else if (red > 255) red = 255;
    if (green < 0) green = 0; else if (green > 255) green = 255;
    if (blue < 0) blue = 0; else if (blue > 255) blue = 255;
    return (int) (0xff000000u | ((unsigned) red << 16) | ((unsigned) green << 8) | (unsigned) blue);
}

int andy_hub_inspect_pixel(int64_t decoder_id, float normalized_x, float normalized_y) {
    GpuDecoder *decoder = find_decoder(decoder_id);
    if (!decoder) return -1;
    pthread_mutex_lock(&decoder->latest_lock);
    const AndyFrameStore *frame = &decoder->latest;
    if (!frame->plane0 || frame->width <= 0 || frame->height <= 0) {
        pthread_mutex_unlock(&decoder->latest_lock);
        return -1;
    }
    const int x = (int) fmaxf(0.0f, fminf((float) frame->width - 1.0f, normalized_x * (float) frame->width));
    const int y = (int) fmaxf(0.0f, fminf((float) frame->height - 1.0f, normalized_y * (float) frame->height));
    int color = -1;
    if (frame->is_bgra) {
        const uint8_t *pixel = frame->plane0 + (size_t) y * frame->stride0 + (size_t) x * 4;
        color = (int) (0xff000000u | ((unsigned) pixel[2] << 16) | ((unsigned) pixel[1] << 8) | (unsigned) pixel[0]);
    } else if (frame->plane1) {
        const int yy = frame->plane0[(size_t) y * frame->stride0 + (size_t) x] - 16;
        const size_t uv_x = (size_t) (x / 2) * 2;
        const size_t uv_y = (size_t) (y / 2);
        const uint8_t *uv = frame->plane1 + uv_y * frame->stride1 + uv_x;
        color = yuv_to_argb_pixel(yy, uv[0] - 128, uv[1] - 128);
    }
    pthread_mutex_unlock(&decoder->latest_lock);
    return color;
}

bool andy_hub_latest_frame_size(int64_t decoder_id, int32_t *out_width, int32_t *out_height) {
    if (!out_width || !out_height) return false;
    *out_width = 0;
    *out_height = 0;
    GpuDecoder *decoder = find_decoder(decoder_id);
    if (!decoder) return false;
    pthread_mutex_lock(&decoder->latest_lock);
    const bool ok = decoder->latest.plane0 && decoder->latest.width > 0 && decoder->latest.height > 0;
    if (ok) {
        *out_width = decoder->latest.width;
        *out_height = decoder->latest.height;
    }
    pthread_mutex_unlock(&decoder->latest_lock);
    return ok;
}

int32_t *andy_hub_copy_latest_frame_argb(int64_t decoder_id, int32_t *out_width, int32_t *out_height) {
    if (!out_width || !out_height) return NULL;
    *out_width = 0;
    *out_height = 0;
    GpuDecoder *decoder = find_decoder(decoder_id);
    if (!decoder) return NULL;
    pthread_mutex_lock(&decoder->latest_lock);
    AndyFrameStore copy = {0};
    const bool ok = andy_frame_store_clone(&copy, &decoder->latest);
    pthread_mutex_unlock(&decoder->latest_lock);
    if (!ok) return NULL;
    const size_t count = (size_t) copy.width * (size_t) copy.height;
    int32_t *dest = (int32_t *) malloc(count * sizeof(int32_t));
    if (!dest) {
        andy_frame_store_clear(&copy);
        return NULL;
    }
    if (copy.is_bgra) {
        for (int y = 0; y < copy.height; ++y) {
            const uint8_t *row = copy.plane0 + (size_t) y * copy.stride0;
            int32_t *out_row = dest + (size_t) y * (size_t) copy.width;
            for (int x = 0; x < copy.width; ++x) {
                const uint8_t *pixel = row + x * 4;
                out_row[x] = (int32_t) (0xff000000u | ((unsigned) pixel[2] << 16) | ((unsigned) pixel[1] << 8) |
                                        (unsigned) pixel[0]);
            }
        }
    } else {
        for (int y = 0; y < copy.height; ++y) {
            const uint8_t *y_row = copy.plane0 + (size_t) y * copy.stride0;
            const uint8_t *uv_row = copy.plane1 + (size_t) (y / 2) * copy.stride1;
            int32_t *out_row = dest + (size_t) y * (size_t) copy.width;
            for (int x = 0; x < copy.width; ++x) {
                const int yy = y_row[x] - 16;
                const size_t uv_x = (size_t) (x / 2) * 2;
                out_row[x] = yuv_to_argb_pixel(yy, uv_row[uv_x] - 128, uv_row[uv_x + 1] - 128);
            }
        }
    }
    *out_width = copy.width;
    *out_height = copy.height;
    andy_frame_store_clear(&copy);
    return dest;
}

float andy_hub_p95_input_to_present_millis(int64_t decoder_id) {
    GpuDecoder *decoder = find_decoder(decoder_id);
    if (!decoder) return -1.0f;
    pthread_mutex_lock(&decoder->stats_lock);
    const float value = p95_from_samples(decoder->input_to_present_millis, decoder->input_to_present_count);
    pthread_mutex_unlock(&decoder->stats_lock);
    return value;
}

float andy_hub_p95_packet_to_present_millis(int64_t decoder_id) {
    GpuDecoder *decoder = find_decoder(decoder_id);
    if (!decoder) return -1.0f;
    pthread_mutex_lock(&decoder->stats_lock);
    const float value = p95_from_samples(decoder->packet_to_present_millis, decoder->packet_to_present_count);
    pthread_mutex_unlock(&decoder->stats_lock);
    return value;
}

float andy_hub_p95_transport_to_present_millis(int64_t decoder_id) {
    GpuDecoder *decoder = find_decoder(decoder_id);
    if (!decoder) return -1.0f;
    pthread_mutex_lock(&decoder->stats_lock);
    const float value = p95_from_samples(decoder->transport_to_present_millis, decoder->transport_to_present_count);
    pthread_mutex_unlock(&decoder->stats_lock);
    return value;
}

void andy_hub_set_ios_decoder(int64_t decoder_id) {
    ios_decoder_id = decoder_id;
}

void andy_hub_clear_ios_decoder(int64_t decoder_id) {
    pthread_mutex_lock(&hub_lock);
    if (ios_decoder_id == decoder_id) ios_decoder_id = ANDY_HUB_INVALID_ID;
    pthread_mutex_unlock(&hub_lock);
}

int64_t andy_hub_ios_decoder(void) {
    return ios_decoder_id;
}

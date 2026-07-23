#ifndef ANDY_MIRROR_HUB_H
#define ANDY_MIRROR_HUB_H

#import <CoreVideo/CoreVideo.h>
#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define ANDY_HUB_INVALID_ID 0LL

int64_t andy_hub_create_decoder(void);
void andy_hub_destroy_decoder(int64_t decoder_id);

int64_t andy_hub_create_presenter(int64_t decoder_id);
void andy_hub_destroy_presenter(int64_t presenter_id);

bool andy_hub_open_presenter_overlay(int64_t presenter_id);
void andy_hub_set_presenter_visible(int64_t presenter_id, bool visible);
void andy_hub_update_presenter_geometry(int64_t presenter_id, int x, int y, int width, int height,
                                          double scale, int parent_window_number);
void andy_hub_set_presenter_fill_host(int64_t presenter_id, bool fill_host);
void andy_hub_set_presenter_content_size(int64_t presenter_id, int width, int height);
void andy_hub_repaint_presenter(int64_t presenter_id);

bool andy_hub_consume_h264(int64_t decoder_id, const uint8_t *bytes, size_t length);
void andy_hub_render_pixel_buffer(int64_t decoder_id, CVPixelBufferRef pixels, bool input_changed_probe,
                                  uint64_t packet_ticks, uint64_t transport_ticks,
                                  bool record_presentation_metrics);
/** Test/helper: fills a BGRA frame and fans it out to attached presenters. */
bool andy_hub_present_solid_bgra(int64_t decoder_id, int width, int height,
                                 uint8_t blue, uint8_t green, uint8_t red, uint8_t alpha);
void andy_hub_remember_latest_pixels(int64_t decoder_id, CVPixelBufferRef pixels);
void andy_hub_repaint_decoder(int64_t decoder_id);

void andy_hub_record_input(int64_t decoder_id);
void andy_hub_record_transport_ingress(int64_t decoder_id);
uint64_t andy_hub_frames_presented(int64_t decoder_id);
bool andy_hub_is_hardware_ready(int64_t decoder_id);
bool andy_hub_latency_probe_changed(int64_t decoder_id, CVPixelBufferRef pixels);
void andy_hub_set_ios_source_active(int64_t decoder_id, bool active);
bool andy_hub_is_ios_source_active(int64_t decoder_id);

void andy_hub_configure_latency_probe(int64_t decoder_id, float left, float top, float width, float height);
uint64_t andy_hub_latency_probe_transitions(int64_t decoder_id);

void andy_hub_update_overlay(int64_t presenter_id, bool grid_enabled, float grid_step_x, float grid_step_y,
                             float grid_r, float grid_g, float grid_b, float grid_a, bool ruler_enabled,
                             float ruler_x, float ruler_y, float ruler_r, float ruler_g, float ruler_b, float ruler_a,
                             float source_width, float source_height, bool picker_enabled, float highlight_left,
                             float highlight_top, float highlight_right, float highlight_bottom);
void andy_hub_update_picker_point(int64_t presenter_id, float normalized_x, float normalized_y, bool visible);
int andy_hub_inspect_pixel(int64_t decoder_id, float normalized_x, float normalized_y);
float andy_hub_p95_input_to_present_millis(int64_t decoder_id);
float andy_hub_p95_packet_to_present_millis(int64_t decoder_id);
float andy_hub_p95_transport_to_present_millis(int64_t decoder_id);

/** Binds iOS capture callbacks to [decoder_id]. ANDY_HUB_INVALID_ID disables hub routing. */
void andy_hub_set_ios_decoder(int64_t decoder_id);
/** Clears the iOS routing slot only if it currently points at [decoder_id]. */
void andy_hub_clear_ios_decoder(int64_t decoder_id);
int64_t andy_hub_ios_decoder(void);

#ifdef __cplusplus
}
#endif

#endif

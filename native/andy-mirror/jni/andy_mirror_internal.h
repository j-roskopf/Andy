#ifndef ANDY_MIRROR_INTERNAL_H
#define ANDY_MIRROR_INTERNAL_H

#import <CoreVideo/CoreVideo.h>
#include <stdbool.h>
#include <stdint.h>

void andy_mirror_remember_latest_pixels(CVPixelBufferRef pixels);
void andy_mirror_render_pixel_buffer(CVPixelBufferRef pixels, bool input_changed_probe,
                                     uint64_t packet_ticks, uint64_t transport_ticks,
                                     bool record_presentation_metrics);
void andy_mirror_record_input(void);
uint64_t andy_mirror_frames_presented(void);
bool andy_mirror_latency_probe_changed(CVPixelBufferRef pixels);
void andy_mirror_set_ios_source_active(bool active);
bool andy_mirror_is_ios_source_active(void);
void andy_mirror_repaint_latest_pixels(void);

#endif

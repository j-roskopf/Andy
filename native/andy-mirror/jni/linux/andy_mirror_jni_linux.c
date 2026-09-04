#include "andy_mirror_hub.h"

#include <jni.h>
#include <stdlib.h>

#define GPU_JNI_METHOD(name) Java_app_andy_desktop_service_mirror_GpuMirrorJni_##name

JNIEXPORT jlong JNICALL GPU_JNI_METHOD(nativeCreateDecoder)(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return (jlong) andy_hub_create_decoder();
}

JNIEXPORT void JNICALL GPU_JNI_METHOD(nativeDestroyDecoder)(JNIEnv *env, jclass clazz, jlong decoder_id) {
    (void) env;
    (void) clazz;
    andy_hub_destroy_decoder((int64_t) decoder_id);
}

JNIEXPORT jlong JNICALL GPU_JNI_METHOD(nativeCreatePresenter)(JNIEnv *env, jclass clazz, jlong decoder_id) {
    (void) env;
    (void) clazz;
    return (jlong) andy_hub_create_presenter((int64_t) decoder_id);
}

JNIEXPORT void JNICALL GPU_JNI_METHOD(nativeDestroyPresenter)(JNIEnv *env, jclass clazz, jlong presenter_id) {
    (void) env;
    (void) clazz;
    andy_hub_destroy_presenter((int64_t) presenter_id);
}

JNIEXPORT jboolean JNICALL GPU_JNI_METHOD(nativeOpenPresenterOverlay)(JNIEnv *env, jclass clazz, jlong presenter_id) {
    (void) env;
    (void) clazz;
    return andy_hub_open_presenter_overlay((int64_t) presenter_id) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL GPU_JNI_METHOD(nativeSetPresenterVisible)(JNIEnv *env, jclass clazz, jlong presenter_id,
                                                                 jboolean visible) {
    (void) env;
    (void) clazz;
    andy_hub_set_presenter_visible((int64_t) presenter_id, visible == JNI_TRUE);
}

JNIEXPORT void JNICALL GPU_JNI_METHOD(nativeUpdatePresenterGeometry)(JNIEnv *env, jclass clazz, jlong presenter_id,
                                                                     jint x, jint y, jint width, jint height,
                                                                     jdouble scale, jint parent_window_number) {
    (void) env;
    (void) clazz;
    andy_hub_update_presenter_geometry((int64_t) presenter_id, x, y, width, height, scale, parent_window_number);
}

JNIEXPORT void JNICALL GPU_JNI_METHOD(nativeSetPresenterFillHost)(JNIEnv *env, jclass clazz, jlong presenter_id,
                                                                  jboolean fill_host) {
    (void) env;
    (void) clazz;
    andy_hub_set_presenter_fill_host((int64_t) presenter_id, fill_host == JNI_TRUE);
}

JNIEXPORT void JNICALL GPU_JNI_METHOD(nativeSetPresenterContentSize)(JNIEnv *env, jclass clazz, jlong presenter_id,
                                                                     jint width, jint height) {
    (void) env;
    (void) clazz;
    andy_hub_set_presenter_content_size((int64_t) presenter_id, width, height);
}

JNIEXPORT void JNICALL GPU_JNI_METHOD(nativeRepaintPresenter)(JNIEnv *env, jclass clazz, jlong presenter_id) {
    (void) env;
    (void) clazz;
    andy_hub_repaint_presenter((int64_t) presenter_id);
}

JNIEXPORT void JNICALL GPU_JNI_METHOD(nativeResetDecoderStream)(JNIEnv *env, jclass clazz, jlong decoder_id) {
    (void) env;
    (void) clazz;
    andy_hub_reset_decoder_stream((int64_t) decoder_id);
}

JNIEXPORT jboolean JNICALL GPU_JNI_METHOD(nativeConsumeH264)(JNIEnv *env, jclass clazz, jlong decoder_id,
                                                             jbyteArray packet) {
    (void) clazz;
    if (!packet) return JNI_FALSE;
    jsize length = (*env)->GetArrayLength(env, packet);
    if (length <= 0) return JNI_FALSE;
    jbyte *bytes = (*env)->GetByteArrayElements(env, packet, NULL);
    if (!bytes) return JNI_FALSE;
    const bool ok = andy_hub_consume_h264((int64_t) decoder_id, (const uint8_t *) bytes, (size_t) length);
    (*env)->ReleaseByteArrayElements(env, packet, bytes, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL GPU_JNI_METHOD(nativePresentSolidBgra)(JNIEnv *env, jclass clazz, jlong decoder_id,
                                                                  jint width, jint height, jint blue, jint green,
                                                                  jint red, jint alpha) {
    (void) env;
    (void) clazz;
    return andy_hub_present_solid_bgra((int64_t) decoder_id, width, height, (uint8_t) (blue & 0xff),
                                       (uint8_t) (green & 0xff), (uint8_t) (red & 0xff), (uint8_t) (alpha & 0xff))
               ? JNI_TRUE
               : JNI_FALSE;
}

JNIEXPORT void JNICALL GPU_JNI_METHOD(nativeRecordInput)(JNIEnv *env, jclass clazz, jlong decoder_id) {
    (void) env;
    (void) clazz;
    andy_hub_record_input((int64_t) decoder_id);
}

JNIEXPORT void JNICALL GPU_JNI_METHOD(nativeRecordTransportIngress)(JNIEnv *env, jclass clazz, jlong decoder_id) {
    (void) env;
    (void) clazz;
    andy_hub_record_transport_ingress((int64_t) decoder_id);
}

JNIEXPORT jlong JNICALL GPU_JNI_METHOD(nativeFramesPresented)(JNIEnv *env, jclass clazz, jlong decoder_id) {
    (void) env;
    (void) clazz;
    return (jlong) andy_hub_frames_presented((int64_t) decoder_id);
}

JNIEXPORT jboolean JNICALL GPU_JNI_METHOD(nativeHasDecodedFrame)(JNIEnv *env, jclass clazz, jlong decoder_id) {
    (void) env;
    (void) clazz;
    return andy_hub_has_decoded_frame((int64_t) decoder_id) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL GPU_JNI_METHOD(nativeIsHardwareReady)(JNIEnv *env, jclass clazz, jlong decoder_id) {
    (void) env;
    (void) clazz;
    return andy_hub_is_hardware_ready((int64_t) decoder_id) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL GPU_JNI_METHOD(nativeSetIosDecoder)(JNIEnv *env, jclass clazz, jlong decoder_id,
                                                            jboolean simulator) {
    (void) env;
    (void) clazz;
    andy_hub_set_ios_decoder((int64_t) decoder_id, simulator == JNI_TRUE);
}

JNIEXPORT jlong JNICALL GPU_JNI_METHOD(nativeIosDecoder)(JNIEnv *env, jclass clazz, jboolean simulator) {
    (void) env;
    (void) clazz;
    return (jlong) (simulator == JNI_TRUE ? andy_hub_ios_sim_decoder() : andy_hub_ios_device_decoder());
}

JNIEXPORT void JNICALL GPU_JNI_METHOD(nativeClearIosDecoder)(JNIEnv *env, jclass clazz, jlong decoder_id) {
    (void) env;
    (void) clazz;
    andy_hub_clear_ios_decoder((int64_t) decoder_id);
}

JNIEXPORT void JNICALL GPU_JNI_METHOD(nativeUpdatePresenterOverlay)(
    JNIEnv *env, jclass clazz, jlong presenter_id, jboolean grid_enabled, jfloat grid_step_x, jfloat grid_step_y,
    jfloat grid_r, jfloat grid_g, jfloat grid_b, jfloat grid_a, jboolean ruler_enabled, jfloat ruler_x, jfloat ruler_y,
    jfloat ruler_r, jfloat ruler_g, jfloat ruler_b, jfloat ruler_a, jfloat source_width, jfloat source_height,
    jboolean picker_enabled, jfloat highlight_left, jfloat highlight_top, jfloat highlight_right,
    jfloat highlight_bottom, jfloat content_zoom, jfloat content_pan_x, jfloat content_pan_y) {
    (void) env;
    (void) clazz;
    andy_hub_update_overlay((int64_t) presenter_id, grid_enabled == JNI_TRUE, grid_step_x, grid_step_y, grid_r, grid_g,
                            grid_b, grid_a, ruler_enabled == JNI_TRUE, ruler_x, ruler_y, ruler_r, ruler_g, ruler_b,
                            ruler_a, source_width, source_height, picker_enabled == JNI_TRUE, highlight_left,
                            highlight_top, highlight_right, highlight_bottom, content_zoom, content_pan_x,
                            content_pan_y);
}

JNIEXPORT void JNICALL GPU_JNI_METHOD(nativeUpdatePresenterPickerPoint)(JNIEnv *env, jclass clazz, jlong presenter_id,
                                                                        jfloat normalized_x, jfloat normalized_y,
                                                                        jboolean visible) {
    (void) env;
    (void) clazz;
    andy_hub_update_picker_point((int64_t) presenter_id, normalized_x, normalized_y, visible == JNI_TRUE);
}

JNIEXPORT jint JNICALL GPU_JNI_METHOD(nativeInspectPixel)(JNIEnv *env, jclass clazz, jlong decoder_id,
                                                          jfloat normalized_x, jfloat normalized_y) {
    (void) env;
    (void) clazz;
    return (jint) andy_hub_inspect_pixel((int64_t) decoder_id, normalized_x, normalized_y);
}

JNIEXPORT jintArray JNICALL GPU_JNI_METHOD(nativeCopyLatestFrameArgb)(JNIEnv *env, jclass clazz, jlong decoder_id,
                                                                      jintArray out_size) {
    (void) clazz;
    if (!out_size || (*env)->GetArrayLength(env, out_size) < 2) return NULL;
    int32_t width = 0;
    int32_t height = 0;
    int32_t *pixels = andy_hub_copy_latest_frame_argb((int64_t) decoder_id, &width, &height);
    if (!pixels || width <= 0 || height <= 0) {
        free(pixels);
        return NULL;
    }
    const jsize count = (jsize) width * (jsize) height;
    jintArray result = (*env)->NewIntArray(env, count);
    if (!result) {
        free(pixels);
        return NULL;
    }
    (*env)->SetIntArrayRegion(env, result, 0, count, (const jint *) pixels);
    free(pixels);
    jint size_values[2] = {(jint) width, (jint) height};
    (*env)->SetIntArrayRegion(env, out_size, 0, 2, size_values);
    return result;
}

JNIEXPORT jboolean JNICALL GPU_JNI_METHOD(nativeLatestFrameSize)(JNIEnv *env, jclass clazz, jlong decoder_id,
                                                                 jintArray out_size) {
    (void) clazz;
    if (!out_size || (*env)->GetArrayLength(env, out_size) < 2) return JNI_FALSE;
    int32_t width = 0;
    int32_t height = 0;
    if (!andy_hub_latest_frame_size((int64_t) decoder_id, &width, &height)) return JNI_FALSE;
    jint values[2] = {(jint) width, (jint) height};
    (*env)->SetIntArrayRegion(env, out_size, 0, 2, values);
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL GPU_JNI_METHOD(nativeReadWindowDesktop)(JNIEnv *env, jclass clazz, jint window_number) {
    (void) env;
    (void) clazz;
    return (jint) andy_hub_window_desktop(window_number);
}

JNIEXPORT void JNICALL GPU_JNI_METHOD(nativeRefreshAllPresenters)(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    andy_hub_refresh_all_presenters();
}

JNIEXPORT void JNICALL GPU_JNI_METHOD(nativeSuppressForDesktopSwitch)(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    andy_hub_suppress_presenters_for_desktop_switch();
}

JNIEXPORT jboolean JNICALL GPU_JNI_METHOD(nativeShouldResumePresentersAfterDesktopSwitch)(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return andy_hub_should_resume_presenters_after_desktop_switch() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL GPU_JNI_METHOD(nativeResumeAfterDesktopSwitch)(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    andy_hub_resume_presenters_after_desktop_switch();
}

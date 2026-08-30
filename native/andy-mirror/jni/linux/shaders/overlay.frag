#version 450
layout(location = 0) in vec2 v_uv;
layout(location = 0) out vec4 out_color;

layout(std140, set = 0, binding = 0) uniform Overlay {
    vec4 grid;
    vec4 ruler;
    vec4 highlight;
    vec4 grid_color;
    vec4 ruler_color;
    vec4 picker;
    vec4 source_size;
    vec4 format_flags;
} overlay;

layout(set = 0, binding = 1) uniform sampler2D y_tex;
layout(set = 0, binding = 2) uniform sampler2D uv_tex;

vec3 sampled_rgb(vec2 coord, bool is_bgra, bool full_range_yuv) {
    if (is_bgra) {
        return texture(y_tex, coord).rgb;
    }
    float y_sample = texture(y_tex, coord).r;
    float y = full_range_yuv ? y_sample : (1.1643 * (y_sample - 0.0625));
    vec2 uv = texture(uv_tex, coord).rg - vec2(0.5, 0.5);
    return vec3(y + 1.5958 * uv.y, y - 0.39173 * uv.x - 0.81290 * uv.y, y + 2.017 * uv.x);
}

void main() {
    bool is_bgra = overlay.format_flags.x > 0.5;
    bool full_range_yuv = overlay.format_flags.y > 0.5;
    float zoom = max(overlay.source_size.z, 1.0);
    vec2 pan = overlay.format_flags.zw;
    vec2 content_uv = v_uv / zoom + pan * (1.0 - 1.0 / zoom);
    vec3 rgb = sampled_rgb(content_uv, is_bgra, full_range_yuv);
    if (overlay.grid.x > 0.5 && overlay.grid.y > 0.0) {
        float step_x = max(overlay.grid.y, 0.0001);
        float step_y = max(overlay.grid.z > 0.0 ? overlay.grid.z : overlay.grid.y, 0.0001);
        float fx = abs(fract(content_uv.x / step_x));
        float fy = abs(fract(content_uv.y / step_y));
        fx = min(fx, 1.0 - fx);
        fy = min(fy, 1.0 - fy);
        float x_width = max(fwidth(content_uv.x / step_x), 0.001);
        float y_width = max(fwidth(content_uv.y / step_y), 0.001);
        float line = max(1.0 - smoothstep(0.0, x_width, fx), 1.0 - smoothstep(0.0, y_width, fy));
        rgb = mix(rgb, overlay.grid_color.rgb, overlay.grid_color.a * line);
    }
    if (overlay.ruler.x > 0.5) {
        float x_width = max(fwidth(content_uv.x), 0.001);
        float y_width = max(fwidth(content_uv.y), 0.001);
        float vertical = 1.0 - smoothstep(x_width * 0.6, x_width * 1.6, abs(content_uv.x - overlay.ruler.y));
        float horizontal = 1.0 - smoothstep(y_width * 0.6, y_width * 1.6, abs(content_uv.y - overlay.ruler.z));
        rgb = mix(rgb, overlay.ruler_color.rgb, overlay.ruler_color.a * max(vertical, horizontal));
    }
    if (overlay.highlight.z > overlay.highlight.x && overlay.highlight.w > overlay.highlight.y) {
        bool inside = content_uv.x >= overlay.highlight.x && content_uv.x <= overlay.highlight.z &&
                      content_uv.y >= overlay.highlight.y && content_uv.y <= overlay.highlight.w;
        vec2 tex_size = vec2(textureSize(y_tex, 0));
        float edge = min(
            min(abs(content_uv.x - overlay.highlight.x) * tex_size.x, abs(content_uv.x - overlay.highlight.z) * tex_size.x),
            min(abs(content_uv.y - overlay.highlight.y) * tex_size.y, abs(content_uv.y - overlay.highlight.w) * tex_size.y));
        if (inside && edge < 2.0) rgb = vec3(0.85, 0.44, 0.29);
    }
    if (overlay.picker.x > 0.5 && overlay.picker.w > 0.5) {
        vec2 delta = content_uv - overlay.picker.yz;
        vec2 tex_size = vec2(textureSize(y_tex, 0));
        float aspect = tex_size.x / max(1.0, tex_size.y);
        float lens_distance = length(vec2(delta.x * aspect, delta.y));
        float lens_radius = 0.092 / zoom;
        if (lens_distance <= lens_radius) {
            if (lens_distance >= lens_radius - 0.004 / zoom) {
                rgb = vec3(0.85, 0.44, 0.29);
            } else {
                vec2 magnified_uv = overlay.picker.yz + delta / 5.0;
                rgb = sampled_rgb(magnified_uv, is_bgra, full_range_yuv);
            }
        }
    }
    out_color = vec4(rgb, 1.0);
}

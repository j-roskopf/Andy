//! Headless Mermaid renderer for Andy chats.
//!
//! Rust (merman) owns parse/layout/raster. The JVM paints the PNG and owns
//! zoom/pan. JNI is a string-in / PNG-out boundary. Skia SVGDOM cannot apply
//! mermaid's CSS classes, so rasterizing in merman (resvg) is required to show
//! a diagram at all.

use std::collections::hash_map::DefaultHasher;
use std::hash::{Hash, Hasher};
use std::panic::{catch_unwind, AssertUnwindSafe};

use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jbyteArray};
use jni::JNIEnv;
use merman::svg::{
    export::{RasterFitBox, RasterOptions},
    sanitize_svg_id, HeadlessRenderer, HostTheme, HostThemePreset, Presentation,
};

fn render_png(source: &str, dark: bool) -> Result<Vec<u8>, String> {
    let trimmed = source.trim();
    if trimmed.is_empty() {
        return Err("empty mermaid source".to_string());
    }
    let mut hasher = DefaultHasher::new();
    trimmed.hash(&mut hasher);
    dark.hash(&mut hasher);
    let diagram_id = sanitize_svg_id(&format!("andy-{:x}", hasher.finish()));
    let preset = if dark {
        HostThemePreset::EditorDark
    } else {
        HostThemePreset::EditorLight
    };
    let renderer = HeadlessRenderer::new()
        .with_lenient_parsing()
        .with_diagram_id(&diagram_id)
        .with_presentation(Presentation::new().with_theme(HostTheme::from_preset(preset)));
    let options = RasterOptions::default()
        .with_fit_to(RasterFitBox::width(1280))
        .with_scale(2.0)
        .with_background(if dark { "#1a1a1a" } else { "#ffffff" });
    renderer
        .render_png_sync(trimmed, &options)
        .map_err(|err| err.to_string())?
        .ok_or_else(|| "no diagram in source".to_string())
}

fn throw_err(env: &mut JNIEnv, message: &str) {
    let _ = env.throw_new("java/lang/IllegalStateException", message);
}

#[no_mangle]
pub extern "system" fn Java_app_andy_desktop_mermaid_MermaidJni_nativeRenderPng<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    source: JString<'local>,
    dark: jboolean,
) -> jbyteArray {
    let src: String = match env.get_string(&source) {
        Ok(value) => value.into(),
        Err(_) => {
            throw_err(&mut env, "mermaid source is not valid UTF-8");
            return std::ptr::null_mut();
        }
    };
    let rendered = catch_unwind(AssertUnwindSafe(|| render_png(&src, dark != 0)));
    match rendered {
        Ok(Ok(png)) => env
            .byte_array_from_slice(&png)
            .map(|value| value.into_raw())
            .unwrap_or_else(|_| {
                throw_err(&mut env, "failed to allocate PNG jbyteArray");
                std::ptr::null_mut()
            }),
        Ok(Err(message)) => {
            throw_err(&mut env, &message);
            std::ptr::null_mut()
        }
        Err(_) => {
            throw_err(&mut env, "mermaid renderer panicked");
            std::ptr::null_mut()
        }
    }
}

#[cfg(test)]
mod tests {
    use super::render_png;

    #[test]
    fn renders_flowchart_png() {
        let png = render_png("flowchart TD\nA-->B", true).expect("render");
        assert!(png.starts_with(b"\x89PNG\r\n\x1a\n"), "expected PNG magic");
        assert!(png.len() > 256, "png too small: {}", png.len());
    }

    #[test]
    fn renders_subgraph_flowchart_with_html_labels() {
        let source = r#"flowchart TB
  subgraph clients["Clients"]
    GUI["Compose Desktop GUI<br/>Andy.app"]
    CLI["Rust CLI"]
  end
  subgraph control["Control plane"]
    MODE{"GUI launch"}
    SOCK["Unix socket"]
  end
  GUI --> MODE
  MODE -->|"no live andyd"| SOCK
  CLI -->|"auto-starts andyd"| SOCK
"#;
        let png = render_png(source, true).expect("render subgraph flowchart");
        assert!(png.starts_with(b"\x89PNG\r\n\x1a\n"), "expected PNG magic");
        assert!(png.len() > 512, "png too small: {}", png.len());
    }
}

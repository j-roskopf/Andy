use std::env;
use std::fs;
use std::path::PathBuf;

/// Inject Andy's GUI/release version (`andy.versionName`) for `andy --version`.
/// Cargo.toml cannot use calendar versions like `2026.0811.0422` (leading zeros).
fn main() {
    let version = env::var("ANDY_VERSION")
        .ok()
        .filter(|v| !v.trim().is_empty())
        .or_else(read_gradle_version_name)
        .unwrap_or_else(|| env!("CARGO_PKG_VERSION").to_string());

    println!("cargo:rerun-if-env-changed=ANDY_VERSION");
    println!("cargo:rustc-env=ANDY_VERSION_NAME={version}");

    if let Some(props) = gradle_properties_path() {
        println!("cargo:rerun-if-changed={}", props.display());
    }
}

fn gradle_properties_path() -> Option<PathBuf> {
    let manifest_dir = PathBuf::from(env::var("CARGO_MANIFEST_DIR").ok()?);
    let props = manifest_dir.join("../../gradle.properties");
    props.canonicalize().ok().filter(|p| p.is_file())
}

fn read_gradle_version_name() -> Option<String> {
    let props = gradle_properties_path()?;
    let text = fs::read_to_string(props).ok()?;
    for line in text.lines() {
        let line = line.trim();
        if line.starts_with('#') || line.is_empty() {
            continue;
        }
        let Some((key, value)) = line.split_once('=') else {
            continue;
        };
        if key.trim() == "andy.versionName" {
            let version = value.trim();
            if !version.is_empty() {
                return Some(version.to_string());
            }
        }
    }
    None
}

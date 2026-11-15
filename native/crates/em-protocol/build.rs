use std::{env, fs, path::PathBuf};

fn main() {
    let properties = PathBuf::from(env::var_os("CARGO_MANIFEST_DIR").expect("crate directory"))
        .join("../../../gradle.properties");
    println!("cargo:rerun-if-changed={}", properties.display());
    println!("cargo:rerun-if-env-changed=ELECTROMAGNETISM_MOD_VERSION");
    let content = fs::read_to_string(properties).expect("read existing mod metadata properties");
    let version = content
        .lines()
        .find_map(|line| line.trim().strip_prefix("mod_version="))
        .expect("mod_version metadata")
        .trim();
    assert!(!version.is_empty());
    let effective = env::var("ELECTROMAGNETISM_MOD_VERSION").unwrap_or_else(|_| version.into());
    assert!(!effective.trim().is_empty() && !effective.contains(['\n', '\r']));
    println!("cargo:rustc-env=ELECTROMAGNETISM_MOD_VERSION={effective}");
}

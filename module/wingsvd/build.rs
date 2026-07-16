use std::error::Error;

// The daemon and the app generate from the same rootd.proto rather than keeping two
// copies in step by hand - the pattern appcontrol.proto already uses between the app
// and vk-turn-proxy.
fn main() -> Result<(), Box<dyn Error>> {
    let proto_dir = "../../app/src/main/proto";
    let proto = format!("{proto_dir}/rootd.proto");
    println!("cargo:rerun-if-changed={proto}");

    prost_build::Config::new()
        .protoc_executable(protoc_bin_vendored::protoc_bin_path()?)
        .compile_protos(&[proto], &[proto_dir])?;
    Ok(())
}

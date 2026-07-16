plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.parcelize) apply false
    alias(libs.plugins.wire) apply false
}

// Entry point for the optional root helper zip, so it is one `./gradlew` away like
// everything else here. It stays a wrapper rather than a real cargo task: the daemon
// lives in its own repo (external/WINGSV_Magisk) and has to build standalone, so the
// build logic belongs there and would only rot if it were duplicated here.
//
// Not wired into any assemble task on purpose. The module is not an APK input, and the
// submodule may not even be checked out - a missing helper must never fail an app build.
val buildRootModule by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the flashable root helper module into dist/ (needs cargo-ndk + NDK)."
    val script = rootProject.file("external/WINGSV_Magisk/build-module.sh")
    doFirst {
        check(script.isFile) {
            "external/WINGSV_Magisk is not checked out: git submodule update --init external/WINGSV_Magisk"
        }
    }
    commandLine(script.absolutePath, rootProject.file("dist").absolutePath)
}

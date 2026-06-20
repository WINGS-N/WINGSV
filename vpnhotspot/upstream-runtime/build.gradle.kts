import java.io.File
import java.util.Properties
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.jvm.tasks.Jar

abstract class BuildDaemonNativeLibsTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDir: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val protoDir: DirectoryProperty

    @get:Input
    abstract val cargoProfile: Property<String>

    @get:Input
    abstract val androidPlatform: Property<Int>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Internal
    abstract val targetDir: DirectoryProperty

    @get:Internal
    abstract val ndkRoot: DirectoryProperty

    @TaskAction
    fun build() {
        val cargoDir = sourceDir.get().asFile
        val targetDir = targetDir.get().asFile
        outputDir.get().asFile.apply {
            deleteRecursively()
            mkdirs()
        }
        val profile = cargoProfile.get()
        val rustFlags = listOf(
            "--remap-path-prefix=${System.getProperty("user.home").trimEnd('/')}/=",
            "--remap-path-prefix=${cargoDir.absolutePath}=.",
            "--remap-path-prefix=${cargoDir.absolutePath}/=",
        ).joinToString("\u001F")
        val targets = listOf(
            "arm64-v8a" to "aarch64-linux-android",
            "armeabi-v7a" to "armv7-linux-androideabi",
        )
        for ((abi, target) in targets) {
            val command = mutableListOf(
                "cargo", "ndk", "--target", abi, "--platform", androidPlatform.get().toString(),
                "build", "--locked", "--bin", "vpnhotspotd"
            ).apply {
                if (profile == "release") add("--release")
            }
            val process = ProcessBuilder(command).directory(cargoDir).redirectErrorStream(true).apply {
                environment().run {
                    this["CARGO_BUILD_TARGET_DIR"] = targetDir.absolutePath
                    this["CARGO_ENCODED_RUSTFLAGS"] = rustFlags
                    this["ANDROID_NDK_HOME"] = ndkRoot.get().asFile.absolutePath
                    this["ANDROID_NDK_ROOT"] = ndkRoot.get().asFile.absolutePath
                }
            }.start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor() == 0) {
                "cargo build failed for $target\n$output"
            }
            val binary = targetDir.resolve("$target/$profile/vpnhotspotd")
            check(binary.isFile) { "Missing daemon binary: ${binary.absolutePath}" }
            outputDir.file("$abi/libvpnhotspotd.so").get().asFile.apply {
                parentFile.mkdirs()
                binary.copyTo(this, overwrite = true)
            }
        }
    }
}

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.wire)
}

android {
    namespace = "be.mygod.vpnhotspot"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        buildConfig = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    sourceSets {
        named("main") {
            manifest.srcFile("src/main/AndroidManifest.xml")
        }
    }
}

// AGP 9.2 dropped `java.filter.include` on `LibraryAndroidSourceSet`. To pick
// the subset of VPNHotspot mobile sources we need, copy them into a generated
// directory and feed that to variant.sources.java via the new
// androidComponents API.
val mobileSourceRoot = layout.projectDirectory.dir("../../external/VPNHotspot/mobile/src/main/java")
val mobileResRoot = layout.projectDirectory.dir("../../external/VPNHotspot/mobile/src/main/res")
val mobileIncludedSources = listOf(
    "be/mygod/vpnhotspot/RoutingManager.kt",
    "be/mygod/vpnhotspot/io/Utils.kt",
    "be/mygod/vpnhotspot/net/InetAddressComparator.kt",
    "be/mygod/vpnhotspot/net/IpSecForwardPolicyCommand.kt",
    "be/mygod/vpnhotspot/net/MacAddressCompat.kt",
    "be/mygod/vpnhotspot/net/Netd.kt",
    "be/mygod/vpnhotspot/net/NetlinkNeighbour.kt",
    "be/mygod/vpnhotspot/net/Routing.kt",
    "be/mygod/vpnhotspot/net/TetherOffloadManager.kt",
    "be/mygod/vpnhotspot/net/TetherStates.kt",
    "be/mygod/vpnhotspot/net/TetherType.kt",
    "be/mygod/vpnhotspot/net/TetheringManagerCompat.kt",
    "be/mygod/vpnhotspot/net/monitor/TetherTimeoutMonitor.kt",
    "be/mygod/vpnhotspot/net/monitor/TrafficRecorder.kt",
    "be/mygod/vpnhotspot/net/monitor/Upstreams.kt",
    "be/mygod/vpnhotspot/net/wifi/SoftApCapability.kt",
    "be/mygod/vpnhotspot/net/wifi/SoftApConfigurationCompat.kt",
    "be/mygod/vpnhotspot/net/wifi/SoftApInfo.kt",
    "be/mygod/vpnhotspot/net/wifi/WifiApManager.kt",
    "be/mygod/vpnhotspot/net/wifi/WifiClient.kt",
    "be/mygod/vpnhotspot/net/wifi/WifiDoubleLock.kt",
    "be/mygod/vpnhotspot/net/wifi/WifiSsidCompat.kt",
    "be/mygod/vpnhotspot/root/MiscCommands.kt",
    "be/mygod/vpnhotspot/root/RootManager.kt",
    "be/mygod/vpnhotspot/root/TetheringCommands.kt",
    "be/mygod/vpnhotspot/root/daemon/ClientConfig.kt",
    "be/mygod/vpnhotspot/root/daemon/DaemonAbi.kt",
    "be/mygod/vpnhotspot/root/daemon/DaemonCommands.kt",
    "be/mygod/vpnhotspot/root/daemon/DaemonController.kt",
    "be/mygod/vpnhotspot/root/daemon/DaemonIpc.kt",
    "be/mygod/vpnhotspot/util/BinderCallbackFlow.kt",
    "be/mygod/vpnhotspot/util/ConstantLookup.kt",
    "be/mygod/vpnhotspot/util/DeviceStorageApp.kt",
    "be/mygod/vpnhotspot/util/Services.kt",
    "be/mygod/vpnhotspot/util/Utils.kt",
    "be/mygod/vpnhotspot/widget/SmartSnackbar.kt"
)

val copyMobileSources by tasks.registering(Sync::class) {
    from(mobileSourceRoot) {
        for (path in mobileIncludedSources) include(path)
    }
    into(layout.buildDirectory.dir("generated/mobileSources/java"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.java?.addStaticSourceDirectory(
            copyMobileSources.get().destinationDir.absolutePath
        )
        variant.sources.res?.addStaticSourceDirectory(mobileResRoot.asFile.absolutePath)
        val variantTitle = variant.name.replaceFirstChar { it.uppercase() }
        val sdkDir = run {
            val localProps = rootProject.file("local.properties")
            val fromLocal = if (localProps.exists()) {
                val props = Properties()
                localProps.inputStream().use { props.load(it) }
                props.getProperty("sdk.dir")
            } else {
                null
            }
            fromLocal
                ?: System.getenv("ANDROID_HOME")
                ?: System.getenv("ANDROID_SDK_ROOT")
                ?: error("Android SDK not found")
        }
        val ndkParent = File(sdkDir, "ndk")
        val ndkRootDir = ndkParent.listFiles()?.filter { it.isDirectory }
            ?.maxByOrNull { it.name }
            ?: error("No Android NDK installed under $ndkParent")
        val daemonTask = tasks.register<BuildDaemonNativeLibsTask>("build${variantTitle}DaemonNativeLibs") {
            sourceDir.set(layout.projectDirectory.dir("../../external/VPNHotspot/mobile/src/main/rust/vpnhotspotd"))
            protoDir.set(layout.projectDirectory.dir("../../external/VPNHotspot/mobile/src/main/proto"))
            cargoProfile.set(if (variant.buildType == "release") "release" else "debug")
            androidPlatform.set(29)
            outputDir.set(layout.buildDirectory.dir("generated/nativeLibs/daemon/${variant.name}"))
            targetDir.set(layout.buildDirectory.dir("rust/vpnhotspotd"))
            ndkRoot.set(ndkRootDir)
        }
        variant.sources.jniLibs?.addGeneratedSourceDirectory(daemonTask, BuildDaemonNativeLibsTask::outputDir)
    }
}

afterEvaluate {
    tasks.matching {
        val name = it.name
        (name.startsWith("compile") && (name.endsWith("Kotlin") || name.endsWith("JavaWithJavac"))) ||
            name.startsWith("extract") && name.endsWith("Annotations") ||
            name.startsWith("lint") ||
            name.startsWith("kspKotlin") ||
            name.startsWith("javadoc")
    }.configureEach {
        dependsOn(copyMobileSources)
    }
}

wire {
    kotlin {
        enumMode = "sealed_class"
        rpcRole = "none"
    }
    sourcePath {
        srcDir("../../external/VPNHotspot/mobile/src/main/proto")
    }
}

val hiddenApiStubAnnotations by configurations.creating
val compileHiddenApiStubs by tasks.registering(JavaCompile::class) {
    source("../../external/VPNHotspot/mobile/src/hiddenApiStubs/java")
    classpath = files(androidComponents.sdkComponents.bootClasspath) + hiddenApiStubAnnotations
    destinationDirectory.set(layout.buildDirectory.dir("intermediates/hiddenApiStubs/classes"))
    sourceCompatibility = "11"
    targetCompatibility = "11"
}
val hiddenApiStubsJar by tasks.registering(Jar::class) {
    from(compileHiddenApiStubs.flatMap { it.destinationDirectory })
    archiveFileName.set("hidden-api-stubs.jar")
    destinationDirectory.set(layout.buildDirectory.dir("libs"))
}

dependencies {
    compileOnly(files(hiddenApiStubsJar.flatMap { it.archiveFile }))
    hiddenApiStubAnnotations(libs.androidx.annotation)
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.collection)
    implementation(libs.kotlinx.coroutines.android)
    implementation("be.mygod.librootkotlinx:librootkotlinx:${libs.versions.librootkotlinxV3.get()}")
    implementation("androidx.browser:browser:1.9.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.preference:preference:1.2.1")
    implementation("io.ktor:ktor-io-jvm:${libs.versions.ktorIo.get()}")
    implementation("com.squareup.okio:okio:${libs.versions.okio.get()}")
    implementation("com.squareup.wire:wire-runtime:${libs.versions.wire.get()}")
    implementation("com.jakewharton.timber:timber:${libs.versions.timber.get()}")
    implementation("org.jetbrains.kotlinx:kotlinx-collections-immutable:0.5.0")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
}

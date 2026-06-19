import org.gradle.jvm.tasks.Jar

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

    sourceSets.getByName("main").apply {
        manifest.srcFile("src/main/AndroidManifest.xml")
        res.srcDirs(
            "../../external/VPNHotspot/mobile/src/main/res"
        )
        java.srcDirs(
            "src/main/java",
            "../../external/VPNHotspot/mobile/src/main/java"
        )
        java.filter.include(
            listOf(
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
        )
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

plugins {
    alias(libs.plugins.android.library)
    id("kotlin-parcelize")
}

android {
    namespace = "be.mygod.vpnhotspot"
    compileSdk = 36
    compileSdkMinor = 1

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild.cmake.arguments += listOf("-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
    }

    buildFeatures {
        buildConfig = true
        dataBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    sourceSets.getByName("main").apply {
        manifest.srcFile("src/main/AndroidManifest.xml")
        java.srcDirs(
            "src/main/java",
            "../external/VPNHotspot/mobile/src/main/java"
        )
        java.filter.include(listOf(
            "be/mygod/vpnhotspot/RoutingManager.kt",
            "be/mygod/vpnhotspot/net/**/*.kt",
            "be/mygod/vpnhotspot/root/Jni.kt",
            "be/mygod/vpnhotspot/root/MiscCommands.kt",
            "be/mygod/vpnhotspot/root/RootManager.kt",
            "be/mygod/vpnhotspot/root/RoutingCommands.kt",
            "be/mygod/vpnhotspot/util/ConstantLookup.kt",
            "be/mygod/vpnhotspot/util/Events.kt",
            "be/mygod/vpnhotspot/util/RootSession.kt",
            "be/mygod/vpnhotspot/util/Services.kt",
            "be/mygod/vpnhotspot/util/UnblockCentral.kt"
        ))
        java.filter.exclude(listOf(
            "be/mygod/vpnhotspot/net/RemoveUidInterfaceRuleCommand.kt",
            "be/mygod/vpnhotspot/net/wifi/P2pSupplicantConfiguration.kt",
            "be/mygod/vpnhotspot/net/wifi/SoftApCapability.kt",
            "be/mygod/vpnhotspot/net/wifi/SoftApConfigurationCompat.kt",
            "be/mygod/vpnhotspot/net/wifi/SoftApInfo.kt",
            "be/mygod/vpnhotspot/net/wifi/VendorElements.kt",
            "be/mygod/vpnhotspot/net/wifi/WifiApDialogFragment.kt",
            "be/mygod/vpnhotspot/net/wifi/WifiApManager.kt",
            "be/mygod/vpnhotspot/net/wifi/WifiClient.kt",
            "be/mygod/vpnhotspot/net/wifi/WifiP2pManagerHelper.kt",
            "be/mygod/vpnhotspot/net/wifi/WifiSsidCompat.kt",
            "be/mygod/vpnhotspot/net/monitor/TetherTimeoutMonitor.kt",
            "be/mygod/vpnhotspot/root/MiscCommands.kt",
            "be/mygod/vpnhotspot/root/RootManager.kt",
            "be/mygod/vpnhotspot/root/RoutingCommands.kt"
        ))
        res.srcDirs(
            "src/main/res"
        )
    }

    externalNativeBuild {
        cmake {
            path = file("../external/VPNHotspot/mobile/src/main/cpp/CMakeLists.txt")
        }
    }
}

dependencies {
    implementation("androidx.activity:activity:1.11.0")
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("androidx.browser:browser:1.9.0")
    implementation("androidx.collection:collection:1.5.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.fragment:fragment-ktx:1.8.9")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.9.4")
    implementation("androidx.preference:preference:1.2.1")
    implementation("be.mygod.librootkotlinx:librootkotlinx:1.2.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("com.linkedin.dexmaker:dexmaker:2.28.6")
    implementation("dnsjava:dnsjava:3.6.3")
    implementation("io.ktor:ktor-network-jvm:3.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-collections-immutable:0.4.0")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
    implementation("com.jakewharton.timber:timber:5.0.1")
}

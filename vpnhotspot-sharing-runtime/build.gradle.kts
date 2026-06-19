plugins {
    alias(libs.plugins.android.library)
    id("kotlin-parcelize")
}

android {
    namespace = "wings.v.vpnhotspot.sharing.runtime"
    compileSdk = 36
    compileSdkMinor = 1

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
}

dependencies {
    implementation(project(":vpnhotspot-upstream-runtime"))
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("be.mygod.librootkotlinx:librootkotlinx:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.jakewharton.timber:timber:5.0.1")
}

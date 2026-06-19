plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "wings.v.vpnhotspot.sharing.runtime"
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
}

dependencies {
    implementation(project(":vpnhotspot:upstream-runtime"))
    implementation(libs.androidx.annotation)
    implementation(libs.kotlinx.coroutines.android)
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.preference:preference:1.2.1")
}

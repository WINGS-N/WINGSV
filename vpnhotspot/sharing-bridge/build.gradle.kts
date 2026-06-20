plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "wings.v.vpnhotspot.sharing.bridge"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        buildConfig = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":vpnhotspot:sharing-runtime"))
    implementation(libs.androidx.annotation)
}

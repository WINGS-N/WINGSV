plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "wings.v.vpnhotspot.sharing.bridge"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

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

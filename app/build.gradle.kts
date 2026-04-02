import java.util.Properties
import java.io.File

plugins {
    alias(libs.plugins.android.application)
}

val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("keystore.properties")
if (keystorePropertiesFile.exists()) {
    keystorePropertiesFile.inputStream().use(keystoreProperties::load)
}
val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use(localProperties::load)
}
val hasReleaseSigning = listOf("storeFile", "storePassword", "keyAlias", "keyPassword").all {
    !keystoreProperties.getProperty(it).isNullOrBlank()
}
val vkTurnRepoDir = rootProject.file("external/vk-turn-proxy")
val vkTurnProtoSourceDir = vkTurnRepoDir.resolve("proto")
val vkTurnGeneratedProtoGo = vkTurnRepoDir.resolve("sessionproto/session.pb.go")
val generatedVkTurnJniLibsDir = layout.buildDirectory.dir("generated/vkturn/jniLibs")
val generatedVkTurnBinary = generatedVkTurnJniLibsDir.map { File(it.asFile, "arm64-v8a/libvkturn.so") }
val protoSourceDir = project.file("src/main/proto")
val generatedProtoJavaDir = layout.buildDirectory.dir("generated/source/proto/main/java")

fun resolveAndroidSdkDir(): File {
    val candidates = listOf(
        System.getenv("ANDROID_SDK_ROOT"),
        System.getenv("ANDROID_HOME"),
        localProperties.getProperty("sdk.dir")
    ).filterNotNull().map(::File)
    return candidates.firstOrNull { it.isDirectory }
        ?: error("Android SDK not found. Set sdk.dir in local.properties or ANDROID_SDK_ROOT.")
}

fun resolveAndroidNdkDir(): File {
    val direct = listOf(
        System.getenv("ANDROID_NDK_HOME"),
        System.getenv("ANDROID_NDK_ROOT"),
        localProperties.getProperty("ndk.dir")
    ).filterNotNull().map(::File)
    direct.firstOrNull { it.isDirectory }?.let { return it }

    val installed = resolveAndroidSdkDir()
        .resolve("ndk")
        .listFiles()
        ?.filter { it.isDirectory }
        ?.sortedByDescending { it.name }
        .orEmpty()
    return installed.firstOrNull()
        ?: error("Android NDK not found. Install it under the Android SDK or set ANDROID_NDK_HOME.")
}

fun resolveVkTurnAndroidClang(): File {
    val ndkDir = resolveAndroidNdkDir()
    val prebuilt = ndkDir.resolve("toolchains/llvm/prebuilt/linux-x86_64/bin")
    val clang = prebuilt.resolve("aarch64-linux-android21-clang")
    return clang.takeIf { it.isFile }
        ?: error("Android clang not found at ${clang.absolutePath}")
}

val buildVkTurnProxyArm64 by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds libvkturn.so from external/vk-turn-proxy for Android arm64 via Go + NDK."

    inputs.files(fileTree(vkTurnRepoDir) {
        exclude(".git/**")
        exclude("**/build/**")
    })
    inputs.property("vkTurnGoToolchain", "go1.25.5")
    outputs.file(generatedVkTurnBinary)

    doFirst {
        check(vkTurnRepoDir.isDirectory) {
            "vk-turn-proxy submodule not found at ${vkTurnRepoDir.absolutePath}. Run git submodule update --init --recursive."
        }

        val outputFile = generatedVkTurnBinary.get()
        val goModCacheDir = rootProject.file(".gradle/vkturn/go-mod-cache")
        val goCacheDir = rootProject.file(".gradle/vkturn/go-cache")
        goModCacheDir.mkdirs()
        goCacheDir.mkdirs()
        outputFile.parentFile.mkdirs()

        workingDir = vkTurnRepoDir
        environment(
            mapOf(
                "GOMODCACHE" to goModCacheDir.absolutePath,
                "GOCACHE" to goCacheDir.absolutePath,
                "GOTOOLCHAIN" to "go1.25.5",
                "CGO_ENABLED" to "1",
                "GOOS" to "android",
                "GOARCH" to "arm64",
                "CC" to resolveVkTurnAndroidClang().absolutePath
            )
        )
        commandLine(
            "go",
            "build",
            "-trimpath",
            "-ldflags",
            "-checklinkname=0 -s -w",
            "-o",
            outputFile.absolutePath,
            "./client"
        )
    }
}

val generateVkTurnProxyProtoGo by tasks.registering(Exec::class) {
    group = "build"
    description = "Generates Go protobuf sources for external/vk-turn-proxy."

    inputs.files(fileTree(vkTurnProtoSourceDir) {
        include("**/*.proto")
    })
    outputs.file(vkTurnGeneratedProtoGo)

    doFirst {
        check(vkTurnRepoDir.isDirectory) {
            "vk-turn-proxy submodule not found at ${vkTurnRepoDir.absolutePath}. Run git submodule update --init --recursive."
        }
        workingDir = vkTurnRepoDir
        commandLine("sh", "scripts/generate_proto.sh")
    }
}

buildVkTurnProxyArm64.configure {
    dependsOn(generateVkTurnProxyProtoGo)
}

val generateWingsProtoJava by tasks.registering(Exec::class) {
    group = "build"
    description = "Generates Java lite sources from app/src/main/proto via protoc."

    inputs.files(fileTree(protoSourceDir) {
        include("**/*.proto")
    })
    outputs.dir(generatedProtoJavaDir)

    doFirst {
        val outDir = generatedProtoJavaDir.get().asFile
        outDir.mkdirs()
        workingDir = projectDir
        commandLine(
            "protoc",
            "--proto_path=${protoSourceDir.absolutePath}",
            "--java_out=lite:${outDir.absolutePath}",
            "${protoSourceDir.resolve("wingsv.proto").absolutePath}"
        )
    }
}

configurations.configureEach {
    exclude(group = "androidx.core", module = "core")
    exclude(group = "androidx.core", module = "core-ktx")
    exclude(group = "androidx.customview", module = "customview")
    exclude(group = "androidx.coordinatorlayout", module = "coordinatorlayout")
    exclude(group = "androidx.drawerlayout", module = "drawerlayout")
    exclude(group = "androidx.viewpager2", module = "viewpager2")
    exclude(group = "androidx.viewpager", module = "viewpager")
    exclude(group = "androidx.appcompat", module = "appcompat")
    exclude(group = "androidx.fragment", module = "fragment")
    exclude(group = "androidx.preference", module = "preference")
    exclude(group = "androidx.recyclerview", module = "recyclerview")
    exclude(group = "androidx.slidingpanelayout", module = "slidingpanelayout")
    exclude(group = "androidx.swiperefreshlayout", module = "swiperefreshlayout")
    exclude(group = "com.google.android.material", module = "material")
    exclude(group = "sesl.androidx.picker", module = "picker-app")
    exclude(group = "sesl.androidx.picker", module = "picker-basic")
    exclude(group = "sesl.androidx.picker", module = "picker-color")
}

android {
    namespace = "wings.v"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "wings.v"
        minSdk = 26
        targetSdk = 36
        versionCode = 17
        versionName = "1.7"
        vectorDrawables.useSupportLibrary = true

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(requireNotNull(keystoreProperties.getProperty("storeFile")))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildFeatures {
        viewBinding = true
    }

    sourceSets.getByName("main").jniLibs.srcDir(generatedVkTurnJniLibsDir.get().asFile)
    sourceSets.getByName("main").java.srcDir(generatedProtoJavaDir.get().asFile)

    packaging {
        resources.excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        jniLibs.useLegacyPackaging = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.named("preBuild") {
    dependsOn(generateVkTurnProxyProtoGo, buildVkTurnProxyArm64, generateWingsProtoJava)
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.oneui.design)
    implementation(libs.protobuf.javalite)
    implementation(libs.wireguard.tunnel)
    implementation(project(":vpnhotspot-bridge"))

    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}

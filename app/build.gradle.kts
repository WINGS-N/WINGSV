import java.util.Properties
import java.io.File
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.plugins.quality.Checkstyle
import org.gradle.api.plugins.quality.Pmd
import org.gradle.api.tasks.TaskProvider

plugins {
    alias(libs.plugins.android.application)
    checkstyle
    pmd
}

val keystoreProperties: Properties = Properties()
val keystorePropertiesFile: File = rootProject.file("keystore.properties")
if (keystorePropertiesFile.exists()) {
    keystorePropertiesFile.inputStream().use(keystoreProperties::load)
}
val localProperties: Properties = Properties()
val localPropertiesFile: File = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use(localProperties::load)
}
val hasReleaseSigning: Boolean = listOf("storeFile", "storePassword", "keyAlias", "keyPassword").all {
    !keystoreProperties.getProperty(it).isNullOrBlank()
}
val vkTurnRepoDir: File = rootProject.file("external/vk-turn-proxy")
val vkTurnProtoSourceDir: File = vkTurnRepoDir.resolve("proto")
val vkTurnGeneratedProtoGo: File = vkTurnRepoDir.resolve("sessionproto/session.pb.go")
val generatedVkTurnJniLibsDir: Provider<Directory> = layout.buildDirectory.dir("generated/jni/libs")
val generatedVkTurnBinary: Provider<File> = generatedVkTurnJniLibsDir.map { File(it.asFile, "arm64-v8a/libvkturn.so") }
val libXrayRepoDir: File = rootProject.file("external/libXray")
val generatedLibXrayDir: Provider<Directory> = layout.buildDirectory.dir("generated/xray")
val generatedLibXrayWorkDir: Provider<File> = generatedLibXrayDir.map { File(it.asFile, "work") }
val generatedLibXrayAar: Provider<File> = generatedLibXrayDir.map { File(it.asFile, "libXray.aar") }
val protoSourceDir: File = project.file("src/main/proto")
val generatedProtoJavaDir: Provider<Directory> = layout.buildDirectory.dir("generated/source/proto/main/java")

fun versionCodeFromSemanticVersion(versionName: String): Int {
    val parts: List<String> = versionName.split('.')
    require(parts.size == 3) {
        "Default app version must use semantic format <major>.<minor>.<patch>"
    }
    val major: Int = parts[0].toIntOrNull()
        ?: error("Default app version major component is invalid: $versionName")
    val minor: Int = parts[1].toIntOrNull()
        ?: error("Default app version minor component is invalid: $versionName")
    val patch: Int = parts[2].toIntOrNull()
        ?: error("Default app version patch component is invalid: $versionName")
    require(minor in 0..99 && patch in 0..99) {
        "Minor and patch components must stay in 0..99 for version code mapping"
    }
    return major * 10000 + minor * 100 + patch
}

fun parseVersionSpec(versionSpec: String?, defaultVersionName: String, defaultVersionCode: Int): Pair<String, Int> {
    val normalizedSpec: String = versionSpec
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: return defaultVersionName to defaultVersionCode
    if (!normalizedSpec.contains('/')) {
        return normalizedSpec to versionCodeFromSemanticVersion(normalizedSpec)
    }
    val versionName: String = normalizedSpec.substringBefore('/').trim()
    val versionCodeRaw: String = normalizedSpec.substringAfter('/', missingDelimiterValue = "").trim()
    require(versionName.isNotEmpty() && versionCodeRaw.isNotEmpty()) {
        "Property -Pver must use format <versionName> or <versionName>/<versionCode>"
    }
    val versionCode: Int = versionCodeRaw.toIntOrNull()
        ?: error("Property -Pver contains invalid versionCode: $versionCodeRaw")
    return versionName to versionCode
}

val defaultAppVersionName = "4.1.0"
val defaultAppVersionCode = versionCodeFromSemanticVersion(defaultAppVersionName)
val configuredAppVersionSpec = providers.gradleProperty("ver").orNull
require(configuredAppVersionSpec == null || Regex("""[^/\s]+(?:/\d+)?""").matches(configuredAppVersionSpec)) {
    "Property -Pver must use format <versionName> or <versionName>/<versionCode>, for example -Pver=4.0.1 or -Pver=4.0.1/40001"
}
val (configuredAppVersionName, configuredAppVersionCode) = parseVersionSpec(
    configuredAppVersionSpec,
    defaultAppVersionName,
    defaultAppVersionCode
)

fun resolveAndroidSdkDir(): File {
    val candidates: List<File> = listOfNotNull(
        System.getenv("ANDROID_SDK_ROOT"),
        System.getenv("ANDROID_HOME"),
        localProperties.getProperty("sdk.dir")
    ).map(::File)
    return candidates.firstOrNull { it.isDirectory }
        ?: error("Android SDK not found. Set sdk.dir in local.properties or ANDROID_SDK_ROOT.")
}

fun resolveAndroidNdkDir(): File {
    val direct: List<File> = listOfNotNull(
        System.getenv("ANDROID_NDK_HOME"),
        System.getenv("ANDROID_NDK_ROOT"),
        localProperties.getProperty("ndk.dir")
    ).map(::File)
    direct.firstOrNull { it.isDirectory }?.let { return it }

    val installed: List<File> = resolveAndroidSdkDir()
        .resolve("ndk")
        .listFiles()
        ?.filter { it.isDirectory }
        ?.sortedByDescending { it.name }
        .orEmpty()
    return installed.firstOrNull()
        ?: error("Android NDK not found. Install it under the Android SDK or set ANDROID_NDK_HOME.")
}

fun resolveVkTurnAndroidClang(): File {
    val ndkDir: File = resolveAndroidNdkDir()
    val prebuilt: File = ndkDir.resolve("toolchains/llvm/prebuilt/linux-x86_64/bin")
    val clang: File = prebuilt.resolve("aarch64-linux-android21-clang")
    return clang.takeIf { it.isFile }
        ?: error("Android clang not found at ${clang.absolutePath}")
}

fun resolveGoBinary(toolName: String): String {
    val userHome: String = requireNotNull(System.getProperty("user.home")) {
        "user.home system property is unavailable"
    }
    val candidates: List<File> = listOfNotNull(
        System.getenv("GOBIN"),
        "$userHome/go/bin",
        "/usr/local/go/bin",
        "/usr/bin"
    ).map { File(it, toolName) }
    return candidates.firstOrNull { it.isFile }
        ?.absolutePath
        ?: toolName
}

fun resolveToolBinDir(toolName: String, fallbackToolName: String = "go"): String {
    val resolved: File = File(resolveGoBinary(toolName))
    resolved.parentFile?.let { return it.absolutePath }
    return File(resolveGoBinary(fallbackToolName)).parentFile.absolutePath
}

fun isLintInvocation(): Boolean {
    return gradle.startParameter.taskNames.any { taskName ->
        taskName.substringAfterLast(':').startsWith("lint", ignoreCase = true)
    }
}

val buildVkTurnProxyArm64: TaskProvider<Exec> by tasks.registering(Exec::class) {
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

        val outputFile: File = generatedVkTurnBinary.get()
        val goModCacheDir: File = rootProject.file(".gradle/vkturn/go-mod-cache")
        val goCacheDir: File = rootProject.file(".gradle/vkturn/go-cache")
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

val generateVkTurnProxyProtoGo: TaskProvider<Exec> by tasks.registering(Exec::class) {
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

val buildLibXrayAndroidAar: TaskProvider<Exec> by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds libXray.aar from external/libXray via gomobile."

    inputs.files(fileTree(libXrayRepoDir) {
        exclude(".git/**")
        exclude("**/*.aar")
        exclude("**/*-sources.jar")
    })
    inputs.property("xrayGoToolchain", "go1.26.0")
    outputs.file(generatedLibXrayAar)

    doFirst {
        check(libXrayRepoDir.isDirectory) {
            "libXray submodule not found at ${libXrayRepoDir.absolutePath}. Run git submodule update --init --recursive."
        }
        val outputDir: File = generatedLibXrayDir.get().asFile
        val workDir: File = generatedLibXrayWorkDir.get()
        outputDir.mkdirs()
        delete(workDir)
        copy {
            from(libXrayRepoDir)
            into(workDir)
            exclude(".git/**")
            exclude("**/*.aar")
            exclude("**/*-sources.jar")
        }
        workingDir = workDir
        environment(
            mapOf(
                "GOTOOLCHAIN" to "go1.26.0",
                "ANDROID_SDK_ROOT" to resolveAndroidSdkDir().absolutePath,
                "ANDROID_HOME" to resolveAndroidSdkDir().absolutePath,
                "PATH" to buildString {
                    append(File(resolveAndroidSdkDir(), "platform-tools").absolutePath)
                    append(File.pathSeparator)
                    append(File(resolveAndroidSdkDir(), "tools/bin").absolutePath)
                    append(File.pathSeparator)
                    append(File(resolveAndroidNdkDir(), "toolchains/llvm/prebuilt/linux-x86_64/bin").absolutePath)
                    append(File.pathSeparator)
                    append(resolveToolBinDir("gomobile"))
                    append(File.pathSeparator)
                    append(System.getenv("PATH") ?: "")
                }
            )
        )
        val shellDollar: String = "$"
        commandLine(
            "sh",
            "-lc",
            """
            set -e
            retry() {
              attempts=0
              while true; do
                "$@" && return 0
                attempts=$((attempts + 1))
                if [ "${shellDollar}attempts" -ge 3 ]; then
                  return 1
                fi
                sleep 2
              done
            }
            export GOPROXY=https://proxy.golang.org,direct
            rm -f libXray.aar libXray-sources.jar
            retry python3 build/main.py android
            test -f libXray.aar
            cp libXray.aar "${generatedLibXrayAar.get().absolutePath}"
            """.trimIndent()
        )
    }
}

val generateWingsProtoJava: TaskProvider<Exec> by tasks.registering(Exec::class) {
    group = "build"
    description = "Generates Java lite sources from app/src/main/proto via protoc."

    inputs.files(fileTree(protoSourceDir) {
        include("**/*.proto")
    })
    outputs.dir(generatedProtoJavaDir)

    doFirst {
        val outDir: File = generatedProtoJavaDir.get().asFile
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
    exclude(group = "sesl.androidx.picker", module = "picker-color")
}

checkstyle {
    toolVersion = "13.3.0"
    configFile = rootProject.file("config/checkstyle/checkstyle.xml")
    isIgnoreFailures = false
}

pmd {
    toolVersion = "7.22.0"
    ruleSetFiles = files(rootProject.file("config/pmd/pmd.xml"))
    ruleSets = emptyList()
    isIgnoreFailures = false
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
        versionCode = configuredAppVersionCode
        versionName = configuredAppVersionName
        vectorDrawables.useSupportLibrary = true

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk.abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
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
        prefab = true
    }

    sourceSets.getByName("main") {
        jniLibs.directories.clear()
        jniLibs.directories.add(generatedVkTurnJniLibsDir.get().asFile.absolutePath)
        java.directories.clear()
        java.directories.addAll(listOf("src/main/java", generatedProtoJavaDir.get().asFile.absolutePath))
    }

    packaging {
        resources.excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        jniLibs.useLegacyPackaging = true
    }

    externalNativeBuild.cmake.path = file("src/main/cpp/CMakeLists.txt")

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

if (!isLintInvocation()) {
    tasks.named("preBuild") {
        dependsOn(
            generateVkTurnProxyProtoGo,
            buildVkTurnProxyArm64,
            generateWingsProtoJava,
            buildLibXrayAndroidAar
        )
    }
}

val checkstyleJava: TaskProvider<Checkstyle> by tasks.registering(Checkstyle::class) {
    group = "verification"
    description = "Runs Checkstyle against app Java sources."

    source("src")
    include("**/*.java")
    exclude("**/R.java", "**/BuildConfig.java")
    classpath = files()
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

val pmdJava: TaskProvider<Pmd> by tasks.registering(Pmd::class) {
    group = "verification"
    description = "Runs PMD against app Java sources."

    source("src/main/java")
    include("**/*.java")
    exclude("**/R.java", "**/BuildConfig.java")
    classpath = files()
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.named("check") {
    dependsOn(checkstyleJava, pmdJava)
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.oneui.design)
    implementation(libs.sesl.pickerBasic)
    implementation(libs.protobuf.javalite)
    implementation(libs.wireguard.tunnel)
    implementation(libs.xhook)
    implementation(project(":amneziawg-tunnel"))
    implementation(files(generatedLibXrayAar))
    implementation(project(":vpnhotspot-bridge"))
    compileOnly(files("libs/xposed-api-82.jar"))

    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}

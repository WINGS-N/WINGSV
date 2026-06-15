import java.util.Properties

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
        maven("https://artifactory-external.vkpartner.ru/artifactory/vkid-sdk-android/")
        maven("https://artifactory-external.vkpartner.ru/artifactory/maven/")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
        maven("https://artifactory-external.vkpartner.ru/artifactory/vkid-sdk-android/")
        maven("https://artifactory-external.vkpartner.ru/artifactory/maven/")
        maven("https://artifactory-external.vkpartner.ru/artifactory/vk-id-captcha/android/")

        val localProperties = Properties().apply {
            val file = rootDir.resolve("local.properties")
            if (file.isFile) {
                file.inputStream().use(::load)
            }
        }
        val seslUser = providers.gradleProperty("seslUser").orNull
            ?: localProperties.getProperty("seslUser")
        val seslToken = providers.gradleProperty("seslToken").orNull
            ?: localProperties.getProperty("seslToken")

        if (!seslUser.isNullOrBlank() && !seslToken.isNullOrBlank()) {
            maven {
                url = uri("https://maven.pkg.github.com/tribalfs/sesl-androidx")
                credentials {
                    username = seslUser
                    password = seslToken
                }
            }
            maven {
                url = uri("https://maven.pkg.github.com/tribalfs/sesl-material-components-android")
                credentials {
                    username = seslUser
                    password = seslToken
                }
            }
            maven {
                url = uri("https://maven.pkg.github.com/tribalfs/oneui-design")
                credentials {
                    username = seslUser
                    password = seslToken
                }
            }
        }
    }
}

rootProject.name = "WINGS V"
include(":app")
include(":vpnhotspot-bridge")
include(":vpnhotspot-upstream-runtime")
include(":amneziawg-tunnel")

project(":amneziawg-tunnel").projectDir = file("external/amneziawg-android/tunnel")
 

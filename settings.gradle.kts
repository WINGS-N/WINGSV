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

        val seslUser = providers.gradleProperty("seslUser").orNull
        val seslToken = providers.gradleProperty("seslToken").orNull

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
 

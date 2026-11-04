// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

@file:Suppress("UnstableApiUsage")

rootProject.name = "AsteriskBOX"

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
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
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        maven("https://jitpack.io")
        exclusiveContent {
            forRepository {
                ivy {
                    name = "AndroidLibBoxLiteGitHubRelease"
                    // Temporary fork while the plugin support PR is open upstream
                    // (Asterisk4Magisk/AndroidLibBoxLite#7). Flip back to the
                    // upstream owner as soon as a release carries it.
                    url = uri("https://github.com/grill-glitch/AndroidLibBoxLite/releases/download")
                    patternLayout {
                        artifact("[revision]/[artifact].[ext]")
                    }
                    metadataSources {
                        artifact()
                    }
                }
            }
            filter {
                includeModule("com.github.asterisk4magisk", "libbox")
            }
        }
    }
}

include(":app")
include(":asteriskd")
include(":bpfmatcher")
include(":bpf2socks")
include(":hevtun")
include(":enhance")

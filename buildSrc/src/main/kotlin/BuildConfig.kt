// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

object ProjectConfig {
    const val JVM_VERSION = 26
    const val PROJECT_NAME = "AsteriskBOX"
    const val VERSION_NAME = "1.0.5-dev"
    const val PACKAGE_NAME = "org.asterisk.zcc.abox"
    const val ASTERISKD_VERSION = "v1.0.4"
    const val BPF2SOCKS_VERSION = "v1.0.3"
    const val BPF_MATCHER_VERSION = "v1.0.0"
    const val SETUIDGID_VERSION = "v1.0.0"
    const val ANDROID_LIB_BOX_LITE_VERSION = "v1.14.0-beta.5-reF1nd"
    const val SING_BOX_VERSION = ANDROID_LIB_BOX_LITE_VERSION
    const val HEV_SOCKS5_TUNNEL_VERSION = "2.16.0"
    const val TARGET_SDK = 37
    const val MIN_SDK = 24
    val SUPPORTED_ANDROID_ABIS = listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
}

fun org.gradle.api.Project.getGitVersionCode(): Int {
    return providers.exec {
        commandLine("git", "rev-list", "--count", "HEAD")
    }.standardOutput.asText.get().trim().toInt()
}

abstract class GenerateProjectInfoTask : DefaultTask() {
    @get:Input
    abstract val packageName: Property<String>

    @get:Input
    abstract val projectName: Property<String>

    @get:Input
    abstract val versionName: Property<String>

    @get:Input
    abstract val versionCode: Property<Int>

    @get:Input
    abstract val singBoxVersion: Property<String>

    @get:Input
    abstract val androidLibBoxLiteVersion: Property<String>

    @get:Input
    abstract val hevSocks5TunnelVersion: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val packagePath = packageName.get().replace('.', '/')
        val file = outputDirectory.file("$packagePath/ProjectInfo.kt").get().asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            package ${packageName.get()}

            object ProjectInfo {
                const val PROJECT_NAME = "${projectName.get()}"
                const val VERSION_NAME = "${versionName.get()}"
                const val VERSION_CODE = ${versionCode.get()}
                const val SING_BOX_VERSION = "${singBoxVersion.get()}"
                const val ANDROID_LIB_BOX_LITE_VERSION = "${androidLibBoxLiteVersion.get()}"
                const val HEV_SOCKS5_TUNNEL_VERSION = "${hevSocks5TunnelVersion.get()}"
            }
            """.trimIndent(),
        )
    }
}

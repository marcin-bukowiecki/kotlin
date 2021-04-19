/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.plugin.mpp.apple

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.plugins.BasePlugin
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.TaskProvider
import org.jetbrains.kotlin.gradle.dsl.multiplatformExtensionOrNull
import org.jetbrains.kotlin.gradle.plugin.mpp.*
import org.jetbrains.kotlin.gradle.plugin.mpp.enabledOnCurrentHost
import org.jetbrains.kotlin.gradle.tasks.dependsOn
import org.jetbrains.kotlin.gradle.tasks.locateOrRegisterTask
import org.jetbrains.kotlin.gradle.tasks.registerTask
import org.jetbrains.kotlin.gradle.utils.lowerCamelCaseName
import org.jetbrains.kotlin.konan.target.Architecture
import org.jetbrains.kotlin.konan.target.HostManager
import org.jetbrains.kotlin.konan.target.KonanTarget
import org.jetbrains.kotlin.util.capitalizeDecapitalize.toLowerCaseAsciiOnly
import java.io.File

private object XcodeEnvironment {
    val buildType: NativeBuildType?
        get() {
            val configuration = System.getenv("CONFIGURATION") ?: return null

            fun String.toNativeBuildType() = when (this.toLowerCase()) {
                "debug" -> NativeBuildType.DEBUG
                "release" -> NativeBuildType.RELEASE
                else -> null
            }

            return configuration.toNativeBuildType()
                ?: System.getenv("KOTLIN_FRAMEWORK_BUILD_TYPE")?.toNativeBuildType()
                ?: throw IllegalArgumentException(
                    "Unexpected environment variable 'CONFIGURATION': $configuration. " +
                            "Use 'KOTLIN_FRAMEWORK_BUILD_TYPE' debug/release for specifying build type."
                )
        }

    val target: KonanTarget?
        get() {
            val sdk = System.getenv("SDK_NAME") ?: return null

            val hostArch = System.getenv("NATIVE_ARCH")
            val hostArchitecture = when {
                hostArch?.contains("x86_64") == true -> Architecture.X64
                hostArch?.contains("arm64") == true -> Architecture.ARM64
                else -> HostManager.host.architecture
            }

            return when {
                sdk.startsWith("iphoneos") -> KonanTarget.IOS_ARM64
                sdk.startsWith("iphonesimulator") -> when (hostArchitecture) {
                    Architecture.ARM64 -> KonanTarget.IOS_SIMULATOR_ARM64
                    else -> KonanTarget.IOS_X64
                }
                sdk.startsWith("watchos") -> KonanTarget.WATCHOS_ARM64
                sdk.startsWith("watchsimulator") -> when (hostArchitecture) {
                    Architecture.ARM64 -> KonanTarget.WATCHOS_SIMULATOR_ARM64
                    else -> KonanTarget.WATCHOS_X64
                }
                sdk.startsWith("appletvos") -> KonanTarget.TVOS_ARM64
                sdk.startsWith("appletvsimulator") -> when (hostArchitecture) {
                    Architecture.ARM64 -> KonanTarget.TVOS_SIMULATOR_ARM64
                    else -> KonanTarget.TVOS_X64
                }
                else -> throw IllegalArgumentException("Unexpected environment variable 'SDK_NAME': $sdk")
            }
        }

    val frameworkSearchDir: File?
        get() {
            val configuration = System.getenv("CONFIGURATION") ?: return null
            val sdk = System.getenv("SDK_NAME") ?: return null
            return File(configuration, sdk)
        }

    val embeddedFrameworksDir: File?
        get() {
            val xcodeTargetBuildDir = System.getenv("TARGET_BUILD_DIR") ?: return null
            val xcodeFrameworksFolderPath = System.getenv("FRAMEWORKS_FOLDER_PATH") ?: return null
            return File(xcodeTargetBuildDir, xcodeFrameworksFolderPath)
        }

    val sign: String? get() = System.getenv("EXPANDED_CODE_SIGN_IDENTITY")

    override fun toString() = """
        XcodeEnvironment [
            CONFIGURATION=${System.getenv("CONFIGURATION")}
            SDK_NAME=${System.getenv("SDK_NAME")}
            EXPANDED_CODE_SIGN_IDENTITY=${System.getenv("EXPANDED_CODE_SIGN_IDENTITY")}
            TARGET_BUILD_DIR=${System.getenv("TARGET_BUILD_DIR")}
            FRAMEWORKS_FOLDER_PATH=${System.getenv("FRAMEWORKS_FOLDER_PATH")}
        ]
    """.trimIndent()
}

internal fun Project.registerAssembleAppleFrameworkTask(framework: Framework) {
    if (!framework.konanTarget.family.isAppleFamily) return

    val frameworkBuildType = framework.buildType
    val frameworkTarget = framework.target
    val frameworkTaskName = lowerCamelCaseName(
        "assemble",
        frameworkBuildType.name.toLowerCaseAsciiOnly(),
        "AppleFramework",
        framework.baseName,
        frameworkTarget.name
    )

    val envBuildType = XcodeEnvironment.buildType
    val envTarget = XcodeEnvironment.target
    val envFrameworkSearchDir = XcodeEnvironment.frameworkSearchDir

    umbrellaAssembleAppleFrameworkTask.dependsOn(
        registerTask<Copy>(frameworkTaskName) { task ->
            task.group = BasePlugin.BUILD_GROUP
            task.description = "Packs $frameworkBuildType ${frameworkTarget.name} framework for Xcode"
            task.enabled = framework.konanTarget.enabledOnCurrentHost
                    && frameworkBuildType == envBuildType
                    && frameworkTarget.konanTarget == envTarget

            task.dependsOn(framework.linkTaskName)
            if (envFrameworkSearchDir != null) {
                task.from(framework.outputDirectory)
                task.into(appleFrameworkDir(envFrameworkSearchDir))
            }
        }
    )
}

private const val UMBRELLA_ASSEMBLE_APPLE_FRAMEWORK = "assembleAppleFramework"
private val Project.umbrellaAssembleAppleFrameworkTask: TaskProvider<Task>
    get() = locateOrRegisterTask(UMBRELLA_ASSEMBLE_APPLE_FRAMEWORK) {
        it.group = "build"
        it.description = "Build all frameworks as requested by Xcode's environment variables"
    }

private const val EMBED_AND_SIGN_APPLE_FRAMEWORK = "embedAndSignAppleFramework"
internal fun Project.registerEmbedAndSignAppleFrameworkTask() {
    val envBuildType = XcodeEnvironment.buildType
    val envTarget = XcodeEnvironment.target
    val envEmbeddedFrameworksDir = XcodeEnvironment.embeddedFrameworksDir
    val envFrameworkSearchDir = XcodeEnvironment.frameworkSearchDir
    val envSign = XcodeEnvironment.sign

    if (envBuildType == null || envTarget == null || envEmbeddedFrameworksDir == null || envFrameworkSearchDir == null) {
        logger.debug(
            "Not registering $EMBED_AND_SIGN_APPLE_FRAMEWORK, since not called from Xcode " +
                    "('SDK_NAME', 'CONFIGURATION', 'TARGET_BUILD_DIR' and 'FRAMEWORKS_FOLDER_PATH' not provided)"
        )
        return
    }

    registerTask<Copy>(EMBED_AND_SIGN_APPLE_FRAMEWORK) { task ->
        task.group = "build"
        task.description = "Embed and sign all frameworks as requested by Xcode's environment variables"

        val appleFrameworks = multiplatformExtensionOrNull?.targets
            ?.filterIsInstance<KotlinNativeTarget>()
            ?.filter { it.konanTarget.family.isAppleFamily }

        if (appleFrameworks.isNullOrEmpty()) return@registerTask

        task.dependsOn(UMBRELLA_ASSEMBLE_APPLE_FRAMEWORK)
        task.inputs.apply {
            property("type", envBuildType)
            property("target", envTarget)
            property("embeddedFrameworksDir", envEmbeddedFrameworksDir)
            property("sign", envSign)
        }

        val outputFiles =
            appleFrameworks
                .filter { it.konanTarget == envTarget }
                .map { it.binaries.getFramework(envBuildType).outputFile }

        task.from(outputFiles.map { appleFrameworkDir(envFrameworkSearchDir).resolve(it.name) }.toTypedArray())
        task.into(envEmbeddedFrameworksDir)

        if (envSign != null) {
            task.doLast {
                outputFiles.forEach { outputFile ->
                    val binaryFile = envEmbeddedFrameworksDir.resolve(outputFile.name).resolve(outputFile.nameWithoutExtension)
                    exec {
                        it.commandLine("codesign", "--force", "--sign", envSign, "--", binaryFile)
                    }
                }
            }
        }
    }
}

private fun Project.appleFrameworkDir(frameworkSearchDir: File) =
    buildDir.resolve("xcode-frameworks").resolve(frameworkSearchDir)
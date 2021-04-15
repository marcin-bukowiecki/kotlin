/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.plugin.mpp.apple

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.plugins.BasePlugin
import org.gradle.api.tasks.Copy
import org.jetbrains.kotlin.gradle.dsl.multiplatformExtensionOrNull
import org.jetbrains.kotlin.gradle.plugin.mpp.AbstractNativeLibrary
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
import org.jetbrains.kotlin.gradle.plugin.mpp.enabledOnCurrentHost
import org.jetbrains.kotlin.gradle.tasks.registerTask
import org.jetbrains.kotlin.gradle.utils.lowerCamelCaseName
import org.jetbrains.kotlin.konan.target.KonanTarget
import org.jetbrains.kotlin.util.capitalizeDecapitalize.toLowerCaseAsciiOnly
import java.io.File

private object XcodeEnvironment {
    val buildType: NativeBuildType?
        get() {
            val configuration = System.getenv("CONFIGURATION")?.toLowerCase() ?: return null
            return when (configuration) {
                "debug" -> NativeBuildType.DEBUG
                "release" -> NativeBuildType.RELEASE
                else -> throw IllegalArgumentException("Unexpected environment variable 'CONFIGURATION': $configuration")
            }
        }

    val target: KonanTarget?
        get() {
            val sdk = System.getenv("SDK_NAME") ?: return null
            return when {
                sdk.startsWith("iphoneos") -> KonanTarget.IOS_ARM64
                sdk.startsWith("iphonesimulator") -> KonanTarget.IOS_X64
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

internal fun Project.registerAssembleAppleFrameworkTask(framework: AbstractNativeLibrary) {
    if (!framework.konanTarget.family.isAppleFamily) return

    val type = framework.buildType
    val target = framework.target
    val taskName = target.assembleAppleFrameworkTaskName(type)

    val xcBuildType = XcodeEnvironment.buildType
    val xcTarget = XcodeEnvironment.target
    val xcFrameworkSearchDir = XcodeEnvironment.frameworkSearchDir

    if (xcBuildType == null || xcTarget == null || xcFrameworkSearchDir == null) {
        logger.info("$taskName requires 'CONFIGURATION' and 'SDK_NAME'")
        return
    }

    if (type != XcodeEnvironment.buildType || target.konanTarget != XcodeEnvironment.target) return

    registerTask<Copy>(taskName) { task ->
        task.group = BasePlugin.BUILD_GROUP
        task.description = "Packs $type ${target.name} framework for Xcode"
        task.enabled = framework.konanTarget.enabledOnCurrentHost

        task.dependsOn(framework.linkTaskName)
        task.from(framework.outputDirectory)
        task.into(appleFrameworkDir(xcFrameworkSearchDir))
    }
}

private const val UMBRELLA_ASSEMBLE_APPLE_FRAMEWORK = "assembleAppleFramework"
internal fun Project.registerUmbrellaAssembleAppleFrameworkTask() {
    logger.info(XcodeEnvironment.toString())

    val xcBuildType = XcodeEnvironment.buildType
    val xcTarget = XcodeEnvironment.target
    val xcFrameworkSearchDir = XcodeEnvironment.frameworkSearchDir

    if (xcBuildType == null || xcTarget == null || xcFrameworkSearchDir == null) {
        logger.info("$UMBRELLA_ASSEMBLE_APPLE_FRAMEWORK requires 'CONFIGURATION' and 'SDK_NAME'")
        return
    }

    registerTask<Task>(UMBRELLA_ASSEMBLE_APPLE_FRAMEWORK) { task ->
        task.group = "build"
        task.description = "Build all frameworks as requested by Xcode's environment variables"

        val appleFrameworks = multiplatformExtensionOrNull?.targets
            ?.filterIsInstance<KotlinNativeTarget>()
            ?.filter { it.konanTarget.family.isAppleFamily }

        if (appleFrameworks.isNullOrEmpty()) return@registerTask

        val taskNames = appleFrameworks
            .filter { framework -> framework.konanTarget == xcTarget }
            .map { framework -> framework.assembleAppleFrameworkTaskName(xcBuildType) }

        task.enabled = taskNames.isNotEmpty()
        task.dependsOn(taskNames.toTypedArray())
        tasks.getByName("assemble").dependsOn(task)
    }
}

private const val EMBED_AND_SIGN_APPLE_FRAMEWORK = "embedAndSignAppleFramework"
internal fun Project.registerEmbedAndSignAppleFrameworkTask() {
    val type = XcodeEnvironment.buildType
    val target = XcodeEnvironment.target
    val embeddedFrameworksDir = XcodeEnvironment.embeddedFrameworksDir
    val frameworkSearchDir = XcodeEnvironment.frameworkSearchDir

    if (type == null || target == null || embeddedFrameworksDir == null || frameworkSearchDir == null) {
        logger.info("$EMBED_AND_SIGN_APPLE_FRAMEWORK requires 'SDK_NAME', 'CONFIGURATION', 'TARGET_BUILD_DIR' and 'FRAMEWORKS_FOLDER_PATH'")
        return
    }

    registerTask<Copy>(EMBED_AND_SIGN_APPLE_FRAMEWORK) { task ->
        task.group = "build"
        task.description = "Embed and sign all frameworks as requested by Xcode's environment variables"

        val appleFrameworks = multiplatformExtensionOrNull?.targets
            ?.filterIsInstance<KotlinNativeTarget>()
            ?.filter { it.konanTarget.family.isAppleFamily }

        if (appleFrameworks.isNullOrEmpty()) return@registerTask

        val sign = XcodeEnvironment.sign

        task.dependsOn(UMBRELLA_ASSEMBLE_APPLE_FRAMEWORK)
        task.inputs.apply {
            property("type", type)
            property("target", target)
            property("embeddedFrameworksDir", embeddedFrameworksDir)
            property("sign", sign)
        }

        val outputFiles =
            appleFrameworks
                .filter { it.konanTarget == target }
                .map { it.binaries.getFramework(type).outputFile }

        task.from(outputFiles.map { appleFrameworkDir(frameworkSearchDir).resolve(it.name) }.toTypedArray())
        task.into(embeddedFrameworksDir)

        if (sign != null) {
            task.doLast {
                outputFiles.forEach { outputFile ->
                    val binaryFile = embeddedFrameworksDir.resolve(outputFile.name).resolve(outputFile.nameWithoutExtension)
                    exec {
                        it.commandLine("codesign", "--force", "--sign", sign, "--", binaryFile)
                    }
                }
            }
        }
    }
}

private fun Project.appleFrameworkDir(frameworkSearchDir: File) =
    buildDir.resolve("xcode-frameworks").resolve(frameworkSearchDir)

private fun KotlinNativeTarget.assembleAppleFrameworkTaskName(buildType: NativeBuildType) =
    lowerCamelCaseName("assemble", buildType.name.toLowerCaseAsciiOnly(), "AppleFramework", name)
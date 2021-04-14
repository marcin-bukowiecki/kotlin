/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.plugin.mpp.apple

import org.gradle.api.Project
import org.gradle.api.plugins.BasePlugin
import org.gradle.api.tasks.Copy
import org.jetbrains.kotlin.gradle.plugin.mpp.AbstractNativeLibrary
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
import org.jetbrains.kotlin.gradle.plugin.mpp.enabledOnCurrentHost
import org.jetbrains.kotlin.gradle.tasks.registerTask
import org.jetbrains.kotlin.gradle.utils.lowerCamelCaseName
import org.jetbrains.kotlin.konan.target.KonanTarget
import org.jetbrains.kotlin.util.capitalizeDecapitalize.toLowerCaseAsciiOnly
import java.io.File

object XcodeEnvironment {
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

private fun Project.appleFrameworkDir(frameworkSearchDir: File) =
    buildDir.resolve("xcode-frameworks").resolve(frameworkSearchDir)

private fun KotlinNativeTarget.assembleAppleFrameworkTaskName(buildType: NativeBuildType) =
    lowerCamelCaseName("assemble", buildType.name.toLowerCaseAsciiOnly(), "AppleFramework", name)
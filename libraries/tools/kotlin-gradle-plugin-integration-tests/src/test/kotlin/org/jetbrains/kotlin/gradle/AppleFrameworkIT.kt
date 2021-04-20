/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle

import kotlin.test.Test

class AppleFrameworkIT : KotlinAndroid36GradleIT() {
    companion object {
        private val gradleVersion = GradleVersionRequired.FOR_MPP_SUPPORT
    }

    override val defaultGradleVersion: GradleVersionRequired
        get() = gradleVersion

    @Test
    fun `assemble debug AppleFramework for IosArm64`() {
        with(Project("sharedAppleFramework")) {
            val options: BuildOptions = defaultBuildOptions().copy(
                customEnvironmentVariables = mapOf(
                    "CONFIGURATION" to "debug",
                    "SDK_NAME" to "iphoneos123"
                )
            )
            build("assembleAppleFramework", options = options) {
                assertSuccessful()
                assertTasksExecuted(":shared:assembleSharedDebugAppleFrameworkIosArm64")
                assertFileExists("/shared/build/xcode-frameworks/debug/iphoneos123/shared.framework")
                assertFileExists("/shared/build/xcode-frameworks/debug/iphoneos123/shared.framework.dSYM")
            }

            build("assembleAppleFramework", options = options) {
                assertSuccessful()
                assertTasksUpToDate(":shared:assembleSharedDebugAppleFrameworkIosArm64")
                assertFileExists("/shared/build/xcode-frameworks/debug/iphoneos123/shared.framework")
                assertFileExists("/shared/build/xcode-frameworks/debug/iphoneos123/shared.framework.dSYM")
            }
        }
    }

    @Test
    fun `check there aren't Xcode tasks without Xcode environment`() {
        with(Project("sharedAppleFramework")) {
            build("tasks") {
                assertSuccessful()
                assertTasksNotRegistered(
                    ":shared:assembleAppleFramework",
                    ":shared:embedAndSignAppleFramework"
                )
            }
        }
    }

    @Test
    fun `check all registered tasks with Xcode environment for Debug IosArm64 configuration`() {
        with(Project("sharedAppleFramework")) {
            val options: BuildOptions = defaultBuildOptions().copy(
                customEnvironmentVariables = mapOf(
                    "CONFIGURATION" to "Debug",
                    "SDK_NAME" to "iphoneos",
                    "EXPANDED_CODE_SIGN_IDENTITY" to "-",
                    "TARGET_BUILD_DIR" to "testBuildDir",
                    "FRAMEWORKS_FOLDER_PATH" to "testFrameworksDir"
                )
            )
            build("tasks", options = options) {
                assertSuccessful()
                assertTasksRegistered(
                    ":shared:assembleAppleFramework",
                    ":shared:assembleSharedDebugAppleFrameworkIosArm64",
                    ":shared:embedAndSignAppleFramework"
                )
                assertTasksNotRegistered(":shared:assembleSharedDebugAppleFrameworkIosX64")
            }
        }
    }

    @Test
    fun `check there isn't embedAndSignAppleFramework task without required Xcode environments`() {
        with(Project("sharedAppleFramework")) {
            val options: BuildOptions = defaultBuildOptions().copy(
                customEnvironmentVariables = mapOf(
                    "CONFIGURATION" to "Debug",
                    "SDK_NAME" to "iphoneos"
                )
            )
            build("tasks", options = options) {
                assertSuccessful()
                assertTasksRegistered(
                    ":shared:assembleAppleFramework",
                    ":shared:assembleSharedDebugAppleFrameworkIosArm64",
                )
                assertTasksNotRegistered(
                    ":shared:embedAndSignAppleFramework",
                    ":shared:assembleSharedDebugAppleFrameworkIosX64"
                )
            }
        }
    }
}
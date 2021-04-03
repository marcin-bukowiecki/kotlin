/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.fir.low.level.api.diagnostic.compiler.based

import org.jetbrains.kotlin.idea.fir.low.level.api.constistency.structure.AbstractCompilerBasedTest
import org.jetbrains.kotlin.test.builders.TestConfigurationBuilder
import org.jetbrains.kotlin.test.runners.diagnosticTestConfiguration

abstract class AbstractDiagnosisCompilerTestDataTest : AbstractCompilerBasedTest() {
    override fun TestConfigurationBuilder.configureTest() {
        diagnosticTestConfiguration()
    }
}
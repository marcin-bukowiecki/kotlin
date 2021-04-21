/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.fir.low.level.api.api

import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.builder.RawFirFragmentForLazyBodiesBuilder
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.declarations.builder.FirDeclarationBuilder
import org.jetbrains.kotlin.fir.declarations.builder.buildPropertyAccessorCopy
import org.jetbrains.kotlin.fir.declarations.builder.buildRegularClassCopy
import org.jetbrains.kotlin.fir.declarations.builder.buildSimpleFunctionCopy
import org.jetbrains.kotlin.fir.expressions.FirReturnExpression
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.idea.fir.low.level.api.providers.firIdeProvider
import org.jetbrains.kotlin.psi.*

object DeclarationCopyBuilder {
    fun createDeclarationCopy(
        originalFirDeclaration: FirDeclaration,
        copiedKtDeclaration: KtDeclaration,
        state: FirModuleResolveState,
    ): FirDeclaration {
        return when (copiedKtDeclaration) {
            is KtNamedFunction -> buildFunctionCopy(
                copiedKtDeclaration,
                originalFirDeclaration as FirSimpleFunction,
                state
            )
            is KtProperty -> buildPropertyCopy(
                copiedKtDeclaration,
                originalFirDeclaration as FirProperty,
                state
            )
            is KtClassOrObject -> buildClassCopy(
                copiedKtDeclaration,
                originalFirDeclaration as FirRegularClass,
                state
            )
            is KtTypeAlias -> buildTypeAliasCopy(
                copiedKtDeclaration,
                originalFirDeclaration as FirTypeAlias,
                state
            )
            else -> error("Unsupported declaration ${copiedKtDeclaration::class.simpleName}")
        }
    }

    private fun buildFunctionCopy(
        element: KtNamedFunction,
        originalFunction: FirSimpleFunction,
        state: FirModuleResolveState,
    ): FirSimpleFunction {
        val builtFunction = createCopy(element, originalFunction)

        // right now we can't resolve builtFunction header properly, as it built right in air,
        // without file, which is now required for running stages other then body resolve, so we
        // take original function header (which is resolved) and copy replacing body with body from builtFunction
        return buildSimpleFunctionCopy(originalFunction) {
            body = builtFunction.body
            symbol = builtFunction.symbol
            initDeclaration(originalFunction, builtFunction, state)
        }.apply { reassignAllReturnTargets(builtFunction) }
    }

    private fun buildClassCopy(
        fakeKtClassOrObject: KtClassOrObject,
        originalFirClass: FirRegularClass,
        state: FirModuleResolveState,
    ): FirRegularClass {
        val builtClass = createCopy(fakeKtClassOrObject, originalFirClass)

        return buildRegularClassCopy(originalFirClass) {
            declarations.clear()
            declarations.addAll(builtClass.declarations)
            symbol = builtClass.symbol
            initDeclaration(originalFirClass, builtClass, state)
        }
    }

    private fun buildTypeAliasCopy(
        fakeKtTypeAlias: KtTypeAlias,
        originalFirTypeAlias: FirTypeAlias,
        state: FirModuleResolveState,
    ): FirTypeAlias {
        val builtTypeAlias = createCopy(fakeKtTypeAlias, originalFirTypeAlias)

        return org.jetbrains.kotlin.fir.declarations.builder.buildTypeAliasCopy(originalFirTypeAlias) {
            expandedTypeRef = builtTypeAlias.expandedTypeRef
            symbol = builtTypeAlias.symbol
            initDeclaration(originalFirTypeAlias, builtTypeAlias, state)
        }
    }

    private fun buildPropertyCopy(
        element: KtProperty,
        originalProperty: FirProperty,
        state: FirModuleResolveState
    ): FirProperty {
        val builtProperty = createCopy(element, originalProperty)

        val originalSetter = originalProperty.setter
        val builtSetter = builtProperty.setter

        // setter has a header with `value` parameter, and we want it type to be resolved
        val copySetter = if (originalSetter != null && builtSetter != null) {
            buildPropertyAccessorCopy(originalSetter) {
                body = builtSetter.body
                symbol = builtSetter.symbol
                initDeclaration(originalSetter, builtSetter, state)
            }.apply { reassignAllReturnTargets(builtSetter) }
        } else {
            builtSetter
        }

        return org.jetbrains.kotlin.fir.declarations.builder.buildPropertyCopy(originalProperty) {
            symbol = builtProperty.symbol
            initializer = builtProperty.initializer

            getter = builtProperty.getter
            setter = copySetter

            initDeclaration(originalProperty, builtProperty, state)
        }
    }

    private fun FirDeclarationBuilder.initDeclaration(
        originalDeclaration: FirDeclaration,
        builtDeclaration: FirDeclaration,
        state: FirModuleResolveState
    ) {
        resolvePhase = minOf(originalDeclaration.resolvePhase, FirResolvePhase.DECLARATIONS)
        source = builtDeclaration.source
        session = state.rootModuleSession
    }

    internal inline fun <reified T : FirDeclaration> createCopy(
        fakeKtDeclaration: KtDeclaration,
        originalFirDeclaration: T,
    ): T {
        return RawFirFragmentForLazyBodiesBuilder.build(
            session = originalFirDeclaration.session,
            baseScopeProvider = originalFirDeclaration.session.firIdeProvider.kotlinScopeProvider,
            designation = originalFirDeclaration.collectDesignation().fullDesignation,
            declaration = fakeKtDeclaration
        ) as T
    }

    private fun FirFunction<*>.reassignAllReturnTargets(from: FirFunction<*>) {
        this.accept(object : FirVisitorVoid() {
            override fun visitElement(element: FirElement) {
                if (element is FirReturnExpression && element.target.labeledElement == from) {
                    element.target.bind(this@reassignAllReturnTargets)
                }
                element.acceptChildren(this)
            }
        })
    }
}
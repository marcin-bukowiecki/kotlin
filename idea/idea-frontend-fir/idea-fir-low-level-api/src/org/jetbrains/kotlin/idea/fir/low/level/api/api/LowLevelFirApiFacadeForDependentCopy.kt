/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.fir.low.level.api.api

import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.parentsOfType
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.idea.fir.low.level.api.FirModuleResolveStateDepended
import org.jetbrains.kotlin.idea.fir.low.level.api.FirModuleResolveStateImpl
import org.jetbrains.kotlin.idea.fir.low.level.api.util.originalDeclaration
import org.jetbrains.kotlin.idea.util.getElementTextInContext
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject
import kotlin.reflect.KClass

object LowLevelFirApiFacadeForDependentCopy {

    private fun KtDeclaration.canBeEnclosingDeclaration(): Boolean = when (this) {
        is KtNamedFunction -> isTopLevel || containingClassOrObject?.isLocal == false
        is KtProperty -> isTopLevel || containingClassOrObject?.isLocal == false
        is KtClassOrObject -> !isLocal
        is KtTypeAlias -> isTopLevel() || containingClassOrObject?.isLocal == false
        else -> false
    }

    private fun findEnclosingNonLocalDeclaration(position: KtElement): KtNamedDeclaration? =
        position.parentsOfType<KtNamedDeclaration>().firstOrNull { ktDeclaration ->
            ktDeclaration.canBeEnclosingDeclaration()
        }

    private fun locateDeclarationByOffset(declaration: KtDeclaration, file: KtFile): KtDeclaration? =
        file.findDeclarationOfTypeAt(declaration.textOffset, declaration::class)

    private fun recordOriginalDeclaration(targetDeclaration: KtNamedDeclaration, originalDeclaration: KtNamedDeclaration) {
        require(!targetDeclaration.isPhysical)
        require(originalDeclaration.containingKtFile !== targetDeclaration.containingKtFile)
        val originalDeclrationParents = originalDeclaration.parentsOfType<KtDeclaration>().toList()
        val fakeDeclarationParents = targetDeclaration.parentsOfType<KtDeclaration>().toList()
        originalDeclrationParents.zip(fakeDeclarationParents) { original, fake ->
            fake.originalDeclaration = original
        }
    }

    private fun <T : KtElement> KtFile.findDeclarationOfTypeAt(offset: Int, declarationType: KClass<T>): T? {
        val elementAtOffset = findElementAt(offset) ?: return null
        return PsiTreeUtil.getParentOfType(elementAtOffset, declarationType.java, false)?.takeIf { it.textOffset == offset }
    }

    fun getResolveStateForDependedCopy(
        originalState: FirModuleResolveState,
        originalKtFile: KtFile,
        copiedKtElement: KtElement
    ): FirModuleResolveState {
        require(originalState is FirModuleResolveStateImpl)

        val nonLocalCopiedDeclaration = findEnclosingNonLocalDeclaration(copiedKtElement)
            ?: error("Cannot find enclosing declaration for ${copiedKtElement.getElementTextInContext()}")

        val originalNonLocalDeclaration =
            locateDeclarationByOffset(nonLocalCopiedDeclaration, originalKtFile) as? KtNamedDeclaration
                ?: error("Cannot find original function matching to ${nonLocalCopiedDeclaration.getElementTextInContext()} in $originalKtFile")

        recordOriginalDeclaration(
            targetDeclaration = nonLocalCopiedDeclaration,
            originalDeclaration = originalNonLocalDeclaration
        )

        val originalFirDeclaration = originalNonLocalDeclaration.getOrBuildFirOfType<FirDeclaration>(originalState)
        val copiedFirDeclaration = DeclarationCopyBuilder.createDeclarationCopy(
            originalFirDeclaration = originalFirDeclaration,
            copiedKtDeclaration = nonLocalCopiedDeclaration,
            state = originalState
        )

        val originalFirFile = originalState.getFirFile(originalKtFile)

        return FirModuleResolveStateDepended(copiedFirDeclaration, originalFirFile, originalState)
    }
}

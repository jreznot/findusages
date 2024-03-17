package com.intellij.driverusage

import com.intellij.codeInsight.AnnotationUtil
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.codeInsight.navigation.impl.PsiTargetPresentationRenderer
import com.intellij.icons.AllIcons
import com.intellij.lang.jvm.JvmModifier
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.getContainingUClass
import org.jetbrains.uast.getUParentForIdentifier
import org.jetbrains.uast.toUElementOfType

internal class RemoteMethodLineMarkerProvider : RelatedItemLineMarkerProvider() {
    override fun getLineMarkerInfo(element: PsiElement): RelatedItemLineMarkerInfo<*>? = null

    override fun collectNavigationMarkers(
        element: PsiElement,
        result: MutableCollection<in RelatedItemLineMarkerInfo<*>>
    ) {
        val uMethod = getUParentForIdentifier(element) ?: return
        if (uMethod is UMethod) {
            val uClass = uMethod.getContainingUClass() ?: return
            val psiMethod = uMethod.javaPsi
            if (uClass.isInterface && psiMethod.hasModifier(JvmModifier.ABSTRACT)) {
                val anchor = uMethod.uastAnchor?.sourcePsi ?: return
                if (AnnotationUtil.isAnnotated(uClass.javaPsi, REMOTE_ANNOTATION_FQN, 0)) {
                    result.add(
                        NavigationGutterIconBuilder.create(AllIcons.Gutter.ReadAccess)
                            .setTooltipText("Go to implementation")
                            .setPopupTitle("Implementations")
                            .setEmptyPopupText("Unable to find implementation")
                            .setTargetRenderer { PsiTargetPresentationRenderer() }
                            .setTargets(NotNullLazyValue.lazy { findRemoteMethods(psiMethod) })
                            .createLineMarkerInfo(anchor)
                    )
                }
            }
        }
    }

    private fun findRemoteMethods(psiMethod: PsiMethod): Collection<PsiElement> {
        if (!psiMethod.isValid) return emptyList()

        val uClass = psiMethod.toUElementOfType<UMethod>()?.getContainingUClass() ?: return emptyList()
        val remoteClass = getTargetRemoteClass(psiMethod.project, uClass) ?: return emptyList()

        return remoteClass.allMethods
            .filter { it.name == psiMethod.name }
            .filterNot {
                it.hasModifier(JvmModifier.PRIVATE)
                        || it.hasModifier(JvmModifier.PROTECTED)
                        || it.hasModifier(JvmModifier.PACKAGE_LOCAL)
            }
            .filter { it.parameters.size == psiMethod.parameters.size }
    }
}

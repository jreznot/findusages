package com.intellij.driverusage

import com.intellij.codeInsight.AnnotationUtil
import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.roots.FileIndexFacade
import com.intellij.openapi.roots.TestSourcesFilter
import com.intellij.openapi.util.TextRange
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.search.GlobalSearchScope.EMPTY_SCOPE
import com.intellij.psi.search.GlobalSearchScope.allScope
import com.intellij.psi.search.RequestResultProcessor
import com.intellij.psi.search.SearchScope
import com.intellij.psi.search.UsageSearchContext
import com.intellij.psi.search.searches.MethodReferencesSearch
import com.intellij.psi.util.InheritanceUtil
import com.intellij.util.Processor
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.evaluateString
import org.jetbrains.uast.getContainingUClass
import org.jetbrains.uast.toUElement

internal class RemoteMethodReferenceSearcher :
    QueryExecutorBase<PsiReference, MethodReferencesSearch.SearchParameters>() {
    override fun processQuery(
        queryParameters: MethodReferencesSearch.SearchParameters,
        consumer: Processor<in PsiReference>
    ) {
        val targetMethod = queryParameters.method

        val methodName = targetMethod.name
        queryParameters.optimizer.searchWord(
            methodName,
            ReadAction.compute<SearchScope, RuntimeException> {
                // do not search for self
                val searchableClass = targetMethod.containingClass ?: return@compute EMPTY_SCOPE
                if (AnnotationUtil.isAnnotated(searchableClass, REMOTE_ANNOTATION_FQN, 0)) return@compute EMPTY_SCOPE

                val project = targetMethod.project
                val file = searchableClass.containingFile?.virtualFile ?: return@compute EMPTY_SCOPE
                if (TestSourcesFilter.isTestSources(file, project)
                    || FileIndexFacade.getInstance(project).isInLibrary(file)) {
                    return@compute EMPTY_SCOPE
                }

                val remoteClass = JavaPsiFacade.getInstance(project)
                    .findClass(REMOTE_ANNOTATION_FQN, allScope(project))
                    ?: return@compute EMPTY_SCOPE

                remoteClass.useScope.intersectWith(queryParameters.effectiveSearchScope)
            },
            UsageSearchContext.IN_CODE,
            true,
            object : RequestResultProcessor() {
                override fun processTextOccurrence(
                    element: PsiElement,
                    offsetInElement: Int,
                    consumer: Processor<in PsiReference>
                ): Boolean {
                    val method = element.toUElement(UMethod::class.java) ?: return true

                    val uClass = method.getContainingUClass() ?: return true
                    val psiMethodFound = method.javaPsi

                    val psiClass = psiMethodFound.containingClass ?: return true
                    if (!psiClass.isInterface) return true
                    if (!AnnotationUtil.isAnnotated(psiClass, REMOTE_ANNOTATION_FQN, 0)) return true

                    val targetClassFqn = uClass.uAnnotations
                        .find { it.qualifiedName == REMOTE_ANNOTATION_FQN }
                        ?.findAttributeValue("value")
                        ?.evaluateString()
                        ?: return true

                    val baseClass = JavaPsiFacade.getInstance(element.project)
                        .findClass(targetClassFqn, allScope(element.project)) ?: return true

                    val searchableClass = targetMethod.containingClass
                    if (!InheritanceUtil.isInheritorOrSelf(searchableClass, baseClass, true)) {
                        return true
                    }

                    val reference = PsiReferenceBase.createSelfReference(
                        element,
                        TextRange(offsetInElement, offsetInElement + methodName.length),
                        targetMethod
                    )

                    return consumer.process(reference)
                }
            }
        )
    }
}

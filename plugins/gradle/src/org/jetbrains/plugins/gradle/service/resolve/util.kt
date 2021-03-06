/*
 * Copyright 2000-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jetbrains.plugins.gradle.service.resolve

import com.intellij.openapi.util.Key
import com.intellij.psi.*
import com.intellij.psi.scope.PsiScopeProcessor
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.plugins.gradle.util.GradleConstants.EXTENSION
import org.jetbrains.plugins.groovy.codeInspection.assignment.GrMethodCallInfo
import org.jetbrains.plugins.groovy.lang.psi.api.statements.blocks.GrClosableBlock
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrMethodCall
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrReferenceExpression
import org.jetbrains.plugins.groovy.lang.psi.impl.GroovyPsiManager
import org.jetbrains.plugins.groovy.lang.psi.impl.statements.expressions.TypesUtil
import org.jetbrains.plugins.groovy.lang.psi.impl.synthetic.GroovyScriptClass
import org.jetbrains.plugins.groovy.lang.psi.util.GroovyPropertyUtils
import org.jetbrains.plugins.groovy.lang.psi.util.PsiUtil
import org.jetbrains.plugins.groovy.lang.resolve.NON_CODE
import org.jetbrains.plugins.groovy.lang.resolve.ResolveUtil.processAllDeclarations
import org.jetbrains.plugins.groovy.lang.resolve.delegatesTo.getDelegatesToInfo
import org.jetbrains.plugins.groovy.lang.resolve.processors.AccessorResolverProcessor
import org.jetbrains.plugins.groovy.lang.resolve.processors.ClassHint
import org.jetbrains.plugins.groovy.lang.resolve.processors.MethodResolverProcessor
import java.util.*

/**
 * @author Vladislav.Soroka
 * @since 11/11/2016
 */
internal fun PsiClass?.isResolvedInGradleScript() = this is GroovyScriptClass && this.containingFile.isGradleScript()

internal fun PsiFile?.isGradleScript() = this?.originalFile?.virtualFile?.extension == EXTENSION

@JvmField val RESOLVED_CODE = Key.create<Boolean?>("gradle.resolved")

fun processDeclarations(aClass: PsiClass,
                        processor: PsiScopeProcessor,
                        state: ResolveState,
                        place: PsiElement): Boolean {
  val name = processor.getHint(com.intellij.psi.scope.NameHint.KEY)?.getName(state)
  if (name == null) {
    aClass.processDeclarations(processor, state, null, place)
  }
  else {
    val propCandidate = place.references.singleOrNull()?.canonicalText
    if (propCandidate != null) {
      val closure = PsiTreeUtil.getParentOfType(place, GrClosableBlock::class.java)
      val typeToDelegate = closure?.let { getDelegatesToInfo(it)?.typeToDelegate }
      if (typeToDelegate != null) {
        val fqNameToDelegate = TypesUtil.getQualifiedName(typeToDelegate) ?: return true
        val classToDelegate = GroovyPsiManager.getInstance(place.project).findClassWithCache(fqNameToDelegate,
                                                                                             place.resolveScope) ?: return true
        if (classToDelegate !== aClass) {
          val parent = place.parent
          if (parent is GrMethodCall) {
            if (canBeMethodOf(propCandidate, parent, typeToDelegate)) return true
          }
        }
      }
    }

    val lValue: Boolean = place is GrReferenceExpression && PsiUtil.isLValue(place);
    if (!lValue) {
      val isSetterCandidate = name.startsWith("set")
      val isGetterCandidate = name.startsWith("get")
      val processedSignatures = HashSet<List<String>>()
      if (isGetterCandidate || !isSetterCandidate) {
        val propertyName = name.removePrefix("get").decapitalize()
        for (method in aClass.findMethodsByName(propertyName, true)) {
          processedSignatures.add(method.getSignature(PsiSubstitutor.EMPTY).parameterTypes.map({ it.canonicalText }))
          place.putUserData(RESOLVED_CODE, true)
          if (!processor.execute(method, state)) return false
        }
        for (method in aClass.findMethodsByName("set" + propertyName.capitalize(), true)) {
          if (PsiType.VOID != method.returnType) continue
          if (processedSignatures.contains(method.getSignature(PsiSubstitutor.EMPTY).parameterTypes.map({ it.canonicalText }))) continue
          processedSignatures.add(method.getSignature(PsiSubstitutor.EMPTY).parameterTypes.map({ it.canonicalText }))
          place.putUserData(RESOLVED_CODE, true)
          if (!processor.execute(method, state)) return false
        }
      }
      if (!isGetterCandidate && !isSetterCandidate) {
        for (method in aClass.findMethodsByName("get" + name.capitalize(), true)) {
          if (processedSignatures.contains(method.getSignature(PsiSubstitutor.EMPTY).parameterTypes.map({ it.canonicalText }))) continue
          processedSignatures.add(method.getSignature(PsiSubstitutor.EMPTY).parameterTypes.map({ it.canonicalText }))
          place.putUserData(RESOLVED_CODE, true)
          if (!processor.execute(method, state)) return false
        }
      }
      for (method in aClass.findMethodsByName(name, true)) {
        if (processedSignatures.contains(method.getSignature(PsiSubstitutor.EMPTY).parameterTypes.map({ it.canonicalText }))) continue
        place.putUserData(RESOLVED_CODE, true)
        if (!processor.execute(method, state)) return false
      }
    }
    else {
      for (method in aClass.findMethodsByName(name, true)) {
        place.putUserData(RESOLVED_CODE, true)
        if (!processor.execute(method, state)) return false
      }
    }
  }
  return true
}

fun canBeMethodOf(methodName: String,
                  place: GrMethodCall,
                  type: PsiType): Boolean {
  val methodCallInfo = GrMethodCallInfo(place)
  val invoked = methodCallInfo.invokedExpression ?: return false
  val argumentTypes = methodCallInfo.argumentTypes

  val thisType = TypesUtil.boxPrimitiveType(type, place.manager, place.resolveScope)
  val processor = MethodResolverProcessor(methodName, invoked, false, thisType, argumentTypes, PsiType.EMPTY_ARRAY, false)
  val state = ResolveState.initial().let {
    it.put(ClassHint.RESOLVE_CONTEXT, invoked)
    it.put(NON_CODE, false)
  }
  processAllDeclarations(thisType, processor, state, invoked)
  val hasApplicableMethods = processor.hasApplicableCandidates()
  if (hasApplicableMethods) {
    return true
  }

  //search for getters
  for (getterName in GroovyPropertyUtils.suggestGettersName(methodName)) {
    val getterResolver = AccessorResolverProcessor(getterName, methodName, invoked, true, thisType, PsiType.EMPTY_ARRAY)
    processAllDeclarations(thisType, getterResolver, state, invoked)
    if (getterResolver.hasApplicableCandidates()) {
      return true
    }
  }
  //search for setters
  for (setterName in GroovyPropertyUtils.suggestSettersName(methodName)) {
    val getterResolver = AccessorResolverProcessor(setterName, methodName, invoked, false, thisType, PsiType.EMPTY_ARRAY)
    processAllDeclarations(thisType, getterResolver, state, invoked)
    if (getterResolver.hasApplicableCandidates()) {
      return true
    }
  }

  return false
}

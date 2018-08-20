/*
 * Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.scripting.compiler.plugin

import com.intellij.openapi.fileTypes.LanguageFileType
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.NameUtils
import org.jetbrains.kotlin.psi.KtScript
import org.jetbrains.kotlin.script.KotlinScriptDefinition
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.starProjectedType
import kotlin.script.experimental.api.*
import kotlin.script.experimental.dependencies.DependenciesResolver
import kotlin.script.experimental.jvm.impl.BridgeDependenciesResolver
import kotlin.script.experimental.location.ScriptExpectedLocation
import kotlin.script.experimental.util.getOrError
import kotlin.script.experimental.util.getOrNull

// temporary trick with passing Any as a template and overwriting it below, TODO: fix after introducing new script definitions hierarchy
abstract class KotlinScriptDefinitionAdapterFromNewAPIBase : KotlinScriptDefinition(Any::class) {

    protected abstract val scriptDefinition: ScriptDefinition

    protected abstract val hostEnvironment: ScriptingEnvironment

    abstract val scriptFileExtensionWithDot: String

    open val baseClass: KClass<*> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        getScriptingClass(scriptDefinition.getOrError(ScriptDefinition.baseClass))
    }

    override val template: KClass<*> get() = baseClass

    override val name: String
        get() = scriptDefinition.getOrNull(ScriptDefinition.name) ?: "Kotlin Script"

    override val fileType: LanguageFileType = KotlinFileType.INSTANCE

    override fun isScript(fileName: String): Boolean =
        fileName.endsWith(scriptFileExtensionWithDot)

    override fun getScriptName(script: KtScript): Name {
        val fileBasedName = NameUtils.getScriptNameForFile(script.containingKtFile.name)
        return Name.identifier(fileBasedName.identifier.removeSuffix(scriptFileExtensionWithDot))
    }

    override val annotationsForSamWithReceivers: List<String>
        get() = emptyList()

    override val dependencyResolver: DependenciesResolver by lazy(LazyThreadSafetyMode.PUBLICATION) {
        BridgeDependenciesResolver(scriptDefinition, null)
    }

    override val acceptedAnnotations: List<KClass<out Annotation>> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        scriptDefinition.getOrNull(ScriptDefinition.refineConfigurationOnAnnotations)
            .orEmpty()
            .map { getScriptingClass(it) as KClass<out Annotation> }
    }

    override val implicitReceivers: List<KType> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        scriptDefinition.getOrNull(ScriptDefinition.scriptImplicitReceivers)
            .orEmpty()
            .map { getScriptingClass(it).starProjectedType }
    }

    override val environmentVariables: List<Pair<String, KType>> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        scriptDefinition.getOrNull(ScriptDefinition.contextVariables)
            ?.map { (k, v) -> k to getScriptingClass(v).starProjectedType }.orEmpty()
    }

    override val additionalCompilerArguments: List<String>
        get() = scriptDefinition.getOrNull(ScriptDefinition.compilerOptions)
            .orEmpty()

    override val scriptExpectedLocations: List<ScriptExpectedLocation> =
        listOf(
            ScriptExpectedLocation.SourcesOnly,
            ScriptExpectedLocation.TestsOnly
        )

    override val targetClassAnnotations: List<Annotation>
        get() = scriptDefinition.getOrNull(ScriptDefinition.generatedClassAnnotations)
            .orEmpty()

    override val targetMethodAnnotations: List<Annotation>
        get() = scriptDefinition.getOrNull(ScriptDefinition.generatedMethodAnnotations)
            .orEmpty()

    private val scriptingClassGetter by lazy(LazyThreadSafetyMode.PUBLICATION) {
        hostEnvironment.getOrNull(ScriptingEnvironment.getScriptingClass)
            ?: throw IllegalArgumentException("Expecting 'getScriptingClass' property in the scripting environment")
    }

    private fun getScriptingClass(type: KotlinType) =
        scriptingClassGetter(
            type,
            KotlinScriptDefinition::class, // Assuming that the KotlinScriptDefinition class is loaded in the proper classloader
            hostEnvironment
        )
}


class KotlinScriptDefinitionAdapterFromNewAPI(
    override val scriptDefinition: ScriptDefinition,
    override val hostEnvironment: ScriptingEnvironment
) : KotlinScriptDefinitionAdapterFromNewAPIBase() {

    override val name: String get() = scriptDefinition.getOrNull(ScriptDefinition.name) ?: super.name

    override val scriptFileExtensionWithDot =
        "." + (scriptDefinition.getOrNull(ScriptDefinition.fileExtension) ?: "kts")
}

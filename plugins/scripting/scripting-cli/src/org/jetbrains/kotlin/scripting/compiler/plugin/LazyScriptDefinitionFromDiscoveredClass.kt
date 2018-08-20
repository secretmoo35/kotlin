/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.scripting.compiler.plugin

import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import java.io.File
import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.annotations.KotlinScriptFileExtension
import kotlin.script.experimental.api.*
import kotlin.script.experimental.definitions.createScriptDefinitionFromAnnotatedBaseClass
import kotlin.script.experimental.jvm.JvmDependency
import kotlin.script.experimental.jvm.defaultJvmScriptingEnvironment

class LazyScriptDefinitionFromDiscoveredClass internal constructor(
    private val annotationsFromAsm: ArrayList<BinAnnData>,
    private val className: String,
    private val classpath: List<File>,
    private val messageCollector: MessageCollector
) : KotlinScriptDefinitionAdapterFromNewAPIBase() {

    constructor(
        classBytes: ByteArray,
        className: String,
        classpath: List<File>,
        messageCollector: MessageCollector
    ) : this(loadAnnotationsFromClass(classBytes), className, classpath, messageCollector)

    override val hostEnvironment: ScriptingEnvironment by lazy(LazyThreadSafetyMode.PUBLICATION) {
        ScriptingEnvironment {
            include(defaultJvmScriptingEnvironment)
            configurationDependencies(JvmDependency(classpath))
        }
    }

    override val scriptDefinition: ScriptDefinition by lazy(LazyThreadSafetyMode.PUBLICATION) {
        messageCollector.report(
            CompilerMessageSeverity.LOGGING,
            "Configure scripting: loading script definition class $className using classpath $classpath\n.  ${Thread.currentThread().stackTrace}"
        )
        try {
            createScriptDefinitionFromAnnotatedBaseClass(
                KotlinType(className),
                hostEnvironment,
                LazyScriptDefinitionFromDiscoveredClass::class
            )
        } catch (ex: ClassNotFoundException) {
            messageCollector.report(CompilerMessageSeverity.ERROR, "Cannot find script definition class $className")
            InvalidScriptDefinition
        } catch (ex: Exception) {
            messageCollector.report(
                CompilerMessageSeverity.ERROR,
                "Error processing script definition class $className: ${ex.message}"
            )
            InvalidScriptDefinition
        }
    }

    override val scriptFileExtensionWithDot: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val extFromAnn = (
                annotationsFromAsm.find { it.name == KotlinScriptFileExtension::class.simpleName!! }?.args
                    ?: annotationsFromAsm.find { it.name == KotlinScript::class.simpleName }?.args
                )?.find { it.name == "extension" }?.value
        val ext = extFromAnn
            ?: scriptDefinition.let {
                it[ScriptDefinition.fileExtension] ?: "kts"
            }
        ".$ext"
    }

    override val name: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        annotationsFromAsm.find { it.name == KotlinScript::class.simpleName!! }?.args?.find { it.name == "name" }?.value
            ?: super.name
    }
}

val InvalidScriptDefinition = ScriptDefinition()

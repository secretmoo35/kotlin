/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package kotlin.script.experimental.jvmhost

import kotlin.script.experimental.api.KotlinType
import kotlin.script.experimental.api.ScriptDefinition
import kotlin.script.experimental.api.ScriptEvaluator
import kotlin.script.experimental.api.ScriptingEnvironment
import kotlin.script.experimental.definitions.createScriptDefinitionFromAnnotatedBaseClass
import kotlin.script.experimental.host.BasicScriptingHost
import kotlin.script.experimental.jvm.defaultJvmScriptingEnvironment

open class BasicJvmScriptingHost(
    hostEnvironment: ScriptingEnvironment = defaultJvmScriptingEnvironment,
    compiler: JvmScriptCompiler = JvmScriptCompiler(hostEnvironment),
    evaluator: ScriptEvaluator = BasicJvmScriptEvaluator()
) : BasicScriptingHost(compiler, evaluator)


inline fun <reified T : Any> createBasicScriptDefinitionFromAnnotatedBaseClass(
    hostEnvironment: ScriptingEnvironment = defaultJvmScriptingEnvironment,
    noinline body: ScriptDefinition.Builder.() -> Unit = {}
): ScriptDefinition = createScriptDefinitionFromAnnotatedBaseClass(KotlinType(T::class), hostEnvironment, ScriptDefinition::class, body)

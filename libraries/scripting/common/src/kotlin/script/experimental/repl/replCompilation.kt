/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package kotlin.script.experimental.repl

import kotlin.script.experimental.api.*

interface ReplSnippetChecker {

    suspend operator fun invoke(
        snippet: ReplSnippetSource,
        state: ReplStageState<*>,
        scriptDefinition: ScriptDefinition
    ): ResultWithDiagnostics<Unit>
}

interface ReplSnippetCompiler {

    suspend operator fun invoke(
        snippet: ReplSnippetSource,
        state: ReplStageState<*>,
        scriptDefinition: ScriptDefinition
    ): ResultWithDiagnostics<CompiledReplSnippet<*>>
}

interface CompiledReplSnippet<ScriptBase : Any> : CompiledScript<ScriptBase>

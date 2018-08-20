/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.jps.platforms

import org.jetbrains.jps.ModuleChunk
import org.jetbrains.jps.builders.storage.BuildDataPaths
import org.jetbrains.jps.incremental.CompileContext
import org.jetbrains.jps.incremental.ModuleBuildTarget
import org.jetbrains.jps.incremental.ProjectBuildException
import org.jetbrains.jps.model.java.JpsJavaClasspathKind
import org.jetbrains.jps.model.java.JpsJavaExtensionService
import org.jetbrains.jps.model.module.JpsModule
import org.jetbrains.jps.util.JpsPathUtil
import org.jetbrains.kotlin.build.BuildMetaInfo
import org.jetbrains.kotlin.build.BuildMetaInfoFactory
import org.jetbrains.kotlin.build.GeneratedFile
import org.jetbrains.kotlin.cli.common.arguments.CommonCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.compilerRunner.JpsCompilerEnvironment
import org.jetbrains.kotlin.config.ApiVersion
import org.jetbrains.kotlin.config.LanguageVersion
import org.jetbrains.kotlin.config.Services
import org.jetbrains.kotlin.incremental.CacheAttributesDiff
import org.jetbrains.kotlin.incremental.ChangesCollector
import org.jetbrains.kotlin.incremental.ExpectActualTrackerImpl
import org.jetbrains.kotlin.incremental.components.ExpectActualTracker
import org.jetbrains.kotlin.incremental.components.LookupTracker
import org.jetbrains.kotlin.jps.build.*
import org.jetbrains.kotlin.jps.incremental.CacheVersionProvider
import org.jetbrains.kotlin.jps.incremental.JpsIncrementalCache
import org.jetbrains.kotlin.jps.model.productionOutputFilePath
import org.jetbrains.kotlin.jps.model.testOutputFilePath
import org.jetbrains.kotlin.modules.TargetId
import org.jetbrains.kotlin.progress.CompilationCanceledException
import org.jetbrains.kotlin.progress.CompilationCanceledStatus
import org.jetbrains.kotlin.utils.addIfNotNull
import java.io.File

/**
 * Properties and actions for Kotlin test / production module build target.
 */
abstract class KotlinModuleBuildTarget<BuildMetaInfoType : BuildMetaInfo>(
    val context: CompileContext,
    val jpsModuleBuildTarget: ModuleBuildTarget
) {
    // TODO(1.2.80): got rid of context and replace it with kotlinContext
    val kotlinContext: KotlinCompilation
        get() = context.kotlinCompilation

    abstract val globalLookupCacheId: String

    val initialLocalCacheAttributesDiff: CacheAttributesDiff

    abstract val isIncrementalCompilationEnabled: Boolean

    init {
        initialLocalCacheAttributesDiff = CacheVersionProvider(kotlinContext.dataPaths, isIncrementalCompilationEnabled)
            .readLocalCacheStatus(jpsModuleBuildTarget)
    }

    val module: JpsModule
        get() = jpsModuleBuildTarget.module

    val isTests: Boolean
        get() = jpsModuleBuildTarget.isTests

    val targetId: TargetId
        get() {
            // Since IDEA 2016 each gradle source root is imported as a separate module.
            // One gradle module X is imported as two JPS modules:
            // 1. X-production with one production target;
            // 2. X-test with one test target.
            // This breaks kotlin code since internal members' names are mangled using module name.
            // For example, a declaration of a function 'f' in 'X-production' becomes 'fXProduction', but a call 'f' in 'X-test' becomes 'fXTest()'.
            // The workaround is to replace a name of such test target with the name of corresponding production module.
            // See KT-11993.
            val name = relatedProductionModule?.name ?: jpsModuleBuildTarget.id
            return TargetId(name, jpsModuleBuildTarget.targetType.typeId)
        }

    val outputDir by lazy {
        val explicitOutputPath = if (isTests) module.testOutputFilePath else module.productionOutputFilePath
        val explicitOutputDir = explicitOutputPath?.let { File(it).absoluteFile.parentFile }
        return@lazy explicitOutputDir
                ?: jpsModuleBuildTarget.outputDir
                ?: throw ProjectBuildException("No output directory found for " + this)
    }

    val friendBuildTargets: List<KotlinModuleBuildTarget<*>>
        get() {
            val result = mutableListOf<KotlinModuleBuildTarget<*>>()

            if (isTests) {
                result.addIfNotNull(context.kotlinBuildTargets[module.productionBuildTarget])
                result.addIfNotNull(context.kotlinBuildTargets[relatedProductionModule?.productionBuildTarget])
            }

            return result.filter { it.sources.isNotEmpty() }
        }

    val friendOutputDirs: List<File>
        get() = friendBuildTargets.mapNotNull {
            JpsJavaExtensionService.getInstance().getOutputDirectory(it.module, false)
        }

    private val relatedProductionModule: JpsModule?
        get() = JpsJavaExtensionService.getInstance().getTestModuleProperties(module)?.productionModule

    val allDependencies by lazy {
        JpsJavaExtensionService.dependencies(module).recursively().exportedOnly()
            .includedIn(JpsJavaClasspathKind.compile(isTests))
    }

    val sources: Map<File, Source> by lazy {
        mutableMapOf<File, Source>().also { result ->
            collectSources(result)
        }
    }

    private fun collectSources(receiver: MutableMap<File, Source>) {
        val moduleExcludes = module.excludeRootsList.urls.mapTo(java.util.HashSet(), JpsPathUtil::urlToFile)

        val compilerExcludes = JpsJavaExtensionService.getInstance()
            .getOrCreateCompilerConfiguration(module.project)
            .compilerExcludes

        val buildRootIndex = context.projectDescriptor.buildRootIndex
        val roots = buildRootIndex.getTargetRoots(jpsModuleBuildTarget, context)
        roots.forEach { rootDescriptor ->
            val isIncludedSourceRoot = rootDescriptor is KotlinIncludedModuleSourceRoot

            rootDescriptor.root.walkTopDown()
                .onEnter { file -> file !in moduleExcludes }
                .forEach { file ->
                    if (!compilerExcludes.isExcluded(file) && file.isFile && file.isKotlinSourceFile) {
                        receiver[file] = Source(file, isIncludedSourceRoot)
                    }
                }

        }
    }

    /**
     * @property isIncludedSourceRoot for reporting errors during cross-compilation common module sources
     */
    class Source(
        val file: File,
        val isIncludedSourceRoot: Boolean
    )

    fun isFromIncludedSourceRoot(file: File): Boolean = sources[file]?.isIncludedSourceRoot == true

    val sourceFiles: Collection<File>
        get() = sources.values.map { it.file }

    override fun toString() = jpsModuleBuildTarget.toString()

    /**
     * Called for `ModuleChunk.representativeTarget`
     */
    abstract fun compileModuleChunk(
        chunk: ModuleChunk,
        commonArguments: CommonCompilerArguments,
        dirtyFilesHolder: KotlinDirtySourceFilesHolder,
        environment: JpsCompilerEnvironment
    ): Boolean

    protected fun reportAndSkipCircular(
        chunk: ModuleChunk,
        environment: JpsCompilerEnvironment
    ): Boolean {
        if (chunk.modules.size > 1) {
            // We do not support circular dependencies, but if they are present, we do our best should not break the build,
            // so we simply yield a warning and report NOTHING_DONE
            environment.messageCollector.report(
                CompilerMessageSeverity.STRONG_WARNING,
                "Circular dependencies are not supported. The following modules depend on each other: "
                        + chunk.modules.joinToString(", ") { it.name } + " "
                        + "Kotlin is not compiled for these modules"
            )

            return true
        }

        return false
    }

    open fun doAfterBuild() {
    }

    open val hasCaches: Boolean = true

    abstract fun createCacheStorage(paths: BuildDataPaths): JpsIncrementalCache

    /**
     * Called for `ModuleChunk.representativeTarget`
     */
    open fun updateChunkMappings(
        chunk: ModuleChunk,
        dirtyFilesHolder: KotlinDirtySourceFilesHolder,
        outputItems: Map<ModuleBuildTarget, Iterable<GeneratedFile>>,
        incrementalCaches: Map<ModuleBuildTarget, JpsIncrementalCache>
    ) {
        // by default do nothing
    }

    open fun updateCaches(
        jpsIncrementalCache: JpsIncrementalCache,
        files: List<GeneratedFile>,
        changesCollector: ChangesCollector,
        environment: JpsCompilerEnvironment
    ) {
        val expectActualTracker = environment.services[ExpectActualTracker::class.java] as ExpectActualTrackerImpl
        jpsIncrementalCache.registerComplementaryFiles(expectActualTracker)
    }

    open fun makeServices(
        builder: Services.Builder,
        incrementalCaches: Map<ModuleBuildTarget, JpsIncrementalCache>,
        lookupTracker: LookupTracker,
        exceptActualTracer: ExpectActualTracker
    ) {
        with(builder) {
            register(LookupTracker::class.java, lookupTracker)
            register(ExpectActualTracker::class.java, exceptActualTracer)
            register(CompilationCanceledStatus::class.java, object : CompilationCanceledStatus {
                override fun checkCanceled() {
                    if (context.cancelStatus.isCanceled) throw CompilationCanceledException()
                }
            })
        }
    }

    protected fun collectSourcesToCompile(dirtyFilesHolder: KotlinDirtySourceFilesHolder) =
        collectSourcesToCompile(this, dirtyFilesHolder)

    /**
     * Should be used only for particular target in chunk (jvm)
     */
    protected fun collectSourcesToCompile(
        target: KotlinModuleBuildTarget<BuildMetaInfoType>,
        dirtyFilesHolder: KotlinDirtySourceFilesHolder
    ): Collection<File> {
        // Should not be cached since may be vary in different rounds

        val jpsModuleTarget = target.jpsModuleBuildTarget
        return when {
            isIncrementalCompilationEnabled -> dirtyFilesHolder.getDirtyFiles(jpsModuleTarget)
            else -> target.sourceFiles
        }
    }

    protected fun checkShouldCompileAndLog(dirtyFilesHolder: KotlinDirtySourceFilesHolder, moduleSources: Collection<File>) =
        checkShouldCompileAndLog(this, dirtyFilesHolder, moduleSources)

    /**
     * Should be used only for particular target in chunk (jvm)
     */
    protected fun checkShouldCompileAndLog(
        target: KotlinModuleBuildTarget<BuildMetaInfoType>,
        dirtyFilesHolder: KotlinDirtySourceFilesHolder,
        moduleSources: Collection<File>
    ): Boolean {
        val hasRemovedSources = dirtyFilesHolder.getRemovedFiles(target.jpsModuleBuildTarget).isNotEmpty()
        val hasDirtyOrRemovedSources = moduleSources.isNotEmpty() || hasRemovedSources
        if (hasDirtyOrRemovedSources) {
            val logger = context.loggingManager.projectBuilderLogger
            if (logger.isEnabled) {
                logger.logCompiledFiles(moduleSources, KotlinBuilder.KOTLIN_BUILDER_NAME, "Compiling files:")
            }
        }

        return hasDirtyOrRemovedSources
    }

    abstract val buildMetaInfoFactory: BuildMetaInfoFactory<BuildMetaInfoType>

    abstract val buildMetaInfoFileName: String

    fun isVersionChanged(chunk: KotlinChunk, buildMetaInfo: BuildMetaInfo): Boolean {
        val file = chunk.buildMetaInfoFile(jpsModuleBuildTarget)
        if (!file.exists()) return false

        val prevBuildMetaInfo =
            try {
                buildMetaInfoFactory.deserializeFromString(file.readText()) ?: return false
            } catch (e: Exception) {
                KotlinBuilder.LOG.error("Could not deserialize build meta info", e)
                return false
            }

        val prevLangVersion = LanguageVersion.fromVersionString(prevBuildMetaInfo.languageVersionString)
        val prevApiVersion = ApiVersion.parse(prevBuildMetaInfo.apiVersionString)

        val reasonToRebuild = when {
            chunk.langVersion != prevLangVersion -> "Language version was changed ($prevLangVersion -> ${chunk.langVersion})"
            chunk.apiVersion != prevApiVersion -> "Api version was changed ($prevApiVersion -> ${chunk.apiVersion})"
            prevLangVersion != LanguageVersion.KOTLIN_1_0 && prevBuildMetaInfo.isEAP && !buildMetaInfo.isEAP -> {
                // If EAP->Non-EAP build with IC, then rebuild all kotlin
                "Last build was compiled with EAP-plugin"
            }
            else -> null
        }

        if (reasonToRebuild != null) {
            KotlinBuilder.LOG.info("$reasonToRebuild. Performing non-incremental rebuild (kotlin only)")
            return true
        }

        return false
    }

    private fun checkRepresentativeTarget(chunk: KotlinChunk) {
        check(chunk.representativeTarget == this)
    }

    private fun checkRepresentativeTarget(chunk: ModuleChunk) {
        check(chunk.representativeTarget() == jpsModuleBuildTarget)
    }

    private fun checkRepresentativeTarget(chunk: List<KotlinModuleBuildTarget<*>>) {
        check(chunk.first() == this)
    }
}
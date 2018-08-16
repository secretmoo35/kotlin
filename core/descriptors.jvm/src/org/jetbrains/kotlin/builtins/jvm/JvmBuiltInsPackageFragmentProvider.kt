/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.builtins.jvm

import org.jetbrains.kotlin.builtins.functions.BuiltInFictitiousFunctionClassFactory
import org.jetbrains.kotlin.builtins.functions.CoroutinesFictitiousPackage
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.descriptors.NotFoundClasses
import org.jetbrains.kotlin.descriptors.deserialization.AdditionalClassPartsProvider
import org.jetbrains.kotlin.descriptors.deserialization.PlatformDependentDeclarationFilter
import org.jetbrains.kotlin.incremental.components.LookupTracker
import org.jetbrains.kotlin.load.kotlin.KotlinClassFinder
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.resolve.scopes.MemberScope
import org.jetbrains.kotlin.serialization.deserialization.*
import org.jetbrains.kotlin.serialization.deserialization.builtins.BuiltInSerializerProtocol
import org.jetbrains.kotlin.serialization.deserialization.builtins.BuiltInsPackageFragmentImpl
import org.jetbrains.kotlin.storage.StorageManager

class JvmBuiltInsPackageFragmentProvider(
    storageManager: StorageManager,
    finder: KotlinClassFinder,
    moduleDescriptor: ModuleDescriptor,
    notFoundClasses: NotFoundClasses,
    additionalClassPartsProvider: AdditionalClassPartsProvider,
    platformDependentDeclarationFilter: PlatformDependentDeclarationFilter
) : AbstractDeserializedPackageFragmentProvider(storageManager, finder, moduleDescriptor) {
    init {
        components = DeserializationComponents(
            storageManager,
            moduleDescriptor,
            DeserializationConfiguration.Default, // TODO
            DeserializedClassDataFinder(this),
            AnnotationAndConstantLoaderImpl(moduleDescriptor, notFoundClasses, BuiltInSerializerProtocol),
            this,
            LocalClassifierTypeSettings.Default,
            ErrorReporter.DO_NOTHING,
            LookupTracker.DO_NOTHING,
            FlexibleTypeDeserializer.ThrowException,
            listOf(
                BuiltInFictitiousFunctionClassFactory(storageManager, moduleDescriptor),
                JvmBuiltInClassDescriptorFactory(storageManager, moduleDescriptor)
            ),
            notFoundClasses,
            ContractDeserializer.DEFAULT,
            additionalClassPartsProvider, platformDependentDeclarationFilter,
            BuiltInSerializerProtocol.extensionRegistry
        )
    }

    override fun findPackage(fqName: FqName): DeserializedPackageFragment? {
        if (fqName == DescriptorUtils.COROUTINES_PACKAGE_FQ_NAME_RELEASE) {
            return CoroutinesFictitiousDeserializerPackageFragment(storageManager, moduleDescriptor)
        }
        return finder.findBuiltInsData(fqName)?.let { inputStream ->
            BuiltInsPackageFragmentImpl.create(fqName, storageManager, moduleDescriptor, inputStream)
        }
    }
}

class CoroutinesFictitiousDeserializerPackageFragment(storageManager: StorageManager, moduleDescriptor: ModuleDescriptor):
    DeserializedPackageFragment(DescriptorUtils.COROUTINES_PACKAGE_FQ_NAME_RELEASE, storageManager, moduleDescriptor) {
    override fun initialize(components: DeserializationComponents) {
    }

    override val classDataFinder: ClassDataFinder
        get() = ClassDataFinder { null }

    override fun getMemberScope(): MemberScope {
        return packageFragment.getMemberScope()
    }

    private val packageFragment = CoroutinesFictitiousPackage(storageManager, moduleDescriptor)
}
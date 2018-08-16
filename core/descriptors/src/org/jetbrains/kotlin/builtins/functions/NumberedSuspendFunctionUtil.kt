/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.builtins.functions

import org.jetbrains.kotlin.descriptors.ClassifierDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.descriptors.PackageFragmentDescriptor
import org.jetbrains.kotlin.descriptors.impl.PackageFragmentDescriptorImpl
import org.jetbrains.kotlin.incremental.components.LookupLocation
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.resolve.scopes.DescriptorKindFilter
import org.jetbrains.kotlin.resolve.scopes.MemberScope
import org.jetbrains.kotlin.resolve.scopes.MemberScopeImpl
import org.jetbrains.kotlin.storage.StorageManager
import org.jetbrains.kotlin.utils.Printer

class CoroutinesFictitiousPackage(storageManager: StorageManager, module: ModuleDescriptor) :
    PackageFragmentDescriptorImpl(module, DescriptorUtils.COROUTINES_PACKAGE_FQ_NAME_RELEASE) {
    private val memberScope = NumberedSuspendFunctionsScope(storageManager, this)

    override fun getMemberScope(): MemberScope {
        return memberScope
    }
}

class NumberedSuspendFunctionsScope(
    private val storageManager: StorageManager,
    private val containingDeclaration: PackageFragmentDescriptor
) : MemberScopeImpl() {
    private val functionClassDescriptors by lazy {
        (0 until 22).map {
            FunctionClassDescriptor(
                storageManager,
                containingDeclaration,
                FunctionClassDescriptor.Kind.SuspendFunction,
                it
            )
        }
    }

    private val names by lazy {
        functionClassDescriptors.map { it.name }.toSet()
    }

    override fun printScopeStructure(p: Printer) {
    }

    override fun getClassifierNames(): Set<Name>? {
        return names
    }

    override fun getContributedDescriptors(
        kindFilter: DescriptorKindFilter,
        nameFilter: (Name) -> Boolean
    ): Collection<DeclarationDescriptor> {
        return functionClassDescriptors.filter { kindFilter.accepts(it) && nameFilter(it.name) }
    }

    override fun getContributedClassifier(name: Name, location: LookupLocation): ClassifierDescriptor? {
        return functionClassDescriptors.find { it.name == name }
    }
}

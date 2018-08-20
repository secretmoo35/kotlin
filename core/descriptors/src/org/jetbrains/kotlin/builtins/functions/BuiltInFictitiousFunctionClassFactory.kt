/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.builtins.functions

import org.jetbrains.kotlin.builtins.BuiltInsPackageFragment
import org.jetbrains.kotlin.builtins.functions.FunctionClassDescriptor.Kind
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.descriptors.deserialization.ClassDescriptorFactory
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.storage.StorageManager

/**
 * Produces descriptors representing the fictitious classes for function types, such as kotlin.Function1 or kotlin.reflect.KFunction2.
 */
class BuiltInFictitiousFunctionClassFactory(
        private val storageManager: StorageManager,
        private val module: ModuleDescriptor
) : ClassDescriptorFactory {

    private data class KindWithArity(val kind: Kind, val arity: Int)

    companion object {
        private fun parseClassName(className: String, packageFqName: FqName): KindWithArity? {
            val kind = FunctionClassDescriptor.Kind.byClassNamePrefix(packageFqName, className) ?: return null

            val prefix = kind.classNamePrefix

            val arity = toInt(className.substring(prefix.length)) ?: return null

            // TODO: validate arity, should be <= 255
            return KindWithArity(kind, arity)
        }

        @JvmStatic
        fun getFunctionalClassKind(className: String, packageFqName: FqName) =
                parseClassName(className, packageFqName)?.kind

        private fun toInt(s: String): Int? {
            if (s.isEmpty()) return null

            var result = 0
            for (c in s) {
                val d = c - '0'
                if (d !in 0..9) return null
                result = result * 10 + d
            }
            return result
        }
    }

    override fun shouldCreateClass(packageFqName: FqName, name: Name): Boolean {
        val string = name.asString()
        return (string.startsWith("Function") || string.startsWith("KFunction") ||
                string.startsWith("SuspendFunction") || string.startsWith("KSuspendFunction")) // an optimization
               && parseClassName(string, packageFqName) != null
    }

    override fun createClass(classId: ClassId): ClassDescriptor? {
        if (classId.isLocal || classId.isNestedClass) return null

        val className = classId.relativeClassName.asString()
        if ("Function" !in className) return null // An optimization

        val packageFqName = classId.packageFqName
        val (kind, arity) = parseClassName(className, packageFqName) ?: return null

        if (kind == Kind.SuspendFunction) {
            return module.builtIns.getSuspendFunction(arity)
        }

        val containingPackageFragment = module.getPackage(packageFqName).fragments.filterIsInstance<BuiltInsPackageFragment>().first()

        return FunctionClassDescriptor(storageManager, containingPackageFragment, kind, arity)
    }

    override fun getAllContributedClassesIfPossible(packageFqName: FqName): Collection<ClassDescriptor> {
        // We don't want to return 256 classes here since it would cause them to appear in every import list of every file
        // and likely slow down compilation very much
        return emptySet()
    }
}

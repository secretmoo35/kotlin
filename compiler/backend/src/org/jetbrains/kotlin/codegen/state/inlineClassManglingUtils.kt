/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.codegen.state

import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameUnsafe
import org.jetbrains.kotlin.resolve.isInlineClassType
import org.jetbrains.kotlin.types.KotlinType
import java.security.MessageDigest

fun getInlineClassValueParametersManglingSuffix(descriptor: CallableMemberDescriptor): String? {
    if (descriptor !is FunctionDescriptor) return null
    if (descriptor is ConstructorDescriptor) return null

    val actualValueParameterTypes = listOfNotNull(descriptor.extensionReceiverParameter?.type) + descriptor.valueParameters.map { it.type }

    if (actualValueParameterTypes.none { it.isInlineClassType() || it.isTypeParameterWithInlineClassUpperBound() }) return null

    return md5radix36string(collectSignatureForMangling(actualValueParameterTypes))
}

private fun collectSignatureForMangling(types: List<KotlinType>): String {
    val sb = StringBuilder()
    for (type in types) {
        val descriptor = type.constructor.declarationDescriptor ?: continue
        when (descriptor) {
            is ClassDescriptor -> {
                sb.append('L')
                sb.append(descriptor.fqNameUnsafe.toString())
                sb.append(';')
            }
            is TypeParameterDescriptor -> {
                // TODO should it contain any of the upper bounds for type parameter?
                // These would currently produce same mangling suffixes:
                //  fun <T : InlineClass1> foo(x: T)
                //  fun <T : InlineClass2> foo(x: T)
                sb.append('T')
                sb.append(descriptor.name.asString())
                sb.append(';')
            }
        }
    }
    return sb.toString()
}

private fun md5radix36string(signatureForMangling: String): String {
    val d = MessageDigest.getInstance("MD5").digest(signatureForMangling.toByteArray())
    var acc = 0L
    for (i in 0..4) {
        acc = (acc shl 8) + (d[i].toLong() and 0xFFL)
    }
    return acc.toString(36)
}

fun KotlinType.isTypeParameterWithInlineClassUpperBound(): Boolean {
    val descriptor = constructor.declarationDescriptor as? TypeParameterDescriptor ?: return false
    return descriptor.isWithInlineClassUpperBoundInner(hashSetOf(descriptor))
}

private fun TypeParameterDescriptor.isWithInlineClassUpperBoundInner(visited: MutableSet<TypeParameterDescriptor>): Boolean {
    for (type in typeConstructor.supertypes) {
        if (type.isInlineClassType()) return true

        val typeParameterDescriptor = type.constructor.declarationDescriptor as? TypeParameterDescriptor ?: continue
        if (!visited.add(typeParameterDescriptor)) continue
        if (typeParameterDescriptor.isWithInlineClassUpperBoundInner(visited)) return true
    }
    return false
}
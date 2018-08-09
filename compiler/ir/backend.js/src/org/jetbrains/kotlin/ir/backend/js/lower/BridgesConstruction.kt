/*
 * Copyright 2010-2018 JetBrains s.r.o.
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

package org.jetbrains.kotlin.ir.backend.js.lower

import org.jetbrains.kotlin.backend.common.ClassLoweringPass
import org.jetbrains.kotlin.backend.common.bridges.FunctionHandle
import org.jetbrains.kotlin.backend.common.bridges.generateBridges
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.backend.common.lower.irBlockBody
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.ir.backend.js.JsIrBackendContext
import org.jetbrains.kotlin.ir.backend.js.ir.JsIrBuilder
import org.jetbrains.kotlin.ir.backend.js.transformers.irToJs.isStatic
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.types.toKotlinType
import org.jetbrains.kotlin.ir.util.isInterface
import org.jetbrains.kotlin.ir.util.isReal
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.DescriptorUtils

// Constructs bridges for inherited generic functions
//
//  Example: for given class hierarchy
//
//          class C<T>  {
//            fun foo(t: T) = ...
//          }
//
//          class D : C<Int> {
//            override fun foo(t: Int) = impl
//          }
//
//  it adds method D that delegates generic calls to implementation:
//
//          class D : C<Int> {
//            override fun foo(t: Int) = impl
//            fun foo(t: Any?) = foo(t as Int)  // Constructed bridge
//          }
//
class BridgesConstruction(val context: JsIrBackendContext) : ClassLoweringPass {

    override fun lower(irClass: IrClass) {
        irClass.declarations
            .filterIsInstance<IrSimpleFunction>()
            .filter { !it.isStatic }
            .forEach { generateBridges(it, irClass) }

        irClass.declarations
            .filterIsInstance<IrProperty>()
            .flatMap { listOfNotNull(it.getter, it.setter) }
            .forEach { generateBridges(it, irClass) }
    }

    private fun generateBridges(function: IrSimpleFunction, irClass: IrClass) {
        // equals(Any?), hashCode(), toString() never need bridges
        if (DescriptorUtils.isMethodOfAny(function.descriptor))
            return

        val bridgesToGenerate = generateBridges(
            function = IrBasedFunctionHandle(function),
            signature = { FunctionAndSignature(it.function) }
        )

        for ((from, to) in bridgesToGenerate) {
            if (to.function.visibility == Visibilities.INVISIBLE_FAKE)
                continue

            if (!from.function.parentAsClass.isInterface &&
                from.function.isReal &&
                from.function.modality != Modality.ABSTRACT &&
                !to.function.isReal
            ) {
                continue
            }

            irClass.declarations.add(createBridge(function, from.function, to.function))
        }
    }

    // Ported from from jvm.lower.BridgeLowering
    private fun createBridge(
        function: IrSimpleFunction,
        bridge: IrSimpleFunction,
        delegateTo: IrSimpleFunction
    ): IrFunction {
        val newDispatchReceiver = bridge.dispatchReceiverParameter?.run {
            JsIrBuilder.buildValueParameter(name, index, type)
        }

        val newExtensionReceiver = bridge.extensionReceiverParameter?.run {
            JsIrBuilder.buildValueParameter(name, index, type)
        }

        // TODO: Support offsets for debug info
        val irFunction = JsIrBuilder.buildFunction(
            bridge.name,
            bridge.visibility,
            bridge.modality, // TODO: should copy modality?
            bridge.isInline,
            bridge.isExternal,
            bridge.isTailrec,
            bridge.isSuspend,
            IrDeclarationOrigin.BRIDGE
        ).apply {

            dispatchReceiverParameter = newDispatchReceiver?.also { it.parent = this }
            extensionReceiverParameter = newExtensionReceiver?.also { it.parent = this }
            typeParameters += bridge.typeParameters
            valueParameters += bridge.valueParameters.map { p ->
                JsIrBuilder.buildValueParameter(p.name, p.index, p.type).also {
                    it.parent = this
                }
            }
            annotations += bridge.annotations
            returnType = bridge.returnType
            parent = delegateTo.parent
        }

        // TODO: Add type casts
        context.createIrBuilder(irFunction.symbol).irBlockBody(irFunction) {
            val call = irCall(delegateTo.symbol)
            call.dispatchReceiver = irGet(irFunction.dispatchReceiverParameter!!)
            irFunction.extensionReceiverParameter?.let {
                call.extensionReceiver = irGet(it)
            }
            irFunction.valueParameters.mapIndexed { i, valueParameter ->
                call.putValueArgument(i, irGet(valueParameter))
            }
            +irReturn(call)
        }.apply {
            irFunction.body = this
        }

        return irFunction
    }
}

// Handle for common.bridges
data class IrBasedFunctionHandle(val function: IrSimpleFunction) : FunctionHandle {

    override val isDeclaration: Boolean = true

    override val isAbstract: Boolean =
        function.modality == Modality.ABSTRACT

    override val isInterfaceDeclaration=
        function.parentAsClass.isInterface

    override fun getOverridden()=
        function.overriddenSymbols.map { IrBasedFunctionHandle(it.owner) }
}

// Wrapper around function that compares and hashCodes it based on signature
// Designed to be used as a Signature type parameter in backend.common.bridges
class FunctionAndSignature(val function: IrSimpleFunction) {

    // TODO: Use type-upper-bound-based signature instead of Strings
    // Currently strings are used for compatibility with a hack-based name generator

    private data class Signature(
        val name: Name,
        val extensionReceiverType: String?,
        val valueParameters: List<String?>
    )

    private val signature = Signature(
        function.name,
        // TODO: should kotlinTypes be used here?
        function.extensionReceiverParameter?.type?.toKotlinType()?.toString(),
        function.valueParameters.map { it.type.toKotlinType()?.toString() }
    )

    override fun equals(other: Any?) =
        signature == (other as FunctionAndSignature).signature

    override fun hashCode(): Int = signature.hashCode()
}



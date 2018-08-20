// !LANGUAGE: +InlineClasses

inline class InlinePrimitive(val x: Int)
inline class InlineReference(val y: String)
inline class InlineNullablePrimitive(val x: Int?)
inline class InlineNullableReference(val y: String?)

object Test {
    fun withPrimitiveAsNullable(a: InlinePrimitive?) {}
    fun withReferenceAsNullable(a: InlineReference?) {}

    fun withNullablePrimitiveAsNullable(a: InlineNullablePrimitive?) {}
    fun withNullableReferenceAsNullable(a: InlineNullableReference?) {}
}

// method: Test::withPrimitiveAsNullable$uyf6cgi
// jvm signature: (LInlinePrimitive;)V
// generic signature: null

// method: Test::withReferenceAsNullable$4509en4r
// jvm signature: (Ljava/lang/String;)V
// generic signature: null

// method: Test::withNullablePrimitiveAsNullable$6wmxy8mb
// jvm signature: (LInlineNullablePrimitive;)V
// generic signature: null

// method: Test::withNullableReferenceAsNullable$22soa6uj
// jvm signature: (LInlineNullableReference;)V
// generic signature: null
// !LANGUAGE: +InlineClasses
// IGNORE_BACKEND: JVM_IR, JS_IR

inline class Id(val id: String)

inline class Name(val name: String)

inline class Password(val password: String)

fun Id.test() {
    if (id != "OK") throw AssertionError()
}

fun Name.test() {
    if (name != "OK") throw AssertionError()
}

fun test(password: Password) {
    if (password.password != "OK") throw AssertionError()
}

class Outer {
    fun Id.testExn() {
        if (id != "OK") throw AssertionError()
    }

    fun Name.testExn() {
        if (name != "OK") throw AssertionError()
    }

    fun testExn(password: Password) {
        if (password.password != "OK") throw AssertionError()
    }

    fun testExns() {
        Id("OK").testExn()
        Name("OK").testExn()
        testExn(Password("OK"))
    }
}

fun box(): String {
    Id("OK").test()
    Name("OK").test()
    test(Password("OK"))

    Outer().testExns()

    return "OK"
}
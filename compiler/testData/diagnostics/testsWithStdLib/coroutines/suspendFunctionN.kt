// !LANGUAGE: +Coroutines
// SKIP_TXT

fun test() {
    suspend {} is <!UNRESOLVED_REFERENCE!>SuspendFunction0<!><*>
}

// FIR_DUMP

val m2: Map<String, *>  = mapOf("baz" to "bat")
val bar: String get() = m2.<!IMPLICIT_NOTHING_TYPE_ARGUMENT_IN_RETURN_POSITION!>getValue<!>(null, ::bar)

fun foo() {
    val m1: Map<String, Any>  = mapOf("foo" to "bar")
    val foo: String by m1
    val baz: String by <!IMPLICIT_NOTHING_TYPE_ARGUMENT_IN_RETURN_POSITION!>m2<!>
    println(foo) // bar
    println(baz) // kotlin.KotlinNothingValueException
    println(bar)
}

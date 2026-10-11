package io.schemata.target

import io.schemata.core.ir.AnnotationValue
import io.schemata.core.ir.Annotations
import io.schemata.core.ir.Namespace
import io.schemata.lang.Span
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProtoFileOptionsTest {
    private val span = Span("s.schemata", 1, 1, 1, 5)

    private fun ns(name: String, vararg options: Pair<String, AnnotationValue>) =
        Namespace(name, emptyList(), span, Annotations(mapOf("proto" to mapOf(*options))))

    private fun str(value: String) = AnnotationValue.Str(value)

    private fun javaPackage(value: String?) =
        value?.let { arrayOf("java_package" to str(it)) } ?: emptyArray()

    @Test
    fun `keys are the language options in the order they are written`() {
        assertEquals(
            listOf(
                "java_package",
                "java_outer_classname",
                "java_multiple_files",
                "csharp_namespace",
                "go_package",
                "objc_class_prefix",
                "php_namespace",
                "php_class_prefix",
                "php_metadata_namespace",
                "ruby_package",
                "swift_prefix",
            ),
            ProtoFileOptions.keys.map { it.name },
        )
    }

    @Test
    fun `each key names the language whose code it moves`() {
        assertEquals(
            listOf(
                "Java",
                "Java",
                "Java",
                "C#",
                "Go",
                "Objective-C",
                "PHP",
                "PHP",
                "PHP",
                "Ruby",
                "Swift",
            ),
            ProtoFileOptions.keys.map { it.language },
        )
        assertEquals(
            ProtoFileOptions.Shape.BACKSLASHED,
            ProtoFileOptions.key("php_namespace")?.shape,
        )
        assertEquals(
            ProtoFileOptions.Shape.DOUBLE_COLON,
            ProtoFileOptions.key("ruby_package")?.shape,
        )
        assertEquals(ProtoFileOptions.Shape.NON_EMPTY, ProtoFileOptions.key("go_package")?.shape)
    }

    @Test
    fun `only java_multiple_files is a boolean`() {
        assertEquals(
            listOf("java_multiple_files"),
            ProtoFileOptions.keys.filter { it.bool }.map { it.name },
        )
    }

    @Test
    fun `a single schema resolves to the options it declares in key order`() {
        val resolved =
            ProtoFileOptions.of(
                listOf(ns("a", "go_package" to str("x/y"), "java_package" to str("com.a")))
            )
        assertEquals(listOf("java_package", "go_package"), resolved.values.keys.toList())
        assertEquals(emptyList(), resolved.conflicts)
    }

    @Test
    fun `no declaring member resolves to nothing`() {
        val resolved = ProtoFileOptions.of(listOf(ns("a"), ns("b")))
        assertEquals(emptyMap(), resolved.values)
        assertEquals(emptyList(), resolved.conflicts)
    }

    @Test
    fun `one declaring member gives the unit its value`() {
        val resolved = ProtoFileOptions.of(listOf(ns("a"), ns("b", *javaPackage("com.b")), ns("c")))
        assertEquals(mapOf("java_package" to str("com.b")), resolved.values)
        assertEquals(emptyList(), resolved.conflicts)
    }

    @Test
    fun `members declaring one value do not conflict`() {
        val resolved =
            ProtoFileOptions.of(
                listOf(ns("a", *javaPackage("com.x")), ns("b", *javaPackage("com.x")))
            )
        assertEquals(mapOf("java_package" to str("com.x")), resolved.values)
        assertEquals(emptyList(), resolved.conflicts)
    }

    @Test
    fun `the first member differing from the first declaring one is the only conflict`() {
        val a = ns("a")
        val b = ns("b", *javaPackage("X"))
        val c = ns("c", *javaPackage("X"))
        val d = ns("d", *javaPackage("Y"))
        val e = ns("e", *javaPackage("Z"))
        val resolved = ProtoFileOptions.of(listOf(a, b, c, d, e))
        assertEquals(mapOf("java_package" to str("X")), resolved.values)
        assertEquals(
            listOf(ProtoFileOptions.Conflict("java_package", b, str("X"), d, str("Y"))),
            resolved.conflicts,
        )
    }

    @Test
    fun `conflicts are listed in key order`() {
        val a = ns("a", "go_package" to str("p/a"), "java_package" to str("com.a"))
        val b = ns("b", "go_package" to str("p/b"), "java_package" to str("com.b"))
        assertEquals(
            listOf("java_package", "go_package"),
            ProtoFileOptions.of(listOf(a, b)).conflicts.map { it.key },
        )
    }

    @Test
    fun `package and name are not options`() {
        assertNull(ProtoFileOptions.key("package"))
        assertNull(ProtoFileOptions.key("name"))
        assertEquals(
            emptyMap(),
            ProtoFileOptions.declared(ns("a", "package" to str("x.v1"), "name" to str("N"))),
        )
    }
}

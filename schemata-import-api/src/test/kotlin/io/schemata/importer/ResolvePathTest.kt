package io.schemata.importer

import kotlin.test.Test
import kotlin.test.assertEquals

class ResolvePathTest {
    @Test
    fun `a sibling resolves against the directory of the base`() {
        assertEquals("a/b.xsd", resolvePath("a/main.xsd", "b.xsd"))
        assertEquals("b.xsd", resolvePath("main.xsd", "b.xsd"))
    }

    @Test
    fun `dot segments are dropped and dot dot takes back the segment before it`() {
        assertEquals("common/b.xsd", resolvePath("maindoc/a.xsd", "../common/b.xsd"))
        assertEquals("a/b.xsd", resolvePath("a/main.xsd", "./b.xsd"))
    }

    @Test
    fun `a dot dot with nothing left to take back is kept`() {
        assertEquals("../b.xsd", resolvePath("main.xsd", "../b.xsd"))
    }

    @Test
    fun `an absolute base stays absolute`() {
        assertEquals("/x/b/t.xsd", resolvePath("/x/a/main.xsd", "../b/t.xsd"))
    }

    @Test
    fun `backslashes in either argument come back as forward slashes`() {
        assertEquals(
            "C:/protos/a/people.proto",
            resolvePath("C:\\protos\\a\\orders.proto", "people.proto"),
        )
        assertEquals(
            "C:/protos/b/people.proto",
            resolvePath("C:\\protos\\a\\orders.proto", "../b/people.proto"),
        )
        assertEquals("C:/xsd/b/types.xsd", resolvePath("C:/xsd/a/main.xsd", "..\\b\\types.xsd"))
    }
}

package buildsrc.convention

import kotlin.test.Test
import kotlin.test.assertEquals

class GitVersionTest {
    @Test
    fun `exactly on a tag is the bare version`() {
        assertEquals("0.1.0", GitVersion.parse("v0.1.0-0-gabc1234", "abc1234"))
    }

    @Test
    fun `commits after a tag give a dev version with the sha`() {
        assertEquals("0.1.0-dev+abc1234", GitVersion.parse("v0.1.0-3-gabc1234", "abc1234"))
    }

    @Test
    fun `a dirty tree appends the suffix`() {
        assertEquals("0.1.0-dirty", GitVersion.parse("v0.1.0-0-gabc1234-dirty", "abc1234"))
        assertEquals("0.1.0-dev+abc1234-dirty", GitVersion.parse("v0.1.0-3-gabc1234-dirty", "abc1234"))
    }

    @Test
    fun `no tag falls back to the sha and no git to unknown`() {
        assertEquals("0.0.0-dev+abc1234", GitVersion.parse(null, "abc1234"))
        assertEquals("0.0.0-unknown", GitVersion.parse(null, null))
    }
}

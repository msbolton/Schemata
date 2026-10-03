package buildsrc.convention

import kotlin.test.Test
import kotlin.test.assertEquals

class GitVersionTest {
    @Test
    fun `exactly on a tag is the bare version`() {
        assertEquals("0.1.0", GitVersion.resolve("v0.1.0-0-gabc1234", "abc1234", false))
    }

    @Test
    fun `commits after a tag give a dev version with the sha`() {
        assertEquals("0.1.0-dev+abc1234", GitVersion.resolve("v0.1.0-3-gabc1234", "abc1234", false))
    }

    @Test
    fun `a dirty tree appends the suffix`() {
        assertEquals("0.1.0-dirty", GitVersion.resolve("v0.1.0-0-gabc1234", "abc1234", true))
        assertEquals(
            "0.1.0-dev+abc1234-dirty",
            GitVersion.resolve("v0.1.0-3-gabc1234", "abc1234", true),
        )
    }

    @Test
    fun `no tag falls back to the sha and no git to unknown`() {
        assertEquals("0.0.0-dev+abc1234", GitVersion.resolve(null, "abc1234", false))
        assertEquals("0.0.0-unknown", GitVersion.resolve(null, null, false))
    }

    @Test
    fun `a tag that does not parse as x y z falls back to the sha`() {
        assertEquals(
            "0.0.0-dev+abc1234",
            GitVersion.resolve("v1.2.3-rc1-2-gabc1234", "abc1234", false),
        )
        assertEquals("0.0.0-dev+abc1234-dirty", GitVersion.resolve("v1", "abc1234", true))
    }

    @Test
    fun `a release candidate tag is the version with its rc suffix`() {
        assertEquals("1.0.0-rc.1", GitVersion.resolve("v1.0.0-rc.1-0-gabc1234", "abc1234", false))
        assertEquals("1.0.0-rc.2-dev+abc1234", GitVersion.resolve("v1.0.0-rc.2-3-gabc1234", "abc1234", false))
        assertEquals("1.0.0-rc.1-dirty", GitVersion.resolve("v1.0.0-rc.1-0-gabc1234", "abc1234", true))
    }

    @Test
    fun `only the rc form of a prerelease is recognised`() {
        assertEquals("0.0.0-dev+abc1234", GitVersion.resolve("v1.0.0-beta.1-0-gabc1234", "abc1234", false))
        assertEquals("0.0.0-dev+abc1234", GitVersion.resolve("v1.0.0-rc-0-gabc1234", "abc1234", false))
    }

    @Test
    fun `dirty at zero distance from a tag still appends the suffix`() {
        assertEquals("0.1.0-dirty", GitVersion.resolve("v0.1.0-0-gabc1234", "abc1234", true))
    }

    @Test
    fun `no tag and no sha is unknown regardless of dirty`() {
        assertEquals("0.0.0-unknown", GitVersion.resolve(null, null, true))
    }
}

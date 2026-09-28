package buildsrc.convention

import java.io.File
import org.gradle.api.provider.ProviderFactory

/**
 * The build's version, read from git once at configuration time. Exactly on a `v*` tag the
 * version is the tag without its `v`; after a tag it is `<tag>-dev+<sha>`; with no tag it is
 * `0.0.0-dev+<sha>`; `-dirty` marks uncommitted changes; without git it is `0.0.0-unknown`.
 */
object GitVersion {
    private val describe = Regex("""^v(\d+\.\d+\.\d+)-(\d+)-g([0-9a-f]{7,})(-dirty)?$""")

    fun parse(describe: String?, fallbackSha: String?): String {
        val m = describe?.trim()?.let { this.describe.matchEntire(it) }
        if (m != null) {
            val (tag, distance, sha, dirty) = m.destructured
            val base = if (distance == "0") tag else "$tag-dev+${sha.take(7)}"
            return base + dirty
        }
        val sha = fallbackSha?.trim()?.takeIf { it.isNotEmpty() } ?: return "0.0.0-unknown"
        return "0.0.0-dev+${sha.take(7)}"
    }

    fun of(providers: ProviderFactory, root: File): String {
        val described = git(providers, root, "describe", "--tags", "--match", "v*", "--long", "--dirty")
        if (described != null) return parse(described, null)
        val sha = git(providers, root, "rev-parse", "--short=7", "HEAD") ?: return "0.0.0-unknown"
        val dirty = git(providers, root, "status", "--porcelain").orEmpty().isNotBlank()
        return parse(null, sha) + if (dirty) "-dirty" else ""
    }

    private fun git(providers: ProviderFactory, root: File, vararg args: String): String? {
        val result =
            providers.exec {
                workingDir = root
                commandLine("git", *args)
                isIgnoreExitValue = true
            }
        return runCatching {
            if (result.result.get().exitValue == 0) result.standardOutput.asText.get().trim() else null
        }.getOrNull()
    }
}

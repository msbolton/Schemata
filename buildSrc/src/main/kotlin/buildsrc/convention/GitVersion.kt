package buildsrc.convention

import java.io.File
import org.gradle.api.provider.ProviderFactory

/**
 * The build's version, read from git once at configuration time. Exactly on a `vX.Y.Z` tag the
 * version is the tag without its `v`; a `vX.Y.Z-rc.N` tag gives `X.Y.Z-rc.N`, the one prerelease
 * form the project uses; after such a tag it is `<tag>-dev+<sha>`; with no matching
 * tag (including a tag that doesn't parse as `vX.Y.Z`) it is `0.0.0-dev+<sha>`; `-dirty` marks a
 * working tree with tracked changes; without git at all it is `0.0.0-unknown`.
 */
object GitVersion {
    private val describeRegex = Regex("""^v(\d+\.\d+\.\d+(?:-rc\.\d+)?)-(\d+)-g([0-9a-f]{7,})$""")

    fun resolve(described: String?, sha: String?, dirty: Boolean): String {
        val base = resolveBase(described, sha)
        return if (dirty && base != "0.0.0-unknown") "$base-dirty" else base
    }

    private fun resolveBase(described: String?, sha: String?): String {
        val m = described?.trim()?.let { describeRegex.matchEntire(it) }
        if (m != null) {
            val (tag, distance, matchedSha) = m.destructured
            return if (distance == "0") tag else "$tag-dev+${matchedSha.take(7)}"
        }
        val trimmedSha = sha?.trim()?.takeIf { it.isNotEmpty() } ?: return "0.0.0-unknown"
        return "0.0.0-dev+${trimmedSha.take(7)}"
    }

    fun of(providers: ProviderFactory, root: File): String {
        val described = git(providers, root, "describe", "--tags", "--match", "v[0-9]*", "--long")
        val sha = git(providers, root, "rev-parse", "--short=7", "HEAD")
        val dirty = git(providers, root, "status", "--porcelain", "--untracked-files=no").orEmpty().isNotBlank()
        return resolve(described, sha, dirty)
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

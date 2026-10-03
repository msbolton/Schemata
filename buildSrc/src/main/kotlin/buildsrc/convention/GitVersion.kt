package buildsrc.convention

import java.io.File
import org.gradle.api.provider.ProviderFactory

/**
 * The build's version, read from git once at configuration time. Exactly on a `vX.Y.Z` tag the
 * version is the tag without its `v`; a `vX.Y.Z-rc.N` tag gives `X.Y.Z-rc.N`, the one prerelease
 * form the project uses; after such a tag it is `<tag>-dev+<sha>`; with no matching
 * tag (including a tag that doesn't parse as `vX.Y.Z`) it is `0.0.0-dev+<sha>`; `-dirty` marks a
 * working tree with tracked changes; without git at all it is `0.0.0-unknown`. When a release
 * workflow runs for a pushed tag, that tag is described first, so it wins over another tag on the
 * same commit.
 */
object GitVersion {
    private const val VERSION_TAGS = "v[0-9]*"

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

    /**
     * The `--match` patterns `git describe` tries in order: on a tag push ([refType] `tag`) the
     * pushed tag [refName] first, then every version tag.
     */
    fun matchPatterns(refType: String?, refName: String?): List<String> =
        if (refType == "tag" && !refName.isNullOrBlank()) listOf(refName, VERSION_TAGS)
        else listOf(VERSION_TAGS)

    fun of(providers: ProviderFactory, root: File): String {
        val patterns =
            matchPatterns(
                providers.environmentVariable("GITHUB_REF_TYPE").orNull,
                providers.environmentVariable("GITHUB_REF_NAME").orNull,
            )
        val described =
            patterns.firstNotNullOfOrNull {
                git(providers, root, "describe", "--tags", "--match", it, "--long")
            }
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

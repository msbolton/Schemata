package io.schemata.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import io.schemata.lang.upgrade.Upgrader

/**
 * `schemata upgrade`: rewrites 1.x files in the 2.0 surface, as `fmt` rewrites layout. A file that
 * already reads as 2.0 is left as it is and not named.
 */
class UpgradeCommand : CliktCommand(name = "upgrade") {
    override fun help(context: Context) =
        "Rewrite 1.x schema files in the 2.0 surface, or check that none still needs it."

    private val check by
        option(
                "--check",
                help =
                    "Write nothing; print a diff for each file that would change and exit 1 if any would",
            )
            .flag()
    private val reporting by FormatOptions()
    private val inputs by argument("PATHS").path(mustExist = true).multiple(required = true)

    override fun run() =
        rewriteInPlace(inputs, check, reporting, "upgraded") { content, path ->
            Upgrader.upgrade(content, path)
        }
}

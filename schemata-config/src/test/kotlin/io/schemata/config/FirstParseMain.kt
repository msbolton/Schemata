package io.schemata.config

import io.schemata.core.annotations.AnnotationRegistry
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * Loads a minimal `schemata.toml` from the directory given as the only argument and exits 0 when it
 * loads. [ConfigLoaderTest] runs this in a fresh JVM so the load is the first tomlj parse there.
 */
object FirstParseMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val file = Path.of(args.single()).resolve(ConfigLoader.FILE_NAME)
        Files.writeString(file, "[compile]\nstrict = true\n")
        val result = ConfigLoader.load(file, AnnotationRegistry(emptyList()), emptyList())
        exitProcess(if (result.config != null) 0 else 1)
    }
}

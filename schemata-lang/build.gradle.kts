plugins {
    id("buildsrc.convention.kotlin-jvm")
    antlr
}

dependencies {
    antlr(libs.antlrTool)
    implementation(libs.antlrRuntime)
    testImplementation(project(":schemata-testkit"))
}

// The antlr plugin makes `api` extend `antlr`, which would leak the whole ANTLR tool into
// consumers' classpaths. Only the runtime belongs there.
configurations.api { setExtendsFrom(extendsFrom.filterNot { it.name == "antlr" }) }

tasks.generateGrammarSource {
    arguments = arguments + listOf("-no-listener", "-no-visitor", "-package", "io.schemata.lang.antlr")
    outputDirectory = layout.buildDirectory.dir("generated-src/antlr/main/io/schemata/lang/antlr").get().asFile
}

tasks.compileKotlin { dependsOn(tasks.generateGrammarSource) }
tasks.compileTestKotlin { dependsOn(tasks.generateTestGrammarSource) }

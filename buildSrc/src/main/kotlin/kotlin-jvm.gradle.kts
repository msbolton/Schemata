package buildsrc.convention

import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    kotlin("jvm")
    `java-library`
    id("com.diffplug.spotless")
}

version = buildsrc.convention.GitVersion.of(providers, rootDir)

kotlin { jvmToolchain(21) }

dependencies { testImplementation(kotlin("test")) }

spotless {
    kotlin {
        target("src/**/*.kt")
        ktfmt("0.53").kotlinlangStyle()
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktfmt("0.53").kotlinlangStyle()
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging { events(TestLogEvent.FAILED, TestLogEvent.PASSED, TestLogEvent.SKIPPED) }
}

// Dependency direction: a module may only depend on modules in a strictly lower layer.
// lang(0) -> core(1) -> target-api(2) -> import-api(3) ->
// proto/sql/xsd/jsonschema/import-xsd/import-proto/import-sql/evolution/lsp(4) -> cli(5).
// testkit is outside the layering.
val layers =
    mapOf(
        "schemata-lang" to 0,
        "schemata-core" to 1,
        "schemata-target-api" to 2,
        "schemata-import-api" to 3,
        "schemata-target-proto" to 4,
        "schemata-target-sql" to 4,
        "schemata-target-xsd" to 4,
        "schemata-target-jsonschema" to 4,
        "schemata-import-xsd" to 4,
        "schemata-import-proto" to 4,
        "schemata-import-sql" to 4,
        "schemata-evolution" to 4,
        "schemata-lsp" to 4,
        "schemata-cli" to 5,
    )

layers[project.name]?.let { myLayer ->
    val moduleName = project.name
    // Captured as a plain Map so the doLast closure below closes over data, not the script
    // object, which the configuration cache requires.
    val layersForTask = layers
    val projectDeps =
        provider {
            listOf("api", "implementation", "compileOnly", "runtimeOnly").flatMap { name ->
                configurations.findByName(name)?.dependencies?.withType(ProjectDependency::class.java)?.map { it.name }
                    ?: emptyList()
            }
        }
    val checkModuleDependencies =
        tasks.register("checkModuleDependencies") {
            group = "verification"
            description = "Fails if this module depends on a module in the same or a higher layer."
            doLast {
                val violations =
                    projectDeps.get().filter { dep ->
                        val depLayer =
                            layersForTask[dep]
                                ?: throw GradleException(
                                    "$dep is not in the layer map in kotlin-jvm.gradle.kts; add it"
                                )
                        depLayer >= myLayer
                    }
                if (violations.isNotEmpty()) {
                    throw GradleException(
                        "$moduleName (layer $myLayer) depends on ${violations.joinToString()}, which is not strictly below it. " +
                            "Dependencies must point downward: lang -> core -> target-api -> import-api -> targets and importers -> cli."
                    )
                }
            }
        }
    tasks.named("check") { dependsOn(checkModuleDependencies) }
}

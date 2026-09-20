package buildsrc.convention

import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    kotlin("jvm")
    `java-library`
    id("com.diffplug.spotless")
}

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
// lang(0) -> core(1) -> target-api(2) -> proto/sql(3) -> cli(4). testkit is outside the layering.
val layers =
    mapOf(
        "schemata-lang" to 0,
        "schemata-core" to 1,
        "schemata-target-api" to 2,
        "schemata-target-proto" to 3,
        "schemata-target-sql" to 3,
        "schemata-cli" to 4,
    )

layers[project.name]?.let { myLayer ->
    val moduleName = project.name
    val layersForTask = layers
    val projectDeps =
        provider {
            listOf("api", "implementation").flatMap { name ->
                configurations.findByName(name)?.dependencies?.withType(ProjectDependency::class.java)?.map { it.name }
                    ?: emptyList()
            }
        }
    val checkModuleDependencies =
        tasks.register("checkModuleDependencies") {
            group = "verification"
            description = "Fails if this module depends on a module in the same or a higher layer."
            doLast {
                val violations = projectDeps.get().filter { dep -> (layersForTask[dep] ?: -1) >= myLayer }
                if (violations.isNotEmpty()) {
                    throw GradleException(
                        "$moduleName (layer $myLayer) depends on ${violations.joinToString()}, which is not strictly below it. " +
                            "Dependencies must point downward: lang -> core -> target-api -> targets -> cli."
                    )
                }
            }
        }
    tasks.named("check") { dependsOn(checkModuleDependencies) }
}

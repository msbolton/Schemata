plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("buildsrc.convention.protoc-tests")
    application
    alias(libs.plugins.shadow)
    alias(libs.plugins.graalvmNative)
}

dependencies {
    implementation(project(":schemata-lang"))
    implementation(project(":schemata-core"))
    implementation(project(":schemata-target-api"))
    implementation(project(":schemata-target-proto"))
    implementation(project(":schemata-target-sql"))
    implementation(project(":schemata-target-xsd"))
    implementation(libs.clikt)
    testImplementation(project(":schemata-testkit"))
    testImplementation(libs.testcontainersPostgres)
    testImplementation(libs.postgresJdbc)
}

application {
    mainClass = "io.schemata.cli.MainKt"
    applicationName = "schemata"
}

// The CLI reports its version from a constant compiled into it, so a native image
// (which carries no jar manifest) prints the same value as the jar.
val generateBuildVersion by
    tasks.registering {
        val version = project.version.toString()
        val outDir = layout.buildDirectory.dir("generated/version")
        inputs.property("version", version)
        outputs.dir(outDir)
        doLast {
            val file = outDir.get().file("io/schemata/cli/BuildVersion.kt").asFile
            file.parentFile.mkdirs()
            file.writeText(
                """
            |package io.schemata.cli
            |
            |/** The build's version, written by the build script. */
            |object BuildVersion {
            |    const val VERSION: String = "$version"
            |}
            |"""
                    .trimMargin()
            )
        }
    }

kotlin.sourceSets.named("main") { kotlin.srcDir(generateBuildVersion) }

tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to "schemata",
            "Implementation-Version" to project.version,
        )
    }
}

tasks.shadowJar {
    archiveBaseName = "schemata"
    archiveClassifier = ""
    mergeServiceFiles()
}

graalvmNative {
    // The task uses the native-image of the JDK on the path; nothing else in the
    // build needs GraalVM, so a plain JDK still builds everything but the binary.
    toolchainDetection = false
    binaries {
        named("main") {
            imageName = "schemata"
            mainClass = "io.schemata.cli.MainKt"
            buildArgs.add("--no-fallback")
        }
    }
}

// The archive path is fixed by configuration, not execution, so reading it here is safe.
val fatJar = tasks.shadowJar.get().archiveFile

tasks.test {
    inputs.file(fatJar)
    systemProperty("schemata.fatJar", fatJar.get().asFile.absolutePath)
    systemProperty("schemata.version", project.version.toString())
    providers.gradleProperty("schemata.nativeBinary").orNull?.let {
        inputs.file(it)
        systemProperty("schemata.nativeBinary", File(it).absolutePath)
    }
}

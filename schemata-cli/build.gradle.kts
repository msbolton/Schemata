import org.gradle.api.tasks.PathSensitivity

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
    implementation(project(":schemata-target-jsonschema"))
    implementation(project(":schemata-import-xsd"))
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
    // No native tests exist; this drops the nativeTest tasks and the
    // junit-platform-native test dependency the plugin otherwise adds.
    testSupport = false
    binaries {
        named("main") {
            imageName = "schemata"
            // The plugin otherwise defaults this project to a shared library.
            sharedLibrary = false
            mainClass = "io.schemata.cli.MainKt"
            buildArgs.add("--no-fallback")
            if (System.getProperty("os.name").lowercase().contains("mac")) {
                // native-image links with the host's SDK version unless told otherwise.
                buildArgs.add("-H:NativeLinkerOption=-mmacosx-version-min=12.0")
            }
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
        // Resolved against the repository root, so the workflow and a developer
        // running Gradle from the root both pass the same path.
        val binary = rootProject.layout.projectDirectory.file(it).asFile.absoluteFile
        inputs.file(binary)
        systemProperty("schemata.nativeBinary", binary.path)
        inputs
            .dir(rootProject.layout.projectDirectory.dir("examples"))
            .withPathSensitivity(PathSensitivity.RELATIVE)
    }
}

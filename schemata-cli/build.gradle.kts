import java.net.URI
import java.security.MessageDigest
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
    implementation(project(":schemata-target-openapi"))
    implementation(project(":schemata-import-api"))
    implementation(project(":schemata-import-xsd"))
    implementation(project(":schemata-import-proto"))
    implementation(project(":schemata-import-sql"))
    implementation(project(":schemata-evolution"))
    implementation(project(":schemata-migrate"))
    implementation(project(":schemata-lsp"))
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

// The released 1.4.0 jar, which the upgrade equivalence test compiles every 1.x case with. It is
// pinned by its digest; when it cannot be fetched (an offline build) the test skips, so the task
// warns instead of failing. A digest mismatch always fails.
val v1Jar = layout.buildDirectory.file("v1/schemata-1.4.0.jar")
val downloadV1Jar by
    tasks.registering {
        val url = "https://github.com/msbolton/Schemata/releases/download/v1.4.0/schemata-1.4.0.jar"
        val sha256 = "8d7b05ba29eb0ea4386f7f47b753544d83667cf6dfd54f13e7742648320d8c5e"
        val target = v1Jar
        outputs.file(target)
        doLast {
            val file = target.get().asFile
            fun digest(f: File) =
                MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") {
                    "%02x".format(it)
                }
            if (file.isFile && digest(file) == sha256) return@doLast
            file.parentFile.mkdirs()
            val partial = File(file.parentFile, file.name + ".part")
            try {
                URI(url).toURL().openStream().use { input ->
                    partial.outputStream().use { input.copyTo(it) }
                }
            } catch (e: java.io.IOException) {
                partial.delete()
                logger.warn("cannot fetch $url (${e.message}); the equivalence test will skip")
                return@doLast
            }
            val actual = digest(partial)
            if (actual != sha256) {
                partial.delete()
                throw GradleException("$url has sha256 $actual, expected $sha256")
            }
            if (!partial.renameTo(file)) throw GradleException("cannot move $partial to $file")
        }
    }

// The archive path is fixed by configuration, not execution, so reading it here is safe.
val fatJar = tasks.shadowJar.get().archiveFile

tasks.test {
    dependsOn(downloadV1Jar)
    inputs.file(fatJar)
    // Absent offline; a file collection may name a missing file.
    inputs.files(v1Jar).withPropertyName("v1Jar").withPathSensitivity(PathSensitivity.NONE)
    systemProperty("schemata.v1Jar", v1Jar.get().asFile.absolutePath)
    // The guide, README, and example tests read these from the repository root, outside the
    // module's own sources, so a change to them alone must still rerun the tests.
    inputs
        .dir(rootProject.layout.projectDirectory.dir("guide"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs
        .file(rootProject.layout.projectDirectory.file("README.md"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs
        .dir(rootProject.layout.projectDirectory.dir("examples"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("schemata.fatJar", fatJar.get().asFile.absolutePath)
    systemProperty("schemata.version", project.version.toString())
    providers.gradleProperty("schemata.nativeBinary").orNull?.let {
        // Resolved against the repository root, so the workflow and a developer
        // running Gradle from the root both pass the same path.
        val binary = rootProject.layout.projectDirectory.file(it).asFile.absoluteFile
        inputs.file(binary)
        systemProperty("schemata.nativeBinary", binary.path)
    }
}

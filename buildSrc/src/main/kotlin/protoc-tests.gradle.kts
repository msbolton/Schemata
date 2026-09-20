package buildsrc.convention

import org.gradle.api.artifacts.VersionCatalogsExtension

// Resolves a protoc binary for the host OS from Maven Central and hands its path to tests as
// the system property `schemata.protoc`. Hermetic: no PATH dependency in CI.

val protocBinary: Configuration by
    configurations.creating {
        isCanBeConsumed = false
        isCanBeResolved = true
    }

val osName = System.getProperty("os.name").lowercase()
val osArch = System.getProperty("os.arch").lowercase()
val protocClassifier =
    when {
        "mac" in osName -> if (osArch == "aarch64") "osx-aarch_64" else "osx-x86_64"
        "linux" in osName -> if (osArch == "aarch64") "linux-aarch_64" else "linux-x86_64"
        else -> throw GradleException("No protoc binary is published for $osName/$osArch")
    }

val protobufVersion =
    the<VersionCatalogsExtension>().named("libs").findVersion("protobuf").get().requiredVersion

dependencies { protocBinary("com.google.protobuf:protoc:$protobufVersion:$protocClassifier@exe") }

val installProtoc by
    tasks.registering(Copy::class) {
        from(protocBinary) { rename { "protoc" } }
        into(layout.buildDirectory.dir("protoc"))
        filePermissions { unix("rwxr-xr-x") }
    }

tasks.withType<Test>().configureEach {
    dependsOn(installProtoc)
    val protocPath = layout.buildDirectory.file("protoc/protoc").map { it.asFile.absolutePath }
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Dschemata.protoc=${protocPath.get()}") })
}

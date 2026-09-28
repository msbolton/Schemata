plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("buildsrc.convention.protoc-tests")
    application
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(project(":schemata-lang"))
    implementation(project(":schemata-core"))
    implementation(project(":schemata-target-api"))
    implementation(project(":schemata-target-proto"))
    implementation(project(":schemata-target-sql"))
    implementation(libs.clikt)
    testImplementation(project(":schemata-testkit"))
    testImplementation(libs.testcontainersPostgres)
    testImplementation(libs.postgresJdbc)
}

application {
    mainClass = "io.schemata.cli.MainKt"
    applicationName = "schemata"
}

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

// The archive path is fixed by configuration, not execution, so reading it here is safe.
val fatJar = tasks.shadowJar.get().archiveFile

tasks.test {
    inputs.file(fatJar)
    systemProperty("schemata.fatJar", fatJar.get().asFile.absolutePath)
}

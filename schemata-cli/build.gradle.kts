plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("buildsrc.convention.protoc-tests")
    application
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

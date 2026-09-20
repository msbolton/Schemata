plugins {
    id("buildsrc.convention.kotlin-jvm")
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
}

application {
    mainClass = "io.schemata.cli.MainKt"
    applicationName = "schemata"
}

plugins { id("buildsrc.convention.kotlin-jvm") }

dependencies {
    api(project(":schemata-import-api"))
    implementation(project(":schemata-target-api"))
    implementation(libs.swaggerParser)
    // swagger-parser logs through SLF4J, which prints a warning on stderr the first time a logger
    // is made when no provider is on the classpath; the CLI's stderr carries only its report.
    runtimeOnly(libs.slf4jNop)
    testImplementation(project(":schemata-testkit"))
}

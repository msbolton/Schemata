plugins { id("buildsrc.convention.kotlin-jvm") }

dependencies {
    api(project(":schemata-target-api"))
    testImplementation(project(":schemata-testkit"))
}

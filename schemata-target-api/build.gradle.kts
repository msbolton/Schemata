plugins { id("buildsrc.convention.kotlin-jvm") }

dependencies {
    api(project(":schemata-core"))
    testImplementation(project(":schemata-testkit"))
}

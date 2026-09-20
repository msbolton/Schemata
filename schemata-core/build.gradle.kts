plugins { id("buildsrc.convention.kotlin-jvm") }

dependencies {
    api(project(":schemata-lang"))
    testImplementation(project(":schemata-testkit"))
}

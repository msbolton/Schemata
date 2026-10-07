plugins { id("buildsrc.convention.kotlin-jvm") }

dependencies {
    api(project(":schemata-target-sql"))
    api(project(":schemata-evolution"))
    testImplementation(project(":schemata-testkit"))
}

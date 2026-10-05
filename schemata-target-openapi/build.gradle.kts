plugins { id("buildsrc.convention.kotlin-jvm") }

dependencies {
    api(project(":schemata-target-api"))
    implementation(project(":schemata-target-jsonschema"))
    testImplementation(project(":schemata-core"))
    testImplementation(project(":schemata-testkit"))
}

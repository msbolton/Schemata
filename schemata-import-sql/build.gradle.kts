plugins { id("buildsrc.convention.kotlin-jvm") }

dependencies {
    api(project(":schemata-import-api"))
    implementation(project(":schemata-target-api"))
    testImplementation(project(":schemata-testkit"))
}

plugins { id("buildsrc.convention.kotlin-jvm") }

dependencies {
    api(project(":schemata-core"))
    implementation(project(":schemata-target-api"))
}

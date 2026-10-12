plugins { id("buildsrc.convention.kotlin-jvm") }

dependencies {
    api(project(":schemata-lang"))
    api(project(":schemata-core"))
    implementation(project(":schemata-target-api"))
    implementation(libs.tomlj)
    testImplementation(kotlin("test"))
    testImplementation(project(":schemata-target-proto"))
    testImplementation(project(":schemata-target-sql"))
    testImplementation(project(":schemata-target-xsd"))
    testImplementation(project(":schemata-target-jsonschema"))
    testImplementation(project(":schemata-target-openapi"))
}

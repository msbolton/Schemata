plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("buildsrc.convention.protoc-tests")
}

dependencies {
    api(project(":schemata-target-api"))
    testImplementation(project(":schemata-testkit"))
}

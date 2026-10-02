plugins { id("buildsrc.convention.kotlin-jvm") }

dependencies {
    api(project(":schemata-core"))
    api(libs.lsp4j)
}

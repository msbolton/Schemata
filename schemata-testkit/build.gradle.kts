plugins { id("buildsrc.convention.kotlin-jvm") }

dependencies {
    implementation(kotlin("test"))
    api(libs.lsp4j)
    implementation(libs.testcontainersPostgres)
    implementation(libs.postgresJdbc)
    implementation(libs.jsonSchemaValidator)
}

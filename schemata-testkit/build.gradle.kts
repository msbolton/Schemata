plugins { id("buildsrc.convention.kotlin-jvm") }

dependencies {
    implementation(kotlin("test"))
    implementation(libs.testcontainersPostgres)
    implementation(libs.postgresJdbc)
}

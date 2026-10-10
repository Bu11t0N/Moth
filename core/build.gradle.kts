plugins { kotlin("jvm") }
kotlin { jvmToolchain(21) }
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach { kotlinOptions.jvmTarget = "17" }
tasks.withType<JavaCompile>().configureEach { sourceCompatibility = "17"; targetCompatibility = "17" }
dependencies {
    implementation("org.json:json:20240303")
    implementation("org.yaml:snakeyaml:2.2")
    testImplementation(kotlin("test-junit"))
}

plugins { kotlin("jvm") }
kotlin { jvmToolchain(21) }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
tasks.withType<JavaCompile>().configureEach { sourceCompatibility = "17"; targetCompatibility = "17" }
dependencies {
    implementation("org.json:json:20260814")
    implementation("org.yaml:snakeyaml:2.7")
    testImplementation(kotlin("test-junit"))
}

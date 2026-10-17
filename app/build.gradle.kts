plugins { id("com.android.application"); kotlin("android"); kotlin("plugin.compose") }
val versionSuffix = (project.findProperty("versionSuffix") as String?)?.trim().orEmpty()
require(versionSuffix.matches(Regex("[A-Za-z0-9.-]*"))) { "versionSuffix: letters, digits, . and - only" }
android {
    namespace = "org.mothvpn.android"
    compileSdk = 36
    defaultConfig { applicationId = "org.mothvpn.android"; minSdk = 26; targetSdk = 34; versionCode = 53; versionName = "0.6.5$versionSuffix"; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
        // versionCode: every build that can be offered as an update must exceed the previous one (0.4.7 beta.1 = 17, beta.2 = 18, beta.3 = 19, beta.4 = 20, beta.5 = 21, beta.6 = 22, stable 0.4.7 = 23, 0.5.0 beta.1 = 24, beta.2 = 25, beta.3 = 26, beta.4 = 27, beta.5 = 28, stable 0.5.0 = 29, 0.5.1 beta.1 = 30, 0.5.1 beta.2 = 31, beta.3 = 32, beta.4 = 33, stable 0.5.1 = 34, 0.5.2 (licenses) = 35, 0.5.2 beta.1 = 36, stable 0.5.2 = 37, 0.5.3 beta.1 = 38, 0.5.3 beta.2 = 39, stable 0.5.3 = 40, 0.5.4 beta.1 = 41, beta.2 = 42, 0.6.0 beta.1 = 43, beta.2 = 44, stable 0.6.0 = 45, 0.6.1 beta.1 = 46, beta.2 = 47, stable 0.6.1 = 48, 0.6.2 beta.1 = 49, stable 0.6.2 = 50, stable 0.6.3 = 51, stable 0.6.4 = 52, stable 0.6.5 = 53).
        // 0.6.4 = 52. Test builds: -PversionSuffix=-beta.1 gives versionName 0.6.5-beta.1 ; no property gives 0.6.5.
    buildTypes {
        getByName("release") {
            signingConfig = if (providers.gradleProperty("unsignedValidation").orNull == "true") null else signingConfigs.create("mothRelease") {
                storeFile = file(System.getenv("MOTH_KEYSTORE_FILE") ?: "../moth-release.p12")
                storePassword = System.getenv("MOTH_KEYSTORE_PASSWORD")
                keyAlias = "moth"
                keyPassword = System.getenv("MOTH_KEYSTORE_PASSWORD")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    splits { abi { isEnable = true; reset(); include("arm64-v8a", "armeabi-v7a", "x86_64", "x86"); isUniversalApk = false } }
    bundle { language { enableSplit = false } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { compose = true; buildConfig = true }
    sourceSets.getByName("main").assets.srcDir("../licenses")
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"; jniLibs.useLegacyPackaging = true }
}
dependencies {
    implementation(project(":core")) { exclude(group = "org.json", module = "json") }
    implementation(files("../libbox.aar"))
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0") { isTransitive = false }
    implementation("com.google.zxing:core:3.5.4")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.4.0")
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

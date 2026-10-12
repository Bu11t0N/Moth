plugins { id("com.android.application"); kotlin("android") }
val versionSuffix = (project.findProperty("versionSuffix") as String?)?.trim().orEmpty()
require(versionSuffix.matches(Regex("[A-Za-z0-9.-]*"))) { "versionSuffix: letters, digits, . and - only" }
android {
    namespace = "org.mothvpn.android"
    compileSdk = 34
    defaultConfig { applicationId = "org.mothvpn.android"; minSdk = 26; targetSdk = 34; versionCode = 27; versionName = "0.5.0$versionSuffix" }
        // versionCode: every build that can be offered as an update must exceed the previous one (0.4.7 beta.1 = 17, beta.2 = 18, beta.3 = 19, beta.4 = 20, beta.5 = 21, beta.6 = 22, stable 0.4.7 = 23, 0.5.0 beta.1 = 24, beta.2 = 25, beta.3 = 26, beta.4 = 27).
        // Test builds: -PversionSuffix=-beta.1 gives versionName 0.5.0-beta.1 ; no property gives 0.5.0.
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
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    sourceSets.getByName("main").assets.srcDir("../licenses")
    composeOptions { kotlinCompilerExtensionVersion = "1.5.8" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"; jniLibs.useLegacyPackaging = true }
}
dependencies {
    implementation(project(":core")) { exclude(group = "org.json", module = "json") }
    implementation(files("../libbox.aar"))
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.compose.material3:material3:1.2.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0") { isTransitive = false }
    implementation("com.google.zxing:core:3.5.3")
}


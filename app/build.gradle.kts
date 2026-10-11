plugins { id("com.android.application"); kotlin("android") }
val versionSuffix = (project.findProperty("versionSuffix") as String?)?.trim().orEmpty()
require(versionSuffix.matches(Regex("[A-Za-z0-9.-]*"))) { "versionSuffix: letters, digits, . and - only" }
android {
    namespace = "org.mothvpn.android"
    compileSdk = 34
    defaultConfig { applicationId = "org.mothvpn.android"; minSdk = 26; targetSdk = 34; versionCode = 16; versionName = "0.4.6$versionSuffix" }
        // versionCode: every build that can be offered as an update must exceed the previous one (beta.1 = 11, beta.2 = 12, beta.3 = 13, beta.4 = 14, beta.5 = 15), so the stable 0.4.6 needs 16 or more.
        // Test builds: -PversionSuffix=-beta.1 gives versionName 0.4.6-beta.1 ; no property gives 0.4.6.
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
}


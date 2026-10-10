plugins { id("com.android.application"); kotlin("android") }
android {
    namespace = "org.routevpn.android"
    compileSdk = 34
    defaultConfig { applicationId = "org.routevpn.android"; minSdk = 26; targetSdk = 34; versionCode = 3; versionName = "0.2.0-alpha.1" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    sourceSets.getByName("main").assets.srcDir("../licenses")
    composeOptions { kotlinCompilerExtensionVersion = "1.5.8" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
dependencies {
    implementation(project(":core")) { exclude(group = "org.json", module = "json") }
    implementation(files("../libbox.aar"))
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.compose.material3:material3:1.2.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}


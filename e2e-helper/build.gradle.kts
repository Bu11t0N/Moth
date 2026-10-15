// E2E only (CI emulator): tiny app that Moth's split tunnel excludes; it reports whether it can reach the test host
// directly and whether a socket bound to the VPN network / tun0 address is rejected. Never shipped.
plugins { id("com.android.application") }
android {
    namespace = "org.mothvpn.e2ehelper"
    compileSdk = 34
    defaultConfig { applicationId = "org.mothvpn.e2ehelper"; minSdk = 26; targetSdk = 34; versionCode = 1; versionName = "1" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}

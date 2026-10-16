plugins { id("com.android.application") }

android {
    namespace = "uk.snetwork.thorvault"
    compileSdk = 36
    defaultConfig {
        applicationId = "uk.snetwork.thorvault"
        minSdk = 26
        targetSdk = 36
        versionCode = 1224
        versionName = "0.12.24-alpha"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.activity:activity:1.13.0")
    implementation("androidx.webkit:webkit:1.16.0")
    implementation("androidx.work:work-runtime:2.11.2")
    implementation("androidx.documentfile:documentfile:1.1.0")
}

plugins { id("com.android.application") }

android {
    namespace = "com.trilingo.interpreter"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.trilingo.interpreter"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "0.7.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.google.mlkit:language-id:17.0.6")
    implementation("com.google.mlkit:translate:17.0.3")
}

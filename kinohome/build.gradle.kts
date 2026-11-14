plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.middlepoint.kinohome"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.middlepoint.kinohome"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

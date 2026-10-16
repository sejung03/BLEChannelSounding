plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.blecsranger"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.blecsranger"
        minSdk = 36
        targetSdk = 36
        versionCode = 9
        versionName = "1.3.5"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

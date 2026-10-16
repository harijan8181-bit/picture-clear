plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.myapplication009"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.myapplication009"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("com.google.android.gms:play-services-ads:23.3.0")
    implementation(libs.mlkit.face.detection)
    implementation("androidx.exifinterface:exifinterface:1.3.7")
}

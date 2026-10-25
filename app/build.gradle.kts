plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.geocam.app"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.geocam.app"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    val c = "1.4.0"
    implementation("androidx.camera:camera-core:$c")
    implementation("androidx.camera:camera-camera2:$c")
    implementation("androidx.camera:camera-lifecycle:$c")
    implementation("androidx.camera:camera-view:$c")
    implementation("androidx.camera:camera-extensions:$c")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.core:core-ktx:1.13.1")
}

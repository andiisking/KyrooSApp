plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.kyroos.app"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.kyroos.app"
        minSdk = 26
        targetSdk = 34
    }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.1" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation(platform("androidx.compose:compose-bom:2023.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.bouncycastle:bcpkix-jdk15on:1.70")
    implementation("org.conscrypt:conscrypt-android:2.5.2")
    implementation("dev.rikka.rikkax.core:core:1.4.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
}

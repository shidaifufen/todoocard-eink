plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.shidaifufen.todocard"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.shidaifufen.todocard"
        minSdk = 26
        targetSdk = 35
        versionCode = 11
        versionName = "1.7.0"
    }
    signingConfigs {
        create("stable") {
            storeFile = System.getenv("ANDROID_KEYSTORE_PATH")?.let { file(it) }
            storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
            keyAlias = "todocard"
            keyPassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
            storeType = "PKCS12"
        }
    }
    // Share the online scene assets with the desktop browser.
    sourceSets["main"].assets.srcDir("../../web")
    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("stable")
        }
        getByName("debug") {
            if (!System.getenv("ANDROID_KEYSTORE_PATH").isNullOrBlank())
                signingConfig = signingConfigs.getByName("stable")
        }
    }
    buildFeatures { compose = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}

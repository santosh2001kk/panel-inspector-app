plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace  = "com.example.testerapigoogle"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.testerapigoogle"
        minSdk        = 24
        targetSdk     = 35
        versionCode   = 1
        versionName   = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions { jvmTarget = "11" }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.runtime)

    // CameraX
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // OkHttp for API calls
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // EXIF rotation fix
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // Glide for result image
    implementation("com.github.bumptech.glide:glide:4.16.0")

    // Gson
    implementation("com.google.code.gson:gson:2.10.1")

    // ViewPager2 for photo guide onboarding
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation(libs.androidx.recyclerview)

    // ARCore for panel verification
    implementation("com.google.ar:core:1.44.0")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
}
android {
  namespace = "app.yoinkpocket.local"
  compileSdk = 35
  defaultConfig {
    applicationId = "app.yoinkpocket.local"
    minSdk = 29
    targetSdk = 35
    versionCode = 2
    versionName = "1.0.1"
    ndk { abiFilters += "arm64-v8a" }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  kotlinOptions { jvmTarget = "17" }
  packaging { jniLibs.useLegacyPackaging = true }
}
dependencies {
  implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
  implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
}

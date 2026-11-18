plugins {
 id("com.android.application")
 id("org.jetbrains.kotlin.android")
 id("org.jetbrains.kotlin.plugin.compose")
}
android {
 namespace = "com.sawt.ai"
 compileSdk = 35
 defaultConfig {
  applicationId = "com.sawt.ai"
  minSdk = 26
  targetSdk = 35
  versionCode = 10
  versionName = "1.5.2"
  ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
 }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
 buildFeatures { compose = true; buildConfig = true }
 buildTypes {
  getByName("debug") {
   val apiUrl = providers.gradleProperty("SAWT_API_BASE_URL").orElse("").get()
   buildConfigField("String", "SAWT_API_BASE_URL", "\"$apiUrl\"")
  }
  getByName("release") {
   val apiUrl = providers.gradleProperty("SAWT_API_BASE_URL").orElse("").get()
   buildConfigField("String", "SAWT_API_BASE_URL", "\"$apiUrl\"")
  }
 }
 packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
dependencies {
 implementation(files("libs/sherpa-onnx-2.0.1.aar"))
 implementation("org.apache.commons:commons-compress:1.27.1")
 implementation("commons-io:commons-io:2.18.0")
 implementation(platform("androidx.compose:compose-bom:2024.12.01"))
 implementation("androidx.activity:activity-compose:1.9.3")
 implementation("androidx.compose.ui:ui")
 implementation("androidx.compose.ui:ui-tooling-preview")
 implementation("androidx.compose.material3:material3")
 implementation("androidx.compose.material:material-icons-extended")
 implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
 implementation("androidx.core:core-ktx:1.15.0")
 debugImplementation("androidx.compose.ui:ui-tooling")
}

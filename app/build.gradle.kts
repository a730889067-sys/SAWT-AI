import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
 id("com.android.application")
 id("org.jetbrains.kotlin.android")
 id("org.jetbrains.kotlin.plugin.compose")
}
android {
 namespace = "com.sawt.ai"
 compileSdk = 36
 defaultConfig {
  applicationId = "com.sawt.ai"
  minSdk = 26
  targetSdk = 36
  versionCode = 30
  versionName = "1.5.22"
  ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
 }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
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
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
dependencies {
 implementation(files("libs/sherpa-onnx-2.0.1.aar"))
 implementation(platform("androidx.compose:compose-bom:2026.06.01"))
 implementation("androidx.activity:activity-compose:1.13.0")
 implementation("androidx.compose.ui:ui")
 implementation("androidx.compose.ui:ui-tooling-preview")
 implementation("androidx.compose.material3:material3")
 implementation("androidx.compose.material:material-icons-extended")
 implementation("androidx.core:core-ktx:1.17.0")
 implementation("androidx.webkit:webkit:1.18.0")
 testImplementation("junit:junit:4.13.2")
 debugImplementation("androidx.compose.ui:ui-tooling")
}

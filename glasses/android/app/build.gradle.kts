import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.jetbrains.kotlin.android)
  alias(libs.plugins.compose.compiler)
}

android {
  namespace = "dev.plogging.glasses"
  compileSdk = 36

  defaultConfig {
    applicationId = "dev.plogging.glasses"
    minSdk = 31
    targetSdk = 36
    versionCode = 1
    versionName = "0.1"

    // Phones and Apple-silicon emulators are arm64; x86_64 is for emulators on Intel machines.
    ndk { abiFilters += listOf("arm64-v8a", "x86_64") }

    // "0" means Developer Mode: no credentials. For a release channel, pass the values of
    // the Wearables Developer Center app from outside git:
    //   ./gradlew assembleDebug -Pmwdat_application_id=... -Pmwdat_client_token=...
    manifestPlaceholders["mwdat_application_id"] =
        providers.gradleProperty("mwdat_application_id").getOrElse("0")
    manifestPlaceholders["mwdat_client_token"] =
        providers.gradleProperty("mwdat_client_token").getOrElse("0")
  }

  buildFeatures { compose = true }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  // The model and the demo video are read straight from the APK.
  androidResources { noCompress += listOf("onnx", "mp4") }

  packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

dependencies {
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.material3)
  implementation(libs.mwdat.core)
  implementation(libs.mwdat.camera)
  implementation(libs.mwdat.mockdevice)
  implementation(libs.onnxruntime.android)
}

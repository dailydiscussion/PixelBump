import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

val localProps = Properties().apply {
  val propsFile = rootProject.file("local.properties")
  if (propsFile.exists()) {
    propsFile.inputStream().use { load(it) }
  }
}

android {
  namespace = "com.pixelbump.app"
  compileSdk = 36
  defaultConfig {
    applicationId = "com.pixelbump.app"
    minSdk = 26
    targetSdk = 36
    versionCode = 5
    versionName = "1.1.3"
  }

  val releaseStorePath = localProps.getProperty("PIXELBUMP_STORE_FILE") ?: System.getenv("PIXELBUMP_STORE_FILE")
  val releaseStoreFile = releaseStorePath?.let { rootProject.file(it) }
  val hasReleaseKey = releaseStoreFile?.exists() == true

  signingConfigs {
    if (hasReleaseKey) {
      create("release") {
        storeFile = releaseStoreFile
        storePassword = localProps.getProperty("PIXELBUMP_STORE_PASSWORD") ?: System.getenv("PIXELBUMP_STORE_PASSWORD")
        keyAlias = localProps.getProperty("PIXELBUMP_KEY_ALIAS") ?: System.getenv("PIXELBUMP_KEY_ALIAS")
        keyPassword = localProps.getProperty("PIXELBUMP_KEY_PASSWORD") ?: System.getenv("PIXELBUMP_KEY_PASSWORD")
        enableV1Signing = true
        enableV2Signing = true
        enableV3Signing = true
      }
    }
  }

  buildTypes {
    release {
      isDebuggable = false
      isMinifyEnabled = false
      if (hasReleaseKey) {
        signingConfig = signingConfigs.getByName("release")
      }
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    compose = true
    aidl = false
    buildConfig = false
    shaders = false
  }

  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
  }
}

kotlin {
  jvmToolchain(17)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  
  // Standard compliant QR Code generator
  implementation("com.google.zxing:core:3.5.3")
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)
}

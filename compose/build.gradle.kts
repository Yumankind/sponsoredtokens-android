import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// COMPOSE IS A SEPARATE MODULE AND NOT A `compileOnly` SOURCE SET IN `:android`.
//
// Both were possible. A `compileOnly` Compose dependency compiles, ships no Compose in the AAR and
// costs a consumer nothing - right up to the moment somebody who does not use Compose calls
// `SponsoredBy`, and gets a `NoClassDefFoundError` at runtime instead of a missing symbol at compile
// time. An optional dependency should be missing where a compiler can see it, so the two composables
// are their own artifact, their own dependency line, and their own decision.
plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.kotlin.compose)
  `maven-publish`
}

android {
  namespace = "com.sponsoredtokens.compose"
  compileSdk = 35

  defaultConfig {
    minSdk = 26
  }

  buildFeatures {
    compose = true
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  publishing {
    singleVariant("release") {
      withSourcesJar()
    }
  }
}

kotlin {
  compilerOptions {
    jvmTarget.set(JvmTarget.JVM_17)
  }
}

dependencies {
  api(project(":android"))
  implementation(platform(libs.compose.bom))
  implementation(libs.compose.ui)
  implementation(libs.compose.foundation)
  implementation(libs.compose.material3)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
}

afterEvaluate {
  publishing {
    publications {
      register<MavenPublication>("release") {
        from(components["release"])
        artifactId = "compose"
      }
    }
  }
}

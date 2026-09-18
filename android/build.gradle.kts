import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.android)
  `maven-publish`
}

android {
  namespace = "com.sponsoredtokens"
  compileSdk = 35

  defaultConfig {
    minSdk = 26
    consumerProguardFiles("consumer-rules.pro")
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
    freeCompilerArgs.add("-Xjvm-default=all")
  }
}

dependencies {
  api(libs.okhttp)
  api(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.androidx.browser)
  implementation(libs.androidx.annotation)

  testImplementation(libs.junit)
  testImplementation(libs.json)
  testImplementation(libs.kotlinx.coroutines.test)
}

// `components["release"]` only exists once AGP has created the variant, so the publication is
// registered after evaluation. This is the canonical shape from AGP's own documentation.
afterEvaluate {
  publishing {
    publications {
      register<MavenPublication>("release") {
        from(components["release"])
        // groupId and version are the PROJECT's, which gradle.properties sets and JitPack
        // overrides on the command line. Only the artifactId is stated here.
        artifactId = "android"
        pom {
          name.set("sponsoredtokens-android")
          description.set("Spend the sponsoredtokens.com public pool from an Android app.")
          url.set("https://sponsoredtokens.com")
          licenses {
            license {
              name.set("MIT License")
              url.set("https://github.com/Yumankind/sponsoredtokens-android/blob/main/LICENSE")
            }
          }
        }
      }
    }
  }
}

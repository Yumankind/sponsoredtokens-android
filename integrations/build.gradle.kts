import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// THE TWO ADAPTERS, AND WHY THEIR DEPENDENCIES ARE `compileOnly`.
//
// An app that uses LangChain4j already has LangChain4j; an app that uses the community OpenAI Kotlin
// client already has that. Bringing either into this AAR would put a second copy of a large library
// into every app that adds us, including the ones that use neither. So they are on the COMPILE
// classpath only: CI proves that what is written here actually matches those libraries' APIs, the
// published artifact carries neither, and a consumer adds the one they were already going to add.
//
// This module is the least certain thing in the repository, and the CI workflow says so: it builds
// in its own job, which is allowed to fail without turning the core library's result red. If a
// version bump upstream breaks an adapter, that is one module to fix and nothing else.
plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.android)
  `maven-publish`
}

android {
  namespace = "com.sponsoredtokens.integrations"
  compileSdk = 35

  defaultConfig {
    minSdk = 26
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
  compileOnly(libs.langchain4j.open.ai)
  compileOnly(libs.openai.client)
  compileOnly(libs.ktor.client.core)
}

afterEvaluate {
  publishing {
    publications {
      register<MavenPublication>("release") {
        from(components["release"])
        artifactId = "integrations"
      }
    }
  }
}

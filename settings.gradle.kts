// The public repository https://github.com/Yumankind/sponsoredtokens-android is a mirror of this
// directory (scripts/mirror.sh), so THIS FILE IS THE ROOT OF THE PUBLISHED GRADLE BUILD. Nothing
// above it travels, which is why there is no reference to the monorepo anywhere in the build.
pluginManagement {
  repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
  }
}

rootProject.name = "sponsoredtokens-android"

include(":android")
include(":compose")
include(":integrations")
include(":samples:app")

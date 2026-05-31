pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolution {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "litertlm-api-server"
include(":litertlm-library")
project(":litertlm-library").projectDir = file("library")
include(":app")

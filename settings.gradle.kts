pluginManagement {
    repositories {
        // Mirrors first: Google's own host is unreliable from here.
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        maven("https://maven.myket.ir")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        maven("https://maven.myket.ir")
        google()
        mavenCentral()
    }
}

rootProject.name = "NiloofarVpn"
include(":app")

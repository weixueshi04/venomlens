import java.util.Properties

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

val localProperties = Properties().apply {
    val propertiesFile = rootDir.resolve("local.properties")
    if (propertiesFile.isFile) propertiesFile.inputStream().use(::load)
}
val insta360MavenUsername = providers.environmentVariable("INSTA360_MAVEN_USERNAME").orNull
    ?: localProperties.getProperty("insta360.maven.username")
val insta360MavenPassword = providers.environmentVariable("INSTA360_MAVEN_PASSWORD").orNull
    ?: localProperties.getProperty("insta360.maven.password")

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        mavenLocal()
        maven { url = uri("https://maven.aliyun.com/repository/public/") }
        maven { url = uri("https://jitpack.io") }
        maven {
            url = uri("https://androidsdk.insta360.com/repository/maven-public/")
            credentials {
                username = insta360MavenUsername
                password = insta360MavenPassword
            }
        }
    }
}

rootProject.name = "snakesnap"
include(":app")

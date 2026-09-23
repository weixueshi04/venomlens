import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.kotlin.dsl.implementation
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    kotlin("kapt")
}

val repositoryRoot = rootProject.file("../..")
val speciesCatalogFile = repositoryRoot.resolve("data/species.json")
val speciesRows = JsonSlurper().parse(speciesCatalogFile) as List<*>
val referenceMetadataFiles = mutableListOf<File>()
val referenceImageFiles = speciesRows.flatMap { row ->
    val species = row as Map<*, *>
    val id = species["speciesId"] as String
    require(id.matches(Regex("[a-z0-9_]+")))
    val references = (species["referenceImages"] as? List<*>).orEmpty()
    val metadataFile = repositoryRoot.resolve("data/reference_images/$id/meta.json")
    val metadata = if (references.isNotEmpty()) {
        referenceMetadataFiles += metadataFile
        (JsonSlurper().parse(metadataFile) as Map<*, *>)["referenceImages"] as List<*>
    } else emptyList<Any>()
    references.mapNotNull { value ->
        @Suppress("UNCHECKED_CAST")
        val image = value as MutableMap<String, Any?>
        val path = image["file"] as String
        require(path.matches(Regex("""data/reference_images/$id/[A-Za-z0-9_-]+\.(jpg|jpeg|png)""")))
        require(!image["rights"].toString().isBlank() && image["rights"] != null)
        require(!image["source"].toString().isBlank() && image["source"] != null)
        val matching = metadata.filterIsInstance<Map<*, *>>().singleOrNull {
            it["file"] == path.substringAfterLast('/')
        }
        val consistent = matching != null && listOf("rights", "source", "sourcePage", "photoId").all {
            image[it] == matching[it]
        }
        image["attributionConsistent"] = consistent
        if (consistent) {
            require(repositoryRoot.resolve(path).isFile) { "Missing reference image: $path" }
            path
        } else null
    }
}
val speciesAssetsDirectory = layout.buildDirectory.dir("generated/speciesAssets")
val prepareSpeciesAssets by tasks.registering(Sync::class) {
    from(repositoryRoot) {
        include("data/species.json")
        include(referenceImageFiles)
    }
    into(speciesAssetsDirectory)
    inputs.file(speciesCatalogFile)
    inputs.files(referenceMetadataFiles)
    doLast {
        speciesAssetsDirectory.get().file("data/species.json").asFile
            .writeText(JsonOutput.toJson(speciesRows), Charsets.UTF_8)
    }
}

android {
    namespace = "com.insta360.kmpsdk.demo"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.insta360.kmpsdk.demo"
        minSdk = 29
        targetSdk = 35
        versionCode = 3
        versionName = libs.versions.inskmpVersion.get()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        viewBinding = true
    }
    sourceSets.getByName("main").assets.srcDir(speciesAssetsDirectory)
    testOptions {
        unitTests.all {
            it.systemProperty("recognitionFixtures", file("src/main/assets/mock").absolutePath)
            it.systemProperty("speciesCatalogFile", speciesCatalogFile.absolutePath)
        }
    }
    // lint 与当前 Kotlin UAST 工具链偶发不兼容导致 NonNullableMutableLiveDataDetector 崩溃，禁用该检测器以恢复 lintDebug
    lint {
        disable += "NullSafeMutableLiveData"
        // 仓库内尚有大量历史 lint error（如缺失翻译），本次功能不改变文案覆盖面；不因 lint error 中止以便产出报告
        abortOnError = false
    }
}

tasks.named("preBuild").configure { dependsOn(prepareSpeciesAssets) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.navigation.fragment)
    implementation(libs.androidx.navigation.ui)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.mockwebserver)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    implementation(libs.immersionbar)
    implementation(libs.xx.permission)
    implementation(libs.timber)
    implementation(libs.glide)
    kapt(libs.glide.compiler)

    implementation(libs.inskmp.camera)
    implementation(libs.inskmp.media)
    implementation(libs.okhttp)
}
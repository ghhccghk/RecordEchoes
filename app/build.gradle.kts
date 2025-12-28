
import com.android.build.api.dsl.ApplicationBuildType
import com.android.build.gradle.internal.cxx.configure.CmakeProperty
import com.android.build.gradle.tasks.PackageAndroidArtifact
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.util.removeSuffixIfPresent
import java.util.Properties


val buildTime = System.currentTimeMillis()


plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.jetbrainsCompose)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.navigationUi) apply false
    alias(libs.plugins.ksp)
    alias(libs.plugins.serialization)
    kotlin("plugin.parcelize")
    id("com.mikepenz.aboutlibraries.plugin")
    id("androidx.baselineprofile")
}

android {
    namespace = "com.ghhccghk.musicplay"
    compileSdk = 36
    val releaseType = if (project.hasProperty("releaseType")) project.properties["releaseType"].toString()
    else readProperties(file("../package.properties")).getProperty("releaseType")
    val myVersionName = "." + "git rev-parse --short=7 HEAD".runCommand(workingDir = rootDir)
    val nodeVersion = "python app/extract_node_version.py".runCommand(workingDir = rootDir)
    val gitHash = "git rev-parse HEAD".runCommand(workingDir = rootDir)

    defaultConfig {
        applicationId = "com.ghhccghk.musicplay"
        // 由于nodejs 编译版本为 29 遂最低为 29
        minSdk = 29
        targetSdk = 36
        versionCode = 6
        versionName = "0.6"
        //noinspection ChromeOsAbiSupport
        ndk.abiFilters += arrayOf("arm64-v8a", "armeabi-v7a","x86_64")
        buildConfigField("long", "BUILD_TIME", "$buildTime")
        buildConfigField(
            "String",
            "MY_VERSION_NAME",
            "\"$versionName$myVersionName\""
        )
        buildConfigField(
            "String",
            "RELEASE_TYPE",
            "\"$releaseType\""
        )
        buildConfigField(
            "boolean",
            "DISABLE_MEDIA_STORE_FILTER",
            "false"
        )

        buildConfigField("String", "NODE_VERSION", "\"$nodeVersion\"")
        buildConfigField("String", "GIT_HASH", "\"$gitHash\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                cppFlags("")
                arguments += listOf("-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
            }
        }
    }

    // 启用按架构分包
    splits {
        abi {
            // Detect app bundle and conditionally disable split abis
            // This is needed due to a "Sequence contains more than one matching element" error
            // present since AGP 8.9.0, for more info see:
            // https://issuetracker.google.com/issues/402800800

            // AppBundle tasks usually contain "bundle" in their name
            //noinspection WrongGradleMethod
            val isBuildingBundle = gradle.startParameter.taskNames.any { it.lowercase().contains("bundle") }

            // Disable split abis when building appBundle
            isEnable = !isBuildingBundle

            reset()
            //noinspection ChromeOsAbiSupport
            include("armeabi-v7a", "arm64-v8a", "x86_64")

            isUniversalApk = true
        }
    }

    lint {
        baseline = file("lint-baseline.xml")
    }

    // https://gitlab.com/IzzyOnDroid/repo/-/issues/491
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }


    packaging {
        dex {
            useLegacyPackaging = false
        }
        jniLibs {
            useLegacyPackaging = true
            // https://issuetracker.google.com/issues/168777344#comment11
            pickFirsts += "lib/arm64-v8a/libdlfunc.so"
            pickFirsts += "lib/armeabi-v7a/libdlfunc.so"
            pickFirsts += "lib/x86/libdlfunc.so"
            pickFirsts += "lib/x86_64/libdlfunc.so"
        }
        resources {
            // https://youtrack.jetbrains.com/issue/KT-48019/Bundle-Kotlin-Tooling-Metadata-into-apk-artifacts
            excludes.addAll(
                setOf(
                "kotlin-tooling-metadata.json",
                "META-INF/LICENSE*",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/*.kotlin_module",
                "META-INF/services/*")
            )
        }
    }

    buildTypes {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildTypes.forEach {
        (it as ApplicationBuildType).run {
            vcsInfo {
                include = false
            }
            isCrunchPngs = false // for reproducible builds TODO how much size impact does this have? where are the pngs from? can we use webp?
        }
    }


    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
            optIn.add("-Xno-param-assertions")
            optIn.add("-Xno-call-assertions")
            optIn.add("-Xno-receiver-assertions")
        }
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    externalNativeBuild.cmake {
        CmakeProperty.ANDROID_STL
    }

    buildFeatures {
        buildConfig = true
        prefab = true
        compose = true
    }

    ksp {
        arg("room.schemaLocation", project.layout.projectDirectory.dir("schemas").asFile.absolutePath)
    }

}


base {
    archivesName.set("Record_Echoes-${android.defaultConfig.versionName}${android.defaultConfig.versionNameSuffix ?: ""}-$buildTime")
}

androidComponents {
    onVariants(selector().withBuildType("release")) {
        // https://github.com/Kotlin/kotlinx.coroutines?tab=readme-ov-file#avoiding-including-the-debug-infrastructure-in-the-resulting-apk
        it.packaging.resources.excludes.addAll("META-INF/*.version", "DebugProbesKt.bin")
    }
}

baselineProfile {
    dexLayoutOptimization = true
}

aboutLibraries {
    offlineMode = false
    collect {
        gitHubApiToken = if (project.hasProperty("github.pat")) property("github.pat").toString() else null
        configPath.file("config") // TODO(ASAP) libraries json ignored
        fetchRemoteLicense.set(false)
    }
    export {
        // Remove the "generated" timestamp to allow for reproducible builds
//        excludeFields.set(listOf("generated"))
        prettyPrint.set(true)
    }
    license {
        // TODO https://github.com/mikepenz/AboutLibraries/issues/1190
        strictMode = com.mikepenz.aboutlibraries.plugin.StrictMode.FAIL
        allowedLicensesMap.put("Other", listOf("com.github.bumptech.glide:glide"))
        allowedLicenses.addAll(
            "Apache-2.0",
            "LGPL",
            "GNU Lesser General Public License v2.1",
            "BSD-2-Clause",
            "BSD-3-Clause",
            "CC0-1.0",
            "MIT",
            "EPL-1.0",
            "GPL-3.0-only"
        )
    }
    library {
        // Enable the duplication mode, allows to merge, or link dependencies which relate
        duplicationMode.set(com.mikepenz.aboutlibraries.plugin.DuplicateMode.MERGE)
        // Configure the duplication rule, to match "duplicates" with
        duplicationRule.set(com.mikepenz.aboutlibraries.plugin.DuplicateRule.GROUP)
    }
}

// https://stackoverflow.com/a/77745844
tasks.withType<PackageAndroidArtifact> {
    doFirst { appMetadata.asFile.orNull?.writeText("") }
}

dependencies {
    implementation(project(":hificore"))
    implementation(project(":misc:alacdecoder"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.androidx.animation.core.android)

    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.material3.window.size.class1)
    implementation(libs.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    implementation(libs.core)
    implementation(libs.zxing.android.embedded)
    implementation(libs.gson)
    implementation(libs.glide)


    val composeBom = platform("androidx.compose:compose-bom:2025.05.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // Choose one of the following:
    // Material Design 3
    implementation(libs.androidx.material3)

    // Android Studio Preview support
    implementation(libs.androidx.ui.tooling.preview)
    debugImplementation(libs.androidx.ui.tooling)

    // UI Tests
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.test.manifest)

    // Optional - Included automatically by material, only add when you need
    // the icons but not the material library (e.g. when using Material3 or a
    // custom design system based on Foundation)
    implementation(libs.androidx.material.icons.core)
    // Optional - Add full set of material icons
    implementation(libs.androidx.material.icons.extended)
    // Optional - Add window size utils
    implementation(libs.androidx.adaptive)

    // Optional - Integration with activities
    implementation(libs.androidx.activity.compose)
    // Optional - Integration with ViewModels
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    // Optional - Integration with LiveData
    implementation(libs.androidx.runtime.livedata)
    // Optional - Integration with RxJava
    implementation(libs.androidx.runtime.rxjava2)

    implementation(libs.androidx.media3.exoplayer)// Media3 ExoPlayer
    implementation(libs.androidx.media3.session)   // 媒体会话管理
    implementation(libs.androidx.media3.ui)   // 媒体会话管理
    implementation(libs.androidx.media3.ui.compose)   // 媒体会话管理
    implementation(libs.androidx.media3.common.ktx)

//    implementation(project(":media3-lib-exoplayer"))
//    implementation(project(":media3-lib-decoder-midi"))
//    implementation(project(":media3-lib-session"))
//    implementation(project(":media3-lib-transformer"))
//    implementation(project(":media3-lib-effect"))
//    implementation(project(":media3-lib-common"))
//    implementation(project(":media3-lib-common-ktx"))

    implementation(libs.superlyricapi)

    implementation(libs.moshi)
    implementation(libs.moshi.kotlin)
    ksp(libs.moshi.kotlin.codegen)
    implementation(libs.androidx.room.ktx)

    implementation( libs.kotlinx.coroutines.core)
    implementation( libs.kotlinx.coroutines.android)

    implementation( libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation (libs.androidx.room.rxjava2)
    implementation( libs.androidx.room.ktx)

    implementation (libs.rxjava)
    implementation(libs.rxandroid)

    implementation(libs.androidx.mediarouter)
    implementation(libs.androidx.palette.ktx)
    implementation(libs.cupertino.icons.extended)

    implementation(libs.aboutlibraries.compose.m3)

    implementation(libs.retrofit)
    implementation(libs.converter.gson)


    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance)
    implementation(libs.coil.compose)
    implementation(libs.hiddenapibypass)
    implementation(libs.lyrics.core)
    implementation(libs.lyrics.ui)
    implementation(libs.hyperfocusapi)
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.androidx.compose)
    //implementation(libs.androidx.glance.wear.tiles)


}

fun String.runCommand(
    workingDir: File = File(".")
): String = providers.exec {
    setWorkingDir(workingDir)
    commandLine(split(' '))
}.standardOutput.asText.get().removeSuffixIfPresent("\n")

fun readProperties(propertiesFile: File) = Properties().apply {
    propertiesFile.inputStream().use { fis ->
        load(fis)
    }
}

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
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenLocal()
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://jitpack.io")  // 添加 JitPack 库
    }
}

gradle.extra.apply {
    set("androidxMediaEnableMidiModule", true)
}


rootProject.name = "Record Echoes"
includeBuild(file("media").toPath().toRealPath().toAbsolutePath().toString()) {
    dependencySubstitution {
        substitute(module("androidx.media3:media3-common")).using(project(":lib-common"))
        substitute(module("androidx.media3:media3-common-ktx")).using(project(":lib-common-ktx"))
        substitute(module("androidx.media3:media3-exoplayer")).using(project(":lib-exoplayer"))
        substitute(module("androidx.media3:media3-exoplayer-midi")).using(project(":lib-decoder-midi"))
        substitute(module("androidx.media3:media3-session")).using(project(":lib-session"))
    }
}
include(":misc:audiofxstub")
include(":misc:audiofxstub2")
include(":misc:audiofxfwd")
include(":misc:alacdecoder")
include(":hificore",":app")
 
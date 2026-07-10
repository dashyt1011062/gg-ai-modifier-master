plugins {
    id("com.android.application")
    id("kotlin-android")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "com.yl.aigg.ai_gg666"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }

    defaultConfig {
        applicationId = "com.yl.aigg.ai_gg666"
        minSdk = flutter.minSdkVersion
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++11"
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            // TODO: Add your own signing config for the release build.
            // Signing with the debug keys for now, so `flutter run --release` works.
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
}

flutter {
    source = "../.."
}

// 复制 root 可执行文件到 assets（jniLibs 会被重命名为 lib*.so）
tasks.register("copyRootHelpers") {
    doLast {
        val buildDir = layout.buildDirectory.get().asFile
        val abiList = listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        val helperList = listOf("scanner_root", "perf_bp_root")

        var copied = 0
        abiList.forEach { abi ->
            helperList.forEach { helper ->
                var srcFile: File? = null
                for (variant in listOf("debug", "release")) {
                    for (prefix in listOf("cxx", "cmake")) {
                        val baseDir = File("$buildDir/intermediates/$prefix/$variant")
                        if (!baseDir.exists()) continue
                        baseDir.listFiles()?.forEach { hashDir ->
                            if (!hashDir.isDirectory) return@forEach
                            val candidate = File(hashDir, "obj/$abi/$helper")
                            if (candidate.exists()) {
                                srcFile = candidate
                                return@forEach
                            }
                        }
                        if (srcFile != null) break
                    }
                    if (srcFile != null) break
                }

                if (srcFile != null) {
                    val destDir = file("src/main/assets/native/$abi")
                    val destFile = file("$destDir/$helper")
                    destDir.mkdirs()
                    srcFile!!.copyTo(destFile, overwrite = true)
                    println("✅ Copied $helper for $abi from ${srcFile!!.absolutePath}")
                    copied++
                } else {
                    println("⚠️ $helper not found for $abi")
                }
            }
        }

        if (copied == 0) {
            println("❌ No root helpers found for any ABI. CMake build may have failed.")
        }
    }
}

tasks.register("copyScannerRoot") {
    dependsOn("copyRootHelpers")
}

// 在 CMake 构建完成后自动复制 scanner_root 到 assets
afterEvaluate {
    // 尝试多种可能的 CMake 构建任务名称
    val cmakeTaskNames = listOf(
        "externalNativeBuildDebug",
        "externalNativeBuildRelease",
        "mergeDebugNativeLibs",
        "mergeReleaseNativeLibs",
        "buildCMakeDebug",
        "buildCMakeRelease",
    )

    var linked = false
    for (taskName in cmakeTaskNames) {
        tasks.findByName(taskName)?.let { task ->
            task.finalizedBy("copyRootHelpers")
            linked = true
            println("✅ Linked copyRootHelpers after: $taskName")
        }
    }

    // 兜底：也作为 assembleDebug 的依赖
    if (!linked) {
        tasks.findByName("assembleDebug")?.dependsOn("copyRootHelpers")
        tasks.findByName("assembleRelease")?.dependsOn("copyRootHelpers")
        println("⚠️ Linked copyRootHelpers as dependency of assembleDebug/assembleRelease (fallback)")
    }
}

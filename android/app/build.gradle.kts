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

// 复制 scanner_root 可执行文件到 assets（jniLibs 会被重命名为 lib*.so）
tasks.register("copyScannerRoot") {
    doLast {
        val buildDir = layout.buildDirectory.get().asFile
        val abiList = listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")

        var copied = 0
        abiList.forEach { abi ->
            // 动态搜索 scanner_root（路径格式: intermediates/cxx/debug/$hash/obj/$abi/scanner_root）
            var srcFile: File? = null

            // 搜索 cxx 和 cmake 目录（可能有 debug/release）
            for (variant in listOf("debug", "release")) {
                for (prefix in listOf("cxx", "cmake")) {
                    val baseDir = File("$buildDir/intermediates/$prefix/$variant")
                    if (!baseDir.exists()) continue

                    // 遍历哈希子目录
                    baseDir.listFiles()?.forEach { hashDir ->
                        if (!hashDir.isDirectory) return@forEach
                        val candidate = File(hashDir, "obj/$abi/scanner_root")
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
                val destFile = file("$destDir/scanner_root")
                destDir.mkdirs()
                srcFile!!.copyTo(destFile, overwrite = true)
                println("✅ Copied scanner_root for $abi from ${srcFile!!.absolutePath}")
                copied++
            } else {
                println("⚠️ scanner_root not found for $abi")
            }
        }

        if (copied == 0) {
            println("❌ No scanner_root found for any ABI. CMake build may have failed.")
        }
    }
}

// 在 CMake 构建完成后自动复制 scanner_root 到 assets
afterEvaluate {
    // 尝试多种可能的 CMake 构建任务名称
    val cmakeTaskNames = listOf(
        "externalNativeBuildDebug",
        "mergeDebugNativeLibs",
        "buildCMakeDebug",
    )

    var linked = false
    for (taskName in cmakeTaskNames) {
        tasks.findByName(taskName)?.let { task ->
            task.finalizedBy("copyScannerRoot")
            linked = true
            println("✅ Linked copyScannerRoot after: $taskName")
        }
    }

    // 兜底：也作为 assembleDebug 的依赖
    if (!linked) {
        tasks.findByName("assembleDebug")?.dependsOn("copyScannerRoot")
        println("⚠️ Linked copyScannerRoot as dependency of assembleDebug (fallback)")
    }
}

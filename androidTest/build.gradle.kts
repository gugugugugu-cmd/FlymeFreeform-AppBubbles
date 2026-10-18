plugins {
    alias(libs.plugins.android.application)
}

/*
 * 阶段 5 安全验证夹具（PHASE5_COMPAT_MATRIX.md §4）。
 *
 * 独立模块而不是 app 的 androidTest 源集：夹具必须能被单独安装、单独卸载，
 * 且绝不能把验证代码混进模块主 APK 的发布产物里。
 *
 * 不依赖 Kotlin：整个夹具是一个 Java Instrumentation 测试，
 * 只用 android.jar + 平台自带的 JUnit/runner，避免为了一次验证引入新的构建面。
 */
android {
    namespace = "io.github.mangi.flymefreeform.forge"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.github.mangi.flymefreeform.forge"
        minSdk = 35
        targetSdk = 37
        versionCode = 1
        versionName = "1.0-forge"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        // 夹具本体放在 androidTest 源集里：安装后作为 instrumentation 运行，
        // 不产生会在桌面出现的启动图标，也就没有"夹具应用被用户误点"的风险。
        getByName("androidTest") {
            java.srcDirs("src/main/java")
        }
    }
}

dependencies {
    // 平台自带的 AndroidJUnitRunner / JUnit4，不引入任何额外依赖：
    // 夹具与目标设备同 API 级别（37），没有必要为了一次验证去拉 androidx.test。
    // 用 `adb shell am instrument -w <pkg>/androidx.test.runner.AndroidJUnitRunner`
    // 或直接 `am instrument -w -e class ...` 触发。

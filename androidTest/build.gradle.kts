plugins {
    alias(libs.plugins.android.application)
}

/*
 * 阶段 5 安全验证夹具（PHASE5_COMPAT_MATRIX.md §4）。
 *
 * 独立模块而不是 app 的 androidTest 源集：夹具必须能被单独安装、单独卸载，
 * 且绝不能把验证代码混进模块主 APK 的发布产物里。
 *
 * 不依赖 Kotlin，也不用 instrumentation：夹具只是一个 Java Activity，
 * 编译输入只有 android.jar，避免为了一次验证引入新的构建面。
 */
// 阶段 5 的 IPC 安全验证夹具：一个 Activity，启动即向 SystemUI 发三条伪造广播。
// 不含测试基础设施（见 AndroidManifest.xml 顶部说明）。
android {
    namespace = "io.github.mangi.flymefreeform.forge"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.mangi.flymefreeform.forge"
        minSdk = 34
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

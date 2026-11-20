plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room)
}

kotlin {
    android {
        namespace = "top.msfxp.schedule.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        // 开启 Android 资源打包，保证 Compose Multiplatform 资源随 AAR 进入 APK
        androidResources {
            enable = true
        }
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime.kmp)
            implementation(libs.compose.foundation.kmp)
            implementation(libs.compose.ui.kmp)
            api(libs.compose.components.resources.kmp)

            // Miuix KMP 组件
            implementation(libs.miuix.ui.kmp)
            implementation(libs.miuix.preference.kmp)
            implementation(libs.miuix.icons.kmp)
            implementation(libs.miuix.nav.kmp)

            // Lifecycle & ViewModel
            implementation(libs.jetbrains.lifecycle.viewmodel.compose)
            implementation(libs.jetbrains.lifecycle.runtime.compose)

            // Room & SQLite
            implementation(libs.androidx.room.runtime)
            implementation(libs.androidx.sqlite.bundled)

            // DataStore
            implementation(libs.androidx.datastore.preferences.core)

            // Ktor & Kotlinx
            implementation(libs.ktor.client.core)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)

            // Koin DI
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)
        }

        androidMain.dependencies {
            implementation(libs.ktor.client.android)
            implementation(libs.kotlinx.coroutines.android)
        }

        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }

    // expect/actual 类/对象为 Beta 特性，显式确认使用以消除编译提示
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "top.msfxp.schedule.shared.resources"
    generateResClass = always
}

room {
    schemaDirectory("$projectDir/schemas")
}

// Room 插件不覆盖 KMP androidLibrary 变体的 KSP 任务，Android 侧单独启用 schema 导出
tasks.withType<com.google.devtools.ksp.gradle.KspAATask>().configureEach {
    if (name.startsWith("kspAndroid")) {
        val schemaDir = layout.projectDirectory.dir("schemas").asFile.absolutePath
        commandLineArgumentProviders.add(
            org.gradle.process.CommandLineArgumentProvider { listOf("room.schemaLocation=$schemaDir") }
        )
    }
}

dependencies {
    add("kspAndroid", libs.androidx.room.compiler)
    add("kspIosArm64", libs.androidx.room.compiler)
    add("kspIosSimulatorArm64", libs.androidx.room.compiler)
}

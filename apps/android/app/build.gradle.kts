plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 版本号唯一来源：仓库根目录的 deeptutor/__version__.py
val deeptutorVersion: String = run {
    val file = rootProject.file("../../deeptutor/__version__.py")
    require(file.exists()) { "找不到 ${file.path}" }
    Regex("""__version__\s*=\s*"([^"]+)"""")
        .find(file.readText())
        ?.groupValues?.get(1)
        ?: error("无法从 ${file.path} 解析版本号")
}

// 1.7.2 -> 10702；保证 Android 要求的单调递增整数
val deeptutorVersionCode: Int = deeptutorVersion
    .split(".")
    .let { (it + listOf("0", "0", "0")).take(3) }
    .let { (major, minor, patch) -> major.toInt() * 10000 + minor.toInt() * 100 + patch.toInt() }

android {
    namespace = "dev.raaa.deeptutor"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.raaa.deeptutor"
        minSdk = 26
        targetSdk = 35
        versionCode = deeptutorVersionCode
        versionName = deeptutorVersion
        // CI 注入默认地址；本地构建默认为空，强制首次启动手动输入
        buildConfigField(
            "String",
            "DEFAULT_SERVER_URL",
            "\"${project.findProperty("deeptutorDefaultServerUrl") ?: ""}\""
        )
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            val storePath = System.getenv("ANDROID_KEYSTORE_PATH")
            if (!storePath.isNullOrBlank()) {
                storeFile = file(storePath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (!System.getenv("ANDROID_KEYSTORE_PATH").isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.webkit)
    implementation(libs.swiperefresh)
    implementation(libs.splashscreen)
    implementation(libs.activity)
    implementation(libs.lifecycle.runtime)
    testImplementation(libs.junit)
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
    android.set(true)
    ignoreFailures.set(false)
    filter {
        exclude("**/generated/**")
    }
}

configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    val baselineFile = project.file("detekt-baseline.xml")
    if (baselineFile.exists()) {
        baseline = baselineFile
    }
}

android {
    namespace = "com.nuvio.remote"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nuvio.remote"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "0.5.1"
    }

    val releaseKeystorePath = System.getenv("NUVIO_KEYSTORE_PATH")?.trim().orEmpty()
    val hasReleaseKeystore = releaseKeystorePath.isNotEmpty() && file(releaseKeystorePath).exists()

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = System.getenv("NUVIO_KEYSTORE_PASSWORD") ?: ""
                keyAlias = System.getenv("NUVIO_KEY_ALIAS") ?: ""
                keyPassword = System.getenv("NUVIO_KEY_PASSWORD") ?: ""
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    // Rede: WebSocket do companion + pareamento HTTP (SECURITY.md)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // WebRTC H.264 da aba Tela (build mantida no Maven Central)
    implementation("io.getstream:stream-webrtc-android:1.3.8")

    // Token de sessão no Keystore (SECURITY.md §4.2)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Scanner de QR Code em Ajustes (CameraX + ZXing core offline leve)
    val cameraXVersion = "1.4.0"
    implementation("androidx.camera:camera-core:$cameraXVersion")
    implementation("androidx.camera:camera-camera2:$cameraXVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraXVersion")
    implementation("androidx.camera:camera-view:$cameraXVersion")
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val appVersion: String = (project.findProperty("VERSION_NAME") as String).trim()

/** `0.1.0` → 100; `1.2.3` → 10203. O Nexus compara o semver do header, o Android compara este inteiro. */
fun versionCodeDe(semver: String): Int {
    val (major, minor, patch) = semver.split('.').map { it.toInt() }
    require(minor < 100 && patch < 100) { "minor/patch até 99: $semver" }
    return major * 10_000 + minor * 100 + patch
}

fun prop(nome: String): String = (project.findProperty(nome) as String).trim()

/**
 * Keystore de release vem SÓ do ambiente (CI = secrets do repo). Sem ele, o
 * assembleRelease assina com a chave de debug pra dar pra testar local — o
 * APK oficial é sempre o do CI.
 */
val keystorePath: String? = System.getenv("ANDROID_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }

android {
    namespace = "br.com.berzerk.coletor"
    compileSdk = 34

    defaultConfig {
        applicationId = "br.com.berzerk.coletor"
        minSdk = 26
        targetSdk = 34
        this.versionCode = versionCodeDe(appVersion)
        this.versionName = appVersion

        // Esquema do redirect do Cognito (callback `berzerkrfid://auth/callback`,
        // registrado no app client da infra). O AppAuth registra a Activity
        // que recebe esse esquema a partir deste placeholder.
        manifestPlaceholders["appAuthRedirectScheme"] = "berzerkrfid"

        buildConfigField("String", "NEXUS_API_BASE_URL", "\"${prop("NEXUS_API_BASE_URL")}\"")
        buildConfigField("String", "COGNITO_HOSTED_UI_DOMAIN", "\"${prop("COGNITO_HOSTED_UI_DOMAIN")}\"")
        buildConfigField("String", "COGNITO_ISSUER", "\"${prop("COGNITO_ISSUER")}\"")
        buildConfigField("String", "COGNITO_CLIENT_ID", "\"${prop("COGNITO_CLIENT_ID")}\"")
        buildConfigField("String", "COGNITO_REDIRECT_URI", "\"berzerkrfid://auth/callback\"")
        buildConfigField("String", "COGNITO_LOGOUT_URI", "\"berzerkrfid://auth/logout\"")

        ndk {
            // O aar da Urovo traz libs nativas pra armeabi, armeabi-v7a e arm64-v8a.
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (keystorePath != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    applicationVariants.all {
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                "berzerk-coletor-${versionName}.apk"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    // SDK UHF da Urovo (oficial, github.com/UrovoSdkCenter/RFID_Android). Proprietário; ver README.
    implementation(files("libs/URFIDLibrary-v2.6.0609.aar"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.appauth)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.face.detection)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}

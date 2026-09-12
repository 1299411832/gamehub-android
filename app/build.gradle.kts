import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing is read from the environment (CI secrets). When any of the
// required values is missing we silently fall back to the debug signing config so
// that builds stay green from day one; real release signing can be enabled later
// just by adding the secrets.
val keystoreFilePath: String? = System.getenv("KEYSTORE_FILE")
val keystorePassword: String? = System.getenv("KEYSTORE_PASSWORD")
val keyAliasValue: String? = System.getenv("KEY_ALIAS")
val keyPasswordValue: String? = System.getenv("KEY_PASSWORD")

val hasReleaseSigning: Boolean = !keystoreFilePath.isNullOrBlank() &&
    !keystorePassword.isNullOrBlank() &&
    !keyAliasValue.isNullOrBlank() &&
    !keyPasswordValue.isNullOrBlank() &&
    file(keystoreFilePath).exists()

android {
    namespace = "space.devmini.gamehub"
    compileSdk = 35

    defaultConfig {
        applicationId = "space.devmini.gamehub"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.0.2"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(keystoreFilePath!!)
                storePassword = keystorePassword
                keyAlias = keyAliasValue
                keyPassword = keyPasswordValue
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("com.google.android.material:material:1.12.0")
}

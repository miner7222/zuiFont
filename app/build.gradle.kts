import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.miner7222.zuifont"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.miner7222.zuifont"
        minSdk = 29
        targetSdk = 36
        versionCode = 1000
        versionName = "1.0.0"
    }

    signingConfigs {
        create("release") {
            val keystorePropertiesFile = rootProject.file("local.properties")
            val keystoreProperties = Properties()
            if (keystorePropertiesFile.exists()) {
                keystoreProperties.load(FileInputStream(keystorePropertiesFile))
            }

            val storePath = keystoreProperties.getProperty("storeFile")
                ?: System.getenv("SIGNING_KEY_STORE_PATH")

            val storePwd = keystoreProperties.getProperty("storePassword")
                ?: System.getenv("SIGNING_STORE_PASSWORD")

            val keyAliasVal = keystoreProperties.getProperty("keyAlias")
                ?: System.getenv("SIGNING_KEY_ALIAS")

            val keyPwd = keystoreProperties.getProperty("keyPassword")
                ?: System.getenv("SIGNING_KEY_PASSWORD")

            if (!storePath.isNullOrEmpty() && !storePwd.isNullOrEmpty()) {
                storeFile = file(storePath)
                storePassword = storePwd
                keyAlias = keyAliasVal
                keyPassword = keyPwd
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (signingConfigs.getByName("release").storeFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3:1.5.0-alpha28")
    implementation("androidx.activity:activity-compose:1.13.0")
}

tasks.register("printVersionName") {
    doLast {
        println(android.defaultConfig.versionName)
    }
}

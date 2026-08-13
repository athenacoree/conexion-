import java.security.KeyStore

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.conexion"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.conexion"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            val envKeystoreFilePath = System.getenv("KEYSTORE_FILE_PATH")
            val envKeystorePassword = System.getenv("KEYSTORE_PASSWORD")
            val envKeyAlias = System.getenv("KEY_ALIAS")
            val envKeyPasswordRaw = System.getenv("KEY_PASSWORD")

            if (!envKeystoreFilePath.isNullOrEmpty() && !envKeystorePassword.isNullOrEmpty() && !envKeyAlias.isNullOrEmpty()) {
                val keystoreFile = file(envKeystoreFilePath)
                if (keystoreFile.exists()) {
                    var correctStorePassword = envKeystorePassword
                    var correctKeyPassword = if (envKeyPasswordRaw.isNullOrEmpty()) envKeystorePassword else envKeyPasswordRaw
                    var correctKeyAlias = envKeyAlias

                    // Test combinations of trimmed and untrimmed passwords/aliases to self-heal credentials
                    val storePassCandidates = listOf(envKeystorePassword, envKeystorePassword.trim())
                    val keyPassCandidates = if (!envKeyPasswordRaw.isNullOrEmpty()) {
                        listOf(envKeyPasswordRaw, envKeyPasswordRaw.trim(), envKeystorePassword, envKeystorePassword.trim())
                    } else {
                        listOf(envKeystorePassword, envKeystorePassword.trim())
                    }
                    val aliasCandidates = listOf(envKeyAlias, envKeyAlias.trim())
                    val keystoreTypes = listOf("PKCS12", "JKS")

                    var found = false
                    for (type in keystoreTypes) {
                        for (sp in storePassCandidates) {
                            for (kp in keyPassCandidates) {
                                for (alias in aliasCandidates) {
                                    try {
                                        val keystore = KeyStore.getInstance(type)
                                        keystoreFile.inputStream().use { stream ->
                                            keystore.load(stream, sp.toCharArray())
                                        }
                                        if (keystore.containsAlias(alias)) {
                                            keystore.getKey(alias, kp.toCharArray())
                                            correctStorePassword = sp
                                            correctKeyPassword = kp
                                            correctKeyAlias = alias
                                            found = true
                                            break
                                        }
                                    } catch (e: Exception) {
                                        // ignore and try next combination
                                    }
                                }
                                if (found) break
                            }
                            if (found) break
                        }
                        if (found) break
                    }

                    storeFile = keystoreFile
                    storePassword = correctStorePassword
                    keyAlias = correctKeyAlias
                    keyPassword = correctKeyPassword
                }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val isSigningConfigured = signingConfigs.getByName("release").storeFile != null
            if (isSigningConfigured) {
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
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.10"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation("io.coil-kt:coil-compose:2.6.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

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
                    var correctStoreType = "jks"

                    // Test combinations of trimmed and untrimmed passwords/aliases to self-heal credentials
                    val storePassCandidates = listOf(envKeystorePassword, envKeystorePassword.trim())
                    val keyPassCandidates = if (!envKeyPasswordRaw.isNullOrEmpty()) {
                        listOf(envKeyPasswordRaw, envKeyPasswordRaw.trim(), envKeystorePassword, envKeystorePassword.trim())
                    } else {
                        listOf(envKeystorePassword, envKeystorePassword.trim())
                    }
                    val aliasCandidates = listOf(envKeyAlias, envKeyAlias.trim())
                    val typeCandidates = listOf("PKCS12", "JKS", KeyStore.getDefaultType()).distinct()

                    var found = false
                    logger.lifecycle("--- Keystore Diagnosis Start ---")
                    logger.lifecycle("Keystore File: ${keystoreFile.absolutePath} (size: ${keystoreFile.length()})")
                    logger.lifecycle("Type candidates: $typeCandidates")
                    logger.lifecycle("envKeystorePassword chars: ${envKeystorePassword.map { it.code }}")
                    logger.lifecycle("envKeyPasswordRaw chars: ${envKeyPasswordRaw?.map { it.code }}")
                    logger.lifecycle("envKeyAlias chars: ${envKeyAlias.map { it.code }}")

                    var detectedType: String? = null
                    for (type in typeCandidates) {
                        for (sp in storePassCandidates) {
                            try {
                                val keystore = KeyStore.getInstance(type)
                                keystoreFile.inputStream().use { stream ->
                                    keystore.load(stream, sp.toCharArray())
                                }
                                detectedType = type
                                correctStorePassword = sp
                                found = true
                                logger.lifecycle("Successfully loaded keystore with Type: $type, Password length: ${sp.length}")
                                break
                            } catch (e: Exception) {
                                logger.lifecycle("Failed to load keystore [Type: $type, Password length: ${sp.length}] - Exception: ${e.javaClass.name}: ${e.message}")
                            }
                        }
                        if (found) break
                    }

                    if (found && detectedType != null) {
                        correctStoreType = detectedType
                        var aliasFound = false
                        val keystore = KeyStore.getInstance(correctStoreType)
                        keystoreFile.inputStream().use { stream ->
                            keystore.load(stream, correctStorePassword.toCharArray())
                        }
                        for (alias in aliasCandidates) {
                            if (keystore.containsAlias(alias)) {
                                for (kp in keyPassCandidates) {
                                    try {
                                        keystore.getKey(alias, kp.toCharArray())
                                        correctKeyPassword = kp
                                        correctKeyAlias = alias
                                        aliasFound = true
                                        break
                                    } catch (e: Exception) {
                                        // ignore
                                    }
                                }
                            }
                            if (aliasFound) break
                        }
                    } else {
                        // Guess by file contents if credentials didn't load it
                        try {
                            keystoreFile.inputStream().use { stream ->
                                val header = ByteArray(4)
                                val read = stream.read(header)
                                if (read == 4) {
                                    val isJks = (header[0] == 0xFE.toByte() && header[1] == 0xED.toByte() &&
                                                 header[2] == 0xFE.toByte() && header[3] == 0xED.toByte())
                                    correctStoreType = if (isJks) "JKS" else "PKCS12"
                                }
                            }
                        } catch (e: Exception) {
                            // fallback to jks
                        }
                    }

                    logger.lifecycle("Keystore load success: $found. Chosen type: $correctStoreType, Chosen alias: '$correctKeyAlias'")
                    logger.lifecycle("--- Keystore Diagnosis End ---")

                    storeFile = keystoreFile
                    storePassword = correctStorePassword
                    keyAlias = correctKeyAlias
                    keyPassword = correctKeyPassword
                    storeType = correctStoreType
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
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
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

    // Ktor Server 2.3.12 dependencies
    implementation("io.ktor:ktor-server-core:2.3.12")
    implementation("io.ktor:ktor-server-netty:2.3.12")
    implementation("io.ktor:ktor-server-websockets:2.3.12")

    // QR Code generation
    implementation("com.google.zxing:core:3.5.1")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

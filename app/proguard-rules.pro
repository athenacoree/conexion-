# Proguard rules for Conexión app

# Keep Jetpack Compose rules
-keepclassmembers class * extends androidx.compose.runtime.RecomposeScope { *; }
-keep class androidx.compose.compiler.plugins.kotlin.ComposeSubplugin { *; }

# Keep Kotlin reflect and coroutines if necessary
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Keep Wi-Fi Direct and BLE model classes
-keep class com.example.conexion.PeerInfo { *; }
-keep class com.example.conexion.BackgroundDiscoveryService$BlePeer { *; }

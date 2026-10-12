# Referenced by name from AndroidManifest <meta-data>, so R8 can't see it.
-keep class net.kravos.player.cast.CastOptionsProvider { *; }

# Room entities / DAOs are generated and referenced reflectively by the generated _Impl classes.
-keep class net.kravos.player.data.** { *; }

# Media3 / Cast use reflection for a few optional components.
-dontwarn org.checkerframework.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn com.google.j2objc.annotations.**

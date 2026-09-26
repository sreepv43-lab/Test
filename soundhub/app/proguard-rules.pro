# kotlinx.serialization (the library index)
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class io.github.sreepv43.soundhub.**$$serializer { *; }
-keepclassmembers class io.github.sreepv43.soundhub.** { *** Companion; }
-keepclasseswithmembers class io.github.sreepv43.soundhub.** { kotlinx.serialization.KSerializer serializer(...); }

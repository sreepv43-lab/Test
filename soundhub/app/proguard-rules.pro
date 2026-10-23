# kotlinx.serialization (the library index)
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class io.github.sreepv43.soundhub.**$$serializer { *; }
-keepclassmembers class io.github.sreepv43.soundhub.** { *** Companion; }
-keepclasseswithmembers class io.github.sreepv43.soundhub.** { kotlinx.serialization.KSerializer serializer(...); }

# SMB home server (smbj and its crypto use reflection and desktop-only classes)
-keep class com.hierynomus.** { *; }
-keep class net.engio.mbassy.** { *; }
-keep class org.bouncycastle.jce.provider.BouncyCastleProvider { *; }
-keep class org.bouncycastle.jcajce.provider.** { *; }
-dontwarn com.hierynomus.**
-dontwarn org.bouncycastle.**
-dontwarn org.ietf.jgss.**
-dontwarn javax.el.**
-dontwarn javax.security.**
-dontwarn org.slf4j.**
-dontwarn sun.security.**
-dontwarn net.engio.mbassy.**
-dontwarn org.apache.**
-dontwarn org.codehaus.**

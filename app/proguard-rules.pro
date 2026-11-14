-keep class com.zephyr.browser.** { *; }
-keepclassmembers class * implements android.os.Parcelable { public static final ** CREATOR; }
-dontwarn org.slf4j.**
-keepattributes *Annotation*

# v1.7.0: WireGuard tunnel library (JNI + reflection-safe surface)
-keep class com.wireguard.** { *; }
-dontwarn com.wireguard.**

# v1.7.61: PDFBox (com.tom-roush:pdfbox-android).
#
# The library can decode JPEG 2000 images when the optional Gemalto JP2 decoder
# is on the classpath. It is not, deliberately — nothing here renders embedded
# images; PDFs are used for their text and their form fields. R8 only needs to
# be told the absence is intentional.
-dontwarn com.gemalto.jp2.**
# PDFBox loads filters and font resources reflectively by class name, so the
# model and filter packages must survive shrinking with their names intact.
-keep class com.tom_roush.pdfbox.filter.** { *; }
-keep class com.tom_roush.pdfbox.pdmodel.font.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-dontwarn com.tom_roush.fontbox.**

# v1.7.79 — offline speech recognition (Vosk).
# Vosk reaches its native library through JNA, which maps Java classes and
# fields onto C structs BY NAME at runtime. R8 renaming any of it produces a
# library that loads and then cannot find a single symbol, so both sides of
# that bridge have to survive minification intact.
-keep class org.vosk.** { *; }
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**

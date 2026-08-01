# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# ── PDFBox-android: optional JPEG2000 codec we never use/ship ─────────────
-dontwarn com.gemalto.jp2.JP2Decoder

# ── Apache POI / xmlbeans / commons-compress: heavy reflection + XML
# schema-driven binding — without these, DOCX materials fail to parse
# at runtime even though the release build compiles fine.
-keep class org.apache.poi.** { *; }
-keep class org.apache.xmlbeans.** { *; }
-keep class org.openxmlformats.** { *; }
-keep class schemasMicrosoftComOffice** { *; }
-keep class org.etsi.uri.** { *; }
-keep class org.apache.commons.compress.** { *; }
-dontwarn org.apache.poi.**
-dontwarn org.apache.xmlbeans.**
-dontwarn org.openxmlformats.**
-dontwarn org.etsi.uri.**
-dontwarn javax.xml.stream.**
-dontwarn org.w3c.dom.**
# POI's chart/shape utilities pull in desktop java.awt classes that don't
# exist on Android — dead code path here since we only read DOCX text.
-dontwarn java.awt.**
-dontwarn com.graphbuilder.**

# ── JNA / Vosk: native bindings resolved via reflection ────────────────────
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.Structure { public *; }
-keep class org.vosk.** { *; }
-dontwarn com.sun.jna.**

# ── org.json (used for manual JSONObject/JSONArray field access) ──────────
-keep class org.json.** { *; }

# ── PDFBox-android font/glyph resources loaded by class name ──────────────
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-dontwarn com.tom_roush.**
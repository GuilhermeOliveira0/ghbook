# PdfBox-Android: dependências opcionais (JPEG2000) não incluídas
-dontwarn com.gemalto.jp2.**
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-keep class com.tom_roush.harmony.** { *; }
# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class br.com.ghbook.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class br.com.ghbook.**$$serializer { *; }
# sherpa-onnx: campos de configuração lidos via JNI
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keep class br.com.ghbook.neural.ChunkCallback { *; }

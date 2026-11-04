# Keep Room entities' generated code reachable is handled by Room's own rules.
# kotlinx.serialization models for seed JSON:
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

# ML Kit Barcode Scanning resolves internal classes reflectively (service loading).
# R8 full mode (AGP 9) strips/renames them, and BarcodeScanning.getClient() NPEs
# in release builds without these keeps.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_barcode.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_barcode_bundled.** { *; }
-dontwarn com.google.mlkit.**

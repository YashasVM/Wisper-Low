# Keep sherpa-onnx JNI bindings
-keep class com.k2fsa.sherpa.onnx.** { *; }

# ONNX Runtime
-keep class ai.onnxruntime.** { *; }

# commons-compress references optional codecs (xz, brotli, zstd, asm) we never load.
-dontwarn org.tukaani.xz.**
-dontwarn org.brotli.dec.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.objectweb.asm.**
-dontwarn org.apache.commons.compress.harmony.**

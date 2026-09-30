# sherpa-onnx / onnxruntime：JNI 按名字反射访问这些类的字段与方法。
# 被 R8 裁掉不会编译报错，只会在 release 包里运行时崩。（参考 phone-whisper proguard-rules.pro）
-keepclassmembers class * { native <methods>; }
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keep class ai.onnxruntime.** { *; }
-dontwarn com.k2fsa.**
-dontwarn ai.onnxruntime.**

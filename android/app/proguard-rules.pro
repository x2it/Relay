-keep class com.freeproxy.app.data.model.** { *; }
-keep class org.conscrypt.** { *; }

# sing-box libbox（JNI 桥接，禁止裁剪/重命名）
-keep class io.nekohasekai.libbox.** { *; }

# Gson 反射所需
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# 第三方库的可选依赖（不影响运行）
-dontwarn sun.misc.**
-dontwarn javax.annotation.**
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn org.slf4j.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.j2objc.annotations.**

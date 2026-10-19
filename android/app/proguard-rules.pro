-keep class com.freeproxy.app.data.model.** { *; }
-keep class org.conscrypt.** { *; }

# sing-box libbox（JNI 桥接，禁止裁剪/重命名）
-keep class io.nekohasekai.libbox.** { *; }
# gobind 运行时 Seq 桥接类（随 AAR 提供，位于 go 包）
-keep class go.Seq { *; }
# 保留所有 native 方法及其所属类（JNI 注册依赖符号名）
-keepclasseswithmembernames class * {
    native <methods>;
}

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

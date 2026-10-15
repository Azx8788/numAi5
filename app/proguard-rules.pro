# ===== numAi5 proguard / R8 rules =====
# Gson 反序列化的 POJO 必须保留字段与无参构造
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod

# model / provider 数据类（被 Gson 反射读写）
-keep class io.github.numai5.model.** { *; }
-keep class io.github.numai5.provider.** { *; }
-keep class io.github.numai5.data.** { *; }

# 枚举
-keepclassmembers enum io.github.numai5.** {
    **[] $VALUES;
    public *;
}

# Gson TypeToken / JsonSerializer
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**

# 保留 BuildConfig
-keep class io.github.numai5.BuildConfig { *; }

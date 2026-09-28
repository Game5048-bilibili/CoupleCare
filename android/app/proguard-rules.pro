# 高德地图 SDK 依赖大量反射与 JNI，release 混淆时务必整体保留
-keep class com.amap.api.** { *; }
-keep class com.autonavi.** { *; }
-keep class com.loc.** { *; }
-dontwarn com.amap.api.**
-dontwarn com.autonavi.**

# Google Play Services Location
-keep class com.google.android.gms.location.** { *; }
-dontwarn com.google.android.gms.**

# 我们自己的数据模型（org.json 手动解析，不涉及反射，但保留以防万一）
-keep class net.game5048.baobao.data.model.** { *; }

# Kotlin 协程
-dontwarn kotlinx.coroutines.**

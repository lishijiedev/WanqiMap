# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# ============================================================================
# 高德 SDK：整体保留，不做裁剪与混淆
# ----------------------------------------------------------------------------
# 高德 SDK 是打包好的闭源 jar（没有 consumer rules），内部大量使用反射按类名/方法名
# 装载实现（地图渲染、导航引擎、定位、网络层都是）。R8 一旦按可达性分析裁剪或重命名，
# 就只会在运行期崩，编译期完全看不出来。所以这里按 jar 里的顶层包整体保留。
#
# 覆盖的顶层包（unzip -l 查得）：
#   com.amap.api.*      地图 / 导航 / 搜索 / 定位 的公开 API
#   com.autonavi.*      导航引擎与地图渲染内核（ae / amap / aps / base / config / extra / util）
#   com.alibaba.idst.*  语音相关内核
#   com.loc.*           定位内部实现
# ============================================================================
-keep class com.amap.** { *; }
-keep class com.autonavi.** { *; }
-keep class com.alibaba.idst.** { *; }
-keep class com.loc.** { *; }

-dontwarn com.amap.**
-dontwarn com.autonavi.**
-dontwarn com.alibaba.idst.**
-dontwarn com.loc.**

# SDK 解析注解、泛型签名与内部类信息时要用到，去掉会运行期出问题
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions

# 为了在崩溃栈里能看到行号
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ============================================================================
# 刻意**不**剥离日志（不用 -assumenosideeffects 去掉 android.util.Log）
# ----------------------------------------------------------------------------
# 现在还在真机联调阶段，logcat 里 tag `WanqimapNavi` 的「路线摘要」「全览框选」
# 是排查问题的唯一现场信息。几百行日志的运行时开销可以忽略，保留它更划算。
# ============================================================================

# 字节码优化与类名平铺
-optimizationpasses 5
-allowaccessmodification
-repackageclasses ''
-renamesourcefileattribute ''

# 保留反射与运行时注解
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,Signature,InnerClasses,EnclosingMethod

# 移除 Release 模式下的日志输出
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
}

# 本地数据模型与数据库实体保留
-keep class top.msfxp.schedule.data.model.** { *; }
-keep class top.msfxp.schedule.data.db.** { *; }

# 桌面微件与后台服务保留
-keep class * extends android.appwidget.AppWidgetProvider { *; }
-keep class top.msfxp.schedule.widget.** { *; }
-keep class top.msfxp.schedule.service.** { *; }

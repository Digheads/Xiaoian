# Xiaoian ProGuard rules
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Started by the shell scripts via app_process, never referenced from code.
-keep class com.xiaoian.app.tools.Fetch {
    public static void main(java.lang.String[]);
}
-keep class com.xiaoian.app.tools.Cat {
    public static void main(java.lang.String[]);
}

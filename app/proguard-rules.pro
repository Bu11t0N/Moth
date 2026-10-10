# gomobile calls these types and interface implementations through JNI.
-keep class go.** { *; }
-keep class io.nekohasekai.libbox.** { *; }
-keep class * implements io.nekohasekai.libbox.PlatformInterface { *; }
-keep class * implements io.nekohasekai.libbox.StringIterator { *; }
-keep class * implements io.nekohasekai.libbox.NetworkInterfaceIterator { *; }
# SnakeYAML instantiates scalar/collection helpers reflectively.
-keep class org.yaml.snakeyaml.** { *; }
# Only SafeConstructor (maps/scalars) is used. Android has no JavaBeans API;
# arbitrary Java object deserialization is deliberately unavailable.
-dontwarn java.beans.**

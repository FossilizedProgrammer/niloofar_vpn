# Native bridge methods are resolved by name from JNI.
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.multicore.vpn.tunnel.Tun2Socks { *; }

# Compose keeps what it needs through its own rules.
-dontwarn org.jetbrains.annotations.**

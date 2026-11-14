# Native bridge methods are resolved by name from JNI.
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.multicore.vpn.tunnel.Tun2Socks { *; }

# Classes the C bridges resolve with FindClass. Psiphon no longer has one: it is
# the official AAR, and gomobile's own consumer rules keep psi/go.
-keep class com.multicore.vpn.tunnel.SunAndLionBridge { *; }

# gomobile bindings: the Go side calls back into these classes by name.
-keep class go.** { *; }
-keep class mobile.** { *; }

# Compose keeps what it needs through its own rules.
-dontwarn org.jetbrains.annotations.**

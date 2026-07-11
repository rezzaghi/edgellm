# JNI resolves native methods by exact class + method name
# (Java_io_github_rezzaghi_..._LlamaBridge_nativeLoadModel).
-keepclasseswithmembernames class io.github.rezzaghi.edgellm.engine.llamacpp.LlamaBridge {
    native <methods>;
}

# Native code looks up onToken("([B)V") by name on the callback object.
-keepclassmembers class * implements io.github.rezzaghi.edgellm.engine.llamacpp.LlamaBridge$TokenCallback {
    void onToken(byte[]);
}
-keep interface io.github.rezzaghi.edgellm.engine.llamacpp.LlamaBridge$TokenCallback { *; }

# Consumer rules for the app's release build (see build.gradle).

# Called from native code by name (native_consumer.c) and nowhere in Java,
# so R8 would drop them.
-keepclassmembers class com.anland.termux.Clipboard {
    void nativeSetClipboardText(java.lang.String);
    void nativeClipListening(boolean);
    void nativeClipboardSync();
}

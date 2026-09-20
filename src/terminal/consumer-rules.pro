# JNI binds by symbol name: Java_com_termux_terminal_JNI_createSubprocess and
# friends live in libtermux.so, so the class and its native methods must keep
# their names through R8 (:app release is minifyEnabled).
-keepclasseswithmembernames,includedescriptorclasses class com.termux.terminal.JNI {
    native <methods>;
}

# The text selection handles are inflated from XML drawables and instantiated
# reflectively by the PopupWindow machinery.
-keep class com.termux.view.textselection.** { *; }

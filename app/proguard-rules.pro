# This WebRTC AAR ships no consumer rules. Its native library looks up Java
# classes/members by name, including jni_zero.JniInit during JNI_OnLoad.
# Keep the JNI boundary intact while R8 optimizes the application and UI.
-keep class org.webrtc.** { *; }
-keep class org.jni_zero.** { *; }

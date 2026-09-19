# Library-specific ProGuard rules go here.

# The download bridge is only called from JavaScript, see Downloads
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

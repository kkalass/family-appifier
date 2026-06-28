# ProGuard rules for the app-immich module.
# Keep WebView client methods intact if minifying.
-keepclassmembers class * extends android.webkit.WebViewClient {
    public *;
}
-keepclassmembers class * extends android.webkit.WebChromeClient {
    public *;
}

# WebView JavaScript bridge methods are invoked by name from page JavaScript. Keep
# @JavascriptInterface members from being removed or renamed by R8 in release builds.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# AndroidX / Material / webkit ship their own consumer-rules.pro, merged automatically.

# Методы, помеченные @JavascriptInterface, вызываются из JavaScript по имени,
# поэтому их нельзя переименовывать.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

-keep class dev.codecompiler.android.MainActivity$Bridge { *; }

-dontwarn org.chromium.**

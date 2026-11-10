# JS 桥接方法不能被混淆或移除
-keepclassmembers class dev.raaa.deeptutor.ShellBridge {
    @android.webkit.JavascriptInterface <methods>;
}

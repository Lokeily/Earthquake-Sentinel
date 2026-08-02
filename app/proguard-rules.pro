# 地震哨兵 Android App 混淆规则
# release 已开启 minify，保留全部应用类以避免反射 / 资源名查找等被误删。
-keep class com.dianguard.app.** { *; }

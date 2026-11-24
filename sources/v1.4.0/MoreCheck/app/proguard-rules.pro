# MoreCheck ProGuard/R8 rules.
# Room, Compose, WorkManager and DataStore ship their own consumer rules,
# so no manual keep rules are required for this project.

# 桌面小组件：由 Manifest 声明、系统按组件名反射实例化。R8 理论上会保留
# Manifest 引用的类，但 full mode 下对「仅在 XML 中出现」的类并不总是稳妥，
# 显式 keep 一次，避免 release 包加桌面小组件时崩溃（debug 包不复现）。
-keep class com.morecheck.ui.widget.** { *; }

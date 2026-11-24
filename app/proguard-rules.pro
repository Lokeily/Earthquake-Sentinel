# 地震哨兵 Android App 混淆规则
# ------------------------------------------------------------
# 2026-08-26 审查整改：废弃「全包 keep」（原 -keep class com.dianguard.app.** { *; }
# 等同关闭混淆与代码缩减），改为按需保留——仅保留反射 / 序列化 / 名字反射
# 依赖的成员，其余全部交由 R8 混淆 + 裁剪，显著降低逆向与包体。
#
# 依据：
#  - Manifest 中的四大组件、ViewBinding 类、各 View 构造方法已由 AGP 自动
#    生成的 aapt_rules.txt 保留，无需手写；
#  - EewVoice 通过 WarnngLevel.name 反射资源名（cnee_w_red 等）→ 需保留枚举 name；
#  - AlertActivity 通过 Intent extra 传递 QuakeRecord（implements Serializable）
#    → 事务序列化依赖字段名与类名；
#  - kotlinx/okhttp 等三方库自带 consumer 规则（META-INF/proguard），无需手动 keep。

# ---------- 1. Serializable：跨组件 Intent 传递（AlertActivity ↔ 回放） ----------
# QuakeRecord / QuakeReportSnapshot 通过 Intent extra 以 Serializable 传递
# （ReplaySetupActivity: intent.getSerializableExtra），事务序列化依赖字段名，
# 保留其类名与成员。其余应用类允许混淆，但保留成员名避免数据类字段被精简。
-keepnames class com.dianguard.app.QuakeRecord
-keepnames class com.dianguard.app.QuakeReportSnapshot
-keepclassmembers,allowobfuscation class com.dianguard.app.QuakeRecord { *; }
-keepclassmembers,allowobfuscation class com.dianguard.app.QuakeReportSnapshot { *; }

# ---------- 2. 枚举：name() 被用于资源名反射（EewVoice），不可混淆 ----------
-keepclassmembers enum com.dianguard.app.** {
    public **[] values();
    public static com.dianguard.app.** valueOf(java.lang.String);
    public final java.lang.String name();
}

# ---------- 3. 第三方库 ----------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
package com.ctf.bilisb.model

/**
 * 单个 SponsorBlock 分类的处理模式（四态，对齐 BiliSponsorSkip）。
 *
 * 之前每个分类只有一个布尔开关（`cat_*`），「自动跳 / 手动跳 / 只标记」这三个动作
 * 由两个**全局**开关（`auto_skip` / `manual_skip`）统一决定，用户无法表达
 * 「赞助自动跳、开场动画要手动确认」这类真实需求。四态把动作下沉到分类粒度。
 *
 * [persistedValue] 是**存储契约**（SharedPreferences / JSON 镜像 / Bundle 都以字符串存），
 * 一旦发布不可改：改了老用户已保存的选择会被 [fromPersisted] 判为未知并回退默认值。
 */
enum class CategoryMode(val persistedValue: String) {
    /** 完全不处理：分类被过滤掉，既不拉取也不标记。 */
    DISABLED("disabled"),

    /** 只在进度条上画标记（+ 到点 toast），不自动跳、不弹按钮。 */
    SHOW_OVERLAY("show_overlay"),

    /** 片段内浮出「跳过」按钮，由用户点按跳过。 */
    MANUAL_SKIP("manual_skip"),

    /** 进入片段自动跳过（配了倒计时就先倒数）。 */
    AUTO_SKIP("auto_skip"),
    ;

    companion object {
        /**
         * 解析持久化值；null / 空 / 未知一律回退 [default]。
         *
         * 之所以**必须有** default 而不是硬回退某个常量：读取端分两种情形 ——
         * 键存在但值损坏（外部改写、跨版本新增枚举值）与键不存在（老安装走 legacy 派生），
         * 前者要落到该分类的默认值，后者要落到 legacy 派生值，由调用方决定。
         */
        fun fromPersisted(value: String?, default: CategoryMode): CategoryMode {
            val normalized = value?.trim().orEmpty()
            if (normalized.isEmpty()) return default
            return entries.firstOrNull { it.persistedValue == normalized } ?: default
        }
    }
}

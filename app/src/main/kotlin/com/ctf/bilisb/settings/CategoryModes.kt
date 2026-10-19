package com.ctf.bilisb.settings

import com.ctf.bilisb.model.CategoryMode
import com.ctf.bilisb.model.SponsorCategories

/**
 * 分类四态模式的两条判定规则。
 *
 * 单独成文件是因为这两条规则是**迁移语义**本身：`legacyCategoryMode` 决定老安装
 * （只有 `cat_*` 布尔 + 两个全局开关）升上来时每个分类是什么模式，`effectiveMode`
 * 决定全局开关关闭时单个分类最终怎么表现。两者都在设置读取链路上，改动会同时影响
 * 老用户升级与新用户行为，因此集中在此、由 [CategoryModeMigrationTest] 钉死。
 */
object CategoryModes {

    /**
     * 老安装（没有 `category_mode_*` 键）→ 四态的派生规则。
     *
     * 顺序即优先级，**必须与四态化之前的运行时行为逐格一致**——升级时用户没有
     * 表达过任何新偏好，行为就不能变：
     *   1. 分类被关掉（`cat_* = false`）→ [CategoryMode.DISABLED]；
     *   2. 全局 manual_skip 开 → [CategoryMode.MANUAL_SKIP]；
     *   3. 全局 auto_skip 开 → [CategoryMode.AUTO_SKIP]；
     *   4. 都没有 → [CategoryMode.SHOW_OVERLAY]（保留进度条标记，不跳）。
     *
     * **manual_skip 先于 auto_skip，这一点是被旧实现钉死的**：旧
     * `SponsorBlockController` 是 `if (settings.manualSkip) { …; return }` ——
     * 手动分支提前返回，所以两个开关同时打开时实际表现是「弹按钮等确认」。
     * 设置页文案也是这个口径（`manual_skip_summary`：「点按才跳(覆盖自动跳过)」）。
     * 若反过来让 auto 优先，`auto_skip=true` + `manual_skip=true` 的老用户会升级后
     * 静默从「等我确认」变成「直接自动跳」。
     */
    fun legacyCategoryMode(enabled: Boolean, autoSkip: Boolean, manualSkip: Boolean): CategoryMode =
        when {
            !enabled -> CategoryMode.DISABLED
            manualSkip -> CategoryMode.MANUAL_SKIP
            autoSkip -> CategoryMode.AUTO_SKIP
            else -> CategoryMode.SHOW_OVERLAY
        }

    /**
     * 分类最终生效的模式 = 用户选择的模式，再受两个全局开关降级。
     *
     * 全局开关在这里**保留**（而不是删掉），是为了让老用户不改任何设置时行为不变：
     *   - `auto_skip = false` → 全部分类降级为只标记，语义就是「一个都不自动跳」；
     *   - `manual_skip = false` → 不再弹手动按钮，同样降级为只标记；
     *   - [CategoryMode.SHOW_OVERLAY] 与 [CategoryMode.DISABLED] 不受两个开关影响。
     *
     * 未知分类（不在表里）一律 [CategoryMode.DISABLED]：宁可少跳也不要对没定义过的
     * category 做动作。
     */
    fun effectiveMode(settings: SettingsSnapshot, category: String): CategoryMode {
        val base = settings.categoryModes[category] ?: return CategoryMode.DISABLED
        return when (base) {
            CategoryMode.AUTO_SKIP -> if (settings.autoSkip) base else CategoryMode.SHOW_OVERLAY
            CategoryMode.MANUAL_SKIP -> if (settings.manualSkip) base else CategoryMode.SHOW_OVERLAY
            CategoryMode.SHOW_OVERLAY, CategoryMode.DISABLED -> base
        }
    }

    /**
     * 会被拉取/展示的分类集合：模式 != DISABLED 的那些。
     *
     * 交给 [com.ctf.bilisb.net.SponsorBlockClient] 在网络层过滤 —— DISABLED 的分类
     * 连片段都不该下载，更不该占用进度条标记与面板列表。
     */
    fun enabledCategories(settings: SettingsSnapshot): Set<String> =
        SponsorCategories.ordered
            .map { it.id }
            .filterTo(linkedSetOf()) { effectiveMode(settings, it) != CategoryMode.DISABLED }
}

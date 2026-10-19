package com.ctf.bilisb.settings

import android.app.Activity
import com.ctf.bilisb.model.CategoryMode
import com.ctf.bilisb.model.SponsorCategories
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 分类四态的**迁移语义**守卫。
 *
 * 加四态时最危险的不是新功能，而是老用户升级后行为悄悄变了：他们没碰过设置，
 * 却因为新增了一组键而被判成「重新选了默认」。所以这里把两件事钉死：
 *
 *  1. [CategoryModes.legacyCategoryMode] 的真值表（老安装 → 四态）——纯函数，直接穷举；
 *  2. 编解码两条通道（prefs / Map）在「键缺失」「键存在」两种情形下的实际取值，
 *     并确保**显式的用户选择永远压过 legacy 派生**。
 *
 * Robolectric 是必须的：prefs 通道要真的 SharedPreferences。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CategoryModeMigrationTest {

    // ---------------------------------------------------------------- legacy 派生真值表

    @Test
    fun `legacy 派生 关掉的分类一律 DISABLED`() {
        assertEquals(CategoryMode.DISABLED, CategoryModes.legacyCategoryMode(false, autoSkip = true, manualSkip = true))
        assertEquals(CategoryMode.DISABLED, CategoryModes.legacyCategoryMode(false, autoSkip = false, manualSkip = false))
    }

    @Test
    fun `legacy 派生 manual_skip 优先于 auto_skip`() {
        assertEquals(CategoryMode.AUTO_SKIP, CategoryModes.legacyCategoryMode(true, autoSkip = true, manualSkip = false))
        assertEquals(CategoryMode.MANUAL_SKIP, CategoryModes.legacyCategoryMode(true, autoSkip = false, manualSkip = true))
        // 两个都开 = 弹按钮等确认。这是四态化之前的真实行为：旧 Controller 里
        // `if (settings.manualSkip) { …; return }` 提前返回，auto 分支根本走不到。
        // 也是设置页文案的口径（manual_skip_summary：「点按才跳(覆盖自动跳过)」）。
        assertEquals(CategoryMode.MANUAL_SKIP, CategoryModes.legacyCategoryMode(true, autoSkip = true, manualSkip = true))
        // 两者都关 = 只标记（片段仍被拉取，只是不跳）
        assertEquals(CategoryMode.SHOW_OVERLAY, CategoryModes.legacyCategoryMode(true, autoSkip = false, manualSkip = false))
    }

    // ---------------------------------------------------------------- prefs 通道

    @Test
    fun `老安装 无 category_mode 键 cat_intro 关掉则 intro 为 DISABLED`() {
        val prefs = prefs()
        prefs.edit().putBoolean(SettingsKeys.CAT_INTRO, false).commit()

        val snapshot = SettingsCodec.snapshotFromPreferences(prefs)

        assertEquals(CategoryMode.DISABLED, snapshot.categoryModes["intro"])
        assertFalse("intro" in snapshot.enabledCategories)
    }

    @Test
    fun `老安装 auto_skip 开启且分类全开则全部 AUTO_SKIP`() {
        val prefs = prefs()
        prefs.edit().putBoolean(SettingsKeys.AUTO_SKIP, true).commit()

        val snapshot = SettingsCodec.snapshotFromPreferences(prefs)

        // legacy 规则：manual 关（默认）时 auto 生效 → AUTO_SKIP。
        // 「两个开关都开」的另一格见 `legacy 派生 manual_skip 优先于 auto_skip`。
        assertEquals(CategoryMode.AUTO_SKIP, snapshot.categoryModes["sponsor"])
        assertEquals(CategoryMode.AUTO_SKIP, snapshot.categoryModes["selfpromo"])
        // preview 也一样 —— 规则只看 cat_* 与全局开关，不看分类语义。
        assertEquals(CategoryMode.AUTO_SKIP, snapshot.categoryModes["preview"])
        assertEquals(SponsorCategories.ordered.size, snapshot.categoryModes.size)
    }

    @Test
    fun `老安装 auto 关闭且手动开启则全部 MANUAL_SKIP`() {
        val prefs = prefs()
        prefs.edit()
            .putBoolean(SettingsKeys.AUTO_SKIP, false)
            .putBoolean(SettingsKeys.MANUAL_SKIP, true)
            .commit()

        val snapshot = SettingsCodec.snapshotFromPreferences(prefs)

        assertEquals(CategoryMode.MANUAL_SKIP, snapshot.categoryModes["sponsor"])
        assertEquals(CategoryMode.MANUAL_SKIP, snapshot.categoryModes["poi_highlight"])
    }

    @Test
    fun `老安装 两个全局都关则全部只标记`() {
        val prefs = prefs()
        prefs.edit()
            .putBoolean(SettingsKeys.AUTO_SKIP, false)
            .putBoolean(SettingsKeys.MANUAL_SKIP, false)
            .commit()

        val snapshot = SettingsCodec.snapshotFromPreferences(prefs)

        SponsorCategories.ordered.forEach { category ->
            assertEquals(category.id, CategoryMode.SHOW_OVERLAY, snapshot.categoryModes[category.id])
        }
        assertEquals(SponsorCategories.ordered.size, snapshot.enabledCategories.size)
    }

    @Test
    fun `显式的 category_mode_* 压过 legacy 派生`() {
        val prefs = prefs()
        prefs.edit()
            // legacy 侧：auto 开、intro 关 —— 派生会让 intro=DISABLED、sponsor=AUTO_SKIP
            .putBoolean(SettingsKeys.AUTO_SKIP, true)
            .putBoolean(SettingsKeys.CAT_INTRO, false)
            // 用户侧：显式选了 intro=auto_skip、sponsor=disabled
            .putString(SettingsKeys.categoryModeKey("intro"), CategoryMode.AUTO_SKIP.persistedValue)
            .putString(SettingsKeys.categoryModeKey("sponsor"), CategoryMode.DISABLED.persistedValue)
            .commit()

        val snapshot = SettingsCodec.snapshotFromPreferences(prefs)

        assertEquals(CategoryMode.AUTO_SKIP, snapshot.categoryModes["intro"])
        assertEquals(CategoryMode.DISABLED, snapshot.categoryModes["sponsor"])
        assertFalse("sponsor" in snapshot.enabledCategories)
        assertTrue("intro" in snapshot.enabledCategories)
    }

    @Test
    fun `category_mode_ 值损坏时退回该分类默认模式而不是 legacy 派生`() {
        val prefs = prefs()
        prefs.edit()
            .putBoolean(SettingsKeys.AUTO_SKIP, false)
            .putString(SettingsKeys.categoryModeKey("sponsor"), "garbage")
            .commit()

        val snapshot = SettingsCodec.snapshotFromPreferences(prefs)

        // 键在 = 用户表达过偏好 → 用 defaultModes（sponsor 默认 AUTO_SKIP），不再走 legacy。
        assertEquals(CategoryMode.AUTO_SKIP, snapshot.categoryModes["sponsor"])
    }

    /**
     * prefs 里 `category_mode_*` **类型**被外部工具改坏（存成了 Int）→ 哨兵路径。
     *
     * 与上一条的区别：上一条是「String 但值非法」，这条是「压根不是 String」，
     * prefs 通道读 getString 会抛 ClassCastException，必须转成哨兵才能落到
     * defaultModes 分支（而不是被误判成「键不存在」→ legacy 派生）。
     */
    @Test
    fun `prefs 里 category_mode 类型被改坏时仍按键存在处理`() {
        val prefs = prefs()
        prefs.edit()
            // 用 false 的 auto 让 legacy 派生给出 SHOW_OVERLAY，与 defaultModes(sponsor)=AUTO_SKIP 区分开。
            .putBoolean(SettingsKeys.AUTO_SKIP, false)
            .putInt(SettingsKeys.categoryModeKey("sponsor"), 3)
            .commit()

        val snapshot = SettingsCodec.snapshotFromPreferences(prefs)

        assertEquals(CategoryMode.AUTO_SKIP, snapshot.categoryModes["sponsor"])
    }

    /**
     * 写通道 → 读通道闭环后，**读到的模式必须与写出去的一致**，且 `cat_*` 跟着派生。
     *
     * 这条是 P3 重构（prefs 通道不再自行做 legacy 派生）的守卫：若哪天把
     * `snapshotFromPreferences` 里的模式搬运删掉，键会「消失」，所有分类会静默
     * 回落到 legacy 派生值，而 legacy 派生依赖全局开关 —— 用户显式选的模式会被吞掉。
     */
    @Test
    fun `写出的 category_mode 不会被读通道的 legacy 派生覆盖`() {
        val prefs = prefs()
        // 故意把全局开关摆成「legacy 派生会给出 AUTO_SKIP」的组合。
        prefs.edit()
            .putBoolean(SettingsKeys.AUTO_SKIP, true)
            .putBoolean(SettingsKeys.MANUAL_SKIP, false)
            .commit()
        val modes = mapOf(
            "sponsor" to CategoryMode.DISABLED,
            "intro" to CategoryMode.SHOW_OVERLAY,
        )
        SettingsCodec.writeSnapshotToPreferences(
            prefs, SettingsSnapshot.DEFAULT.copy(categoryModes = modes),
        )

        val restored = SettingsCodec.snapshotFromPreferences(prefs)

        assertEquals(CategoryMode.DISABLED, restored.categoryModes["sponsor"])
        assertEquals(CategoryMode.SHOW_OVERLAY, restored.categoryModes["intro"])
        assertFalse("sponsor" in restored.enabledCategories)
        // cat_* 与模式同步：DISABLED → false，SHOW_OVERLAY → true。
        assertEquals(false, prefs.getBoolean(SettingsKeys.CAT_SPONSOR, true))
        assertEquals(true, prefs.getBoolean(SettingsKeys.CAT_INTRO, false))
    }

    @Test
    fun `prefs 写出的快照能被读回且模式一致`() {
        val prefs = prefs()
        val modes = mapOf(
            "sponsor" to CategoryMode.MANUAL_SKIP,
            "filler" to CategoryMode.DISABLED,
            SponsorCategories.PADDING to CategoryMode.SHOW_OVERLAY,
        )
        SettingsCodec.writeSnapshotToPreferences(
            prefs, SettingsSnapshot.DEFAULT.copy(categoryModes = modes),
        )

        val restored = SettingsCodec.snapshotFromPreferences(prefs)

        assertEquals(modes["sponsor"], restored.categoryModes["sponsor"])
        assertEquals(modes["filler"], restored.categoryModes["filler"])
        assertEquals(modes[SponsorCategories.PADDING], restored.categoryModes[SponsorCategories.PADDING])
        // cat_* 也被同步写出（老工具链继续读得到）
        assertEquals(true, prefs.getBoolean(SettingsKeys.CAT_SPONSOR, false))
        assertEquals(false, prefs.getBoolean(SettingsKeys.CAT_FILLER, true))
    }

    // ---------------------------------------------------------------- Map 通道（JSON 镜像 / Bundle）

    @Test
    fun `Map 通道同样在键缺失时走 legacy 派生`() {
        val legacy = SettingsCodec.snapshotFromMap(
            mapOf(
                SettingsKeys.CAT_INTRO to false,
                SettingsKeys.AUTO_SKIP to true,
            ),
        )
        assertEquals(CategoryMode.DISABLED, legacy.categoryModes["intro"])
        assertEquals(CategoryMode.AUTO_SKIP, legacy.categoryModes["sponsor"])
    }

    @Test
    fun `Map 通道显式值压过 legacy 派生`() {
        val values = mapOf(
            SettingsKeys.AUTO_SKIP to true,
            SettingsKeys.CAT_INTRO to false,
            SettingsKeys.categoryModeKey("intro") to CategoryMode.MANUAL_SKIP.persistedValue,
        )
        val snapshot = SettingsCodec.snapshotFromMap(values)
        assertEquals(CategoryMode.MANUAL_SKIP, snapshot.categoryModes["intro"])
    }

    // ---------------------------------------------------------------- effectiveMode

    @Test
    fun `effectiveMode 在 auto_skip 关闭时把 AUTO_SKIP 降级为只标记`() {
        val settings = SettingsSnapshot.DEFAULT.copy(
            autoSkip = false,
            categoryModes = mapOf("sponsor" to CategoryMode.AUTO_SKIP),
        )
        assertEquals(CategoryMode.SHOW_OVERLAY, CategoryModes.effectiveMode(settings, "sponsor"))
    }

    @Test
    fun `effectiveMode 在 manual_skip 关闭时把 MANUAL_SKIP 降级为只标记`() {
        val settings = SettingsSnapshot.DEFAULT.copy(
            manualSkip = false,
            categoryModes = mapOf("intro" to CategoryMode.MANUAL_SKIP),
        )
        assertEquals(CategoryMode.SHOW_OVERLAY, CategoryModes.effectiveMode(settings, "intro"))
    }

    @Test
    fun `effectiveMode 不动 SHOW_OVERLAY 与 DISABLED`() {
        val settings = SettingsSnapshot.DEFAULT.copy(
            autoSkip = false,
            manualSkip = false,
            categoryModes = mapOf(
                "preview" to CategoryMode.SHOW_OVERLAY,
                "filler" to CategoryMode.DISABLED,
            ),
        )
        assertEquals(CategoryMode.SHOW_OVERLAY, CategoryModes.effectiveMode(settings, "preview"))
        assertEquals(CategoryMode.DISABLED, CategoryModes.effectiveMode(settings, "filler"))
    }

    @Test
    fun `effectiveMode 全局开关都开时原样返回`() {
        val settings = SettingsSnapshot.DEFAULT.copy(
            autoSkip = true,
            manualSkip = true,
            categoryModes = mapOf(
                "sponsor" to CategoryMode.AUTO_SKIP,
                "intro" to CategoryMode.MANUAL_SKIP,
            ),
        )
        assertEquals(CategoryMode.AUTO_SKIP, CategoryModes.effectiveMode(settings, "sponsor"))
        assertEquals(CategoryMode.MANUAL_SKIP, CategoryModes.effectiveMode(settings, "intro"))
    }

    @Test
    fun `effectiveMode 对未知或未登记的分类一律 DISABLED`() {
        val settings = SettingsSnapshot.DEFAULT.copy(categoryModes = mapOf("sponsor" to CategoryMode.AUTO_SKIP))
        assertEquals(CategoryMode.DISABLED, CategoryModes.effectiveMode(settings, "brand_new_category"))
        // 手写快照(categoryModes 缺项)也不能因为「没配置」就默认自动跳。
        assertEquals(CategoryMode.DISABLED, CategoryModes.effectiveMode(settings, "intro"))
    }

    @Test
    fun `enabledCategories 排除 DISABLED 的分类`() {
        val settings = SettingsSnapshot.DEFAULT.copy(
            categoryModes = SponsorCategories.ordered.associate { it.id to CategoryMode.AUTO_SKIP } +
                mapOf("filler" to CategoryMode.DISABLED),
        )
        val enabled = CategoryModes.enabledCategories(settings)
        assertFalse("filler" in enabled)
        assertEquals(SponsorCategories.ordered.size - 1, enabled.size)
    }

    private fun prefs() = Robolectric.buildActivity(Activity::class.java).setup().get()
        .getSharedPreferences("category-mode-migration", 0)
        .apply { edit().clear().commit() }
}

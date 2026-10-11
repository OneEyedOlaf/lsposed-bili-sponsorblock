package com.ctf.bilisb.unlock

/**
 * 东南亚（Bstar/TH）受限番剧 ViewReply 响应重构器。
 *
 * 当主站 viewunite.v1.View 返回 -404 错误时，宿主会展示「视频不见了哦」。
 * 本重构器以真实的受限 ViewReply 响应（view_reply_template.bin，包含完整的 Arc、Owner、
 * ReqUser、PageCtrl 等骨架）为模板，使用 [WireSplice] 在二进制 wire 级别无损切片并替换：
 *  1. supplement(6) → ViewPgcAny.ogvData(1)：
 *     - media_id(1) = 目标 seasonId
 *     - season_id(2) = 目标 seasonId
 *     - total_ep(14)
 *     - title(24)
 *     - cover(20) / square_cover(21)
 *     - share_url(22) / short_link(23)
 *     - publish(10) 发布信息
 *     - 清除 field 28 / 29 遗留的辉夜姬限免/加更券受限标签
 *     - has_can_play_ep(27) = 1
 *     - rights(5) = allow_download(1)=1, can_watch(3)=1, area_limit(4)=0, only_vip_download(15)=0, new_allow_download(16)=1
 *  2. tab(5) → tab_module(1){tab_type=1} → introduction(2) → modules(2)：
 *     - Module 2（标题）：更新 subfield 1 为季标题
 *     - Module 1（简介 OgvIntroduction）：动态注入 overview(12), alias(11), styles(10), areas(9), season_type_name(8)
 *     - 过滤旧 Module 13 (选集)、Module 21 (系列切换器)、Module 9 (旧声优制作)、Module 28 (旧漫画推荐)
 *     - 注入正季选集区块 Module 13（SectionData），紧随 Module 1 之后
 *  3. arc(1)：
 *     - title(9) = 季标题
 *     - cover(8) = 季封面
 *     - aid(1) / cid(2) = 首集 aid / cid（若有）
 *  4. page_ctrl(10)：
 *     - 将 daid 和 season_id 替换为目标 seasonId
 *
 * 经由宿主类 `ViewReply.parseFrom` 实例化后返回，使宿主详情页与选集列表完整渲染。
 */
object ThViewReplyReconstructor {

    private val templateBytes: ByteArray by lazy {
        val stream = javaClass.classLoader?.getResourceAsStream("unlock/view_reply_template.bin")
            ?: error("unlock/view_reply_template.bin template missing")
        stream.use { it.readBytes() }
    }

    fun reconstruct(season: ThSeasonDetail): ByteArray {
        val template = templateBytes

        // 1. 替换 supplement (field 6) 中的 ogvData
        val withSupplement = WireSplice.transformMessage(template, 6) { supplement ->
            WireSplice.transformMessage(supplement, 2) { pgcAnyValue ->
                WireSplice.transformMessage(pgcAnyValue, 1) { ogvData ->
                    rebuildOgvData(ogvData, season)
                }
            }
        }

        // 2. 替换 tab (field 5) 中的 modules
        val withTab = WireSplice.transformMessage(withSupplement, 5) { tab ->
            rebuildTab(tab, season)
        }

        // 3. 替换 arc (field 1)
        val withArc = WireSplice.transformMessage(withTab, 1) { arc ->
            rebuildArc(arc, season)
        }

        // 4. 替换 page_ctrl (field 10) 中的 season_id 和 daid
        val withPageCtrl = rebuildPageCtrl(withArc, season)

        return withPageCtrl
    }

    private fun rebuildOgvData(ogvData: ByteArray, season: ThSeasonDetail): ByteArray {
        val sidLong = season.seasonId.toLong()
        var updated = setVarints(
            ogvData,
            mapOf(
                1 to sidLong,
                2 to sidLong,
                27 to 1L,
            ),
        )
        val total = (if (season.total > 0) season.total else season.episodes.size).toLong()
        if (total > 0) {
            updated = setVarints(updated, mapOf(14 to total))
        }
        if (season.title.isNotEmpty()) {
            updated = updateStringField(updated, 24, season.title)
        }
        if (season.cover.isNotEmpty()) {
            updated = updateStringField(updated, 20, season.cover)
            updated = updateStringField(updated, 21, season.cover)
        }
        updated = updateStringField(updated, 22, "https://www.bilibili.tv/en/play/${season.seasonId}")
        updated = updateStringField(updated, 23, "https://b23.tv/ss${season.seasonId}")

        // publish(10)
        if (season.publishDateShow.isNotEmpty() || season.timeLengthShow.isNotEmpty()) {
            updated = updateSubMessage(updated, 10) { publish ->
                var pub = publish
                if (season.publishDateShow.isNotEmpty()) {
                    pub = updateStringField(pub, 1, season.publishDateShow)
                    pub = updateStringField(pub, 2, season.publishDateShow)
                    pub = updateStringField(pub, 6, season.publishDateShow)
                }
                if (season.timeLengthShow.isNotEmpty()) {
                    pub = updateStringField(pub, 7, season.timeLengthShow)
                    pub = updateStringField(pub, 9, season.timeLengthShow)
                }
                pub = setVarints(pub, mapOf(3 to 1L, 4 to 1L))
                pub
            }
        }

        // rights(5)
        updated = updateSubMessage(updated, 5) { rights ->
            setVarints(rights, mapOf(1 to 1L, 3 to 1L, 4 to 0L, 15 to 0L, 16 to 1L))
        }

        // 清理模板中遗留的旧辉夜姬限免/加更券受限标签（field 28, 29）
        val cleaned = WireSplice.emit(WireSplice.parse(updated).filterNot { it.field == 28 || it.field == 29 })
        return cleaned
    }

    private fun rebuildTab(tab: ByteArray, season: ThSeasonDetail): ByteArray {
        return WireSplice.transformMessage(tab, 1) { tabModule ->
            val tabType = WireSplice.parse(tabModule).firstOrNull()
                ?.takeIf { it.field == 1 && it.wireType == 0 }?.varint()
            if (tabType != 1L) {
                tabModule
            } else {
                WireSplice.transformMessage(tabModule, 2) { introduction ->
                    val introElems = WireSplice.parse(introduction)
                    val filteredElems = mutableListOf<WireSplice.Elem>()
                    for (e in introElems) {
                        if (e.field == 2 && e.wireType == 2) {
                            val mtype = WireSplice.parse(e.payload()).firstOrNull()
                                ?.takeIf { it.field == 1 && it.wireType == 0 }?.varint()
                            // 过滤旧 Module 13 (选集)、Module 21 (系列切换器)、Module 9 (旧声优制作)、Module 28 (旧漫画推荐)
                            if (mtype == 13L || mtype == 21L || mtype == 9L || mtype == 28L) continue
                            if (mtype == 2L && season.title.isNotEmpty()) {
                                val patchedM2 = patchModule2Title(e.payload(), season.title)
                                filteredElems.add(WireSplice.Elem(2, 2, WireSplice.message(2, patchedM2)))
                                continue
                            }
                            if (mtype == 1L) {
                                val patchedM1 = patchModule1Introduction(e.payload(), season)
                                filteredElems.add(WireSplice.Elem(2, 2, WireSplice.message(2, patchedM1)))
                                continue
                            }
                        }
                        filteredElems.add(e)
                    }

                    // 构造新的 Module 13 (选集 SectionData)
                    val module13 = ViewTabHook.buildSectionModuleBytes(season.seasonId, season.episodes)

                    // 插入到 Module 1 之后（紧跟在介绍模块后面）
                    var insertAt = filteredElems.size
                    for ((idx, e) in filteredElems.withIndex()) {
                        if (e.field == 2 && e.wireType == 2) {
                            val mtype = WireSplice.parse(e.payload()).firstOrNull()
                                ?.takeIf { it.field == 1 && it.wireType == 0 }?.varint()
                            if (mtype == 1L) {
                                insertAt = idx + 1
                                break
                            }
                        }
                    }
                    filteredElems.add(insertAt, WireSplice.Elem(2, 2, WireSplice.message(2, module13)))
                    WireSplice.emit(filteredElems)
                }
            }
        }
    }

    private fun patchModule2Title(modBytes: ByteArray, title: String): ByteArray {
        return WireSplice.transformMessage(modBytes, 6) { titleData ->
            updateStringField(titleData, 1, title)
        }
    }

    private fun patchModule1Introduction(modBytes: ByteArray, season: ThSeasonDetail): ByteArray {
        return updateSubMessage(modBytes, 2) { ogvIntro ->
            var updated = ogvIntro
            if (season.evaluate.isNotEmpty()) {
                updated = updateStringField(updated, 12, season.evaluate) // overview
            }
            if (season.originName.isNotEmpty()) {
                updated = updateStringField(updated, 11, season.originName) // alias
            }
            if (season.styles.isNotEmpty()) {
                updated = updateStringField(updated, 10, season.styles) // styles
            }
            if (season.areas.isNotEmpty()) {
                updated = updateStringField(updated, 9, season.areas) // areas
            }
            val typeName = season.typeName.ifEmpty { "番剧" }
            updated = updateStringField(updated, 8, typeName) // season_type_name
            updated
        }
    }

    private fun rebuildArc(arc: ByteArray, season: ThSeasonDetail): ByteArray {
        var updated = arc
        if (season.title.isNotEmpty()) {
            updated = updateStringField(updated, 9, season.title)
        }
        if (season.cover.isNotEmpty()) {
            updated = updateStringField(updated, 8, season.cover)
        }
        val firstEp = season.episodes.firstOrNull()
        if (firstEp != null) {
            val varints = mutableMapOf<Int, Long>()
            if (firstEp.aid > 0) varints[1] = firstEp.aid
            if (firstEp.cid > 0) varints[2] = firstEp.cid
            if (varints.isNotEmpty()) {
                updated = setVarints(updated, varints)
            }
        }
        return updated
    }

    private fun rebuildPageCtrl(bytes: ByteArray, season: ThSeasonDetail): ByteArray {
        val elems = WireSplice.parse(bytes)
        val sidStr = season.seasonId.toString()
        val updated = elems.map { e ->
            if (e.field == 10 && e.wireType == 2) {
                val entryElems = WireSplice.parse(e.payload())
                val keyElem = entryElems.firstOrNull { it.field == 1 && it.wireType == 2 }
                val key = keyElem?.let { String(it.payload(), Charsets.UTF_8) }
                if (key == "season_id" || key == "daid") {
                    val newEntryBytes = entryElems.filterNot { it.field == 2 }.toMutableList().apply {
                        add(WireSplice.Elem(2, 2, WireSplice.message(2, sidStr.toByteArray(Charsets.UTF_8))))
                    }
                    WireSplice.Elem(10, 2, WireSplice.message(10, WireSplice.emit(newEntryBytes)))
                } else {
                    e
                }
            } else {
                e
            }
        }
        return WireSplice.emit(updated)
    }

    private fun updateStringField(bytes: ByteArray, field: Int, value: String): ByteArray {
        val elems = WireSplice.parse(bytes).filterNot { it.field == field }
        val newElem = WireSplice.Elem(field, 2, WireSplice.message(field, value.toByteArray(Charsets.UTF_8)))
        return WireSplice.emit(elems + newElem)
    }

    private fun updateSubMessage(bytes: ByteArray, field: Int, transform: (ByteArray) -> ByteArray): ByteArray {
        return if (WireSplice.firstMessage(bytes, field) != null) {
            WireSplice.transformMessage(bytes, field, transform)
        } else {
            bytes + WireSplice.message(field, transform(byteArrayOf()))
        }
    }

    private fun setVarints(bytes: ByteArray, values: Map<Int, Long>): ByteArray {
        val unchanged = WireSplice.emit(WireSplice.parse(bytes).filterNot { it.field in values })
        return values.entries.fold(unchanged) { out, (field, value) ->
            out + WireSplice.varintElem(field, value)
        }
    }
}

package com.ctf.bilisb.unlock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 东南亚（Bstar/TH）受限番剧 404 响应重建单元测试。
 */
class ThSeasonViewReplyReconstructTest {

    private val seasonJson: String by lazy {
        javaClass.classLoader!!.getResourceAsStream("unlock/th-season-1030026.json")!!
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    @Test
    fun `parseThSeasonDetail 能够正确解析 Bstar modules 剧集结构与元数据`() {
        val detail = SeasonParser.parseThSeasonDetail(seasonJson)
        assertNotNull("Season detail 不应为 null", detail)
        assertEquals(1030026, detail!!.seasonId)
        assertEquals("斗神姬", detail.title)
        assertTrue(detail.cover.contains("bstarstatic.com"))
        assertEquals(12, detail.episodes.size)
        assertEquals("斗神姬", detail.originName)
        assertEquals("原创 战斗 科幻 机战", detail.styles)
        assertEquals("中国大陆", detail.areas)
        assertEquals("动画", detail.typeName)
        assertEquals("2021/10/11 开播", detail.publishDateShow)
        assertEquals("已完结", detail.timeLengthShow)
        assertTrue(detail.evaluate.startsWith("在遥远的过去"))

        val ep1 = detail.episodes[0]
        assertEquals(10871139L, ep1.epId)
        assertEquals("1", ep1.title)
        assertEquals("斗神机", ep1.longTitle)
        assertEquals("第1话 斗神机", ep1.showTitle())
        assertEquals("", ep1.badge)

        // status=13 的剧集自动补齐 VIP 徽标
        val ep2 = detail.episodes[1]
        assertEquals(10871259L, ep2.epId)
        assertEquals(13, ep2.status)
        assertEquals("VIP", ep2.badge)
        assertEquals(1, ep2.badgeType)
    }

    @Test
    fun `parseFlatEpisodes 兜底支持 Bstar modules 形态`() {
        val eps = SeasonParser.parseFlatEpisodes(seasonJson)
        assertEquals(12, eps.size)
        assertEquals(10871139L, eps[0].epId)
    }

    @Test
    fun `ThViewReplyReconstructor 重建产物 wire 字段验证`() {
        val detail = SeasonParser.parseThSeasonDetail(seasonJson)!!
        val bytes = ThViewReplyReconstructor.reconstruct(detail)
        assertTrue("重建字节不应为空", bytes.isNotEmpty())

        // 1. 验证 supplement (6) 中的 ogvData
        val supp = WireSplice.firstMessage(bytes, 6)
        assertNotNull("supplement 必须存在", supp)
        val pgc = WireSplice.firstMessage(supp!!, 2)
        assertNotNull("pgc 载荷必须存在", pgc)
        val ogv = WireSplice.firstMessage(pgc!!, 1)
        assertNotNull("ogvData 必须存在", ogv)

        val ogvElems = WireSplice.parse(ogv!!)
        val mediaId = ogvElems.first { it.field == 1 }.varint()
        assertEquals(1030026L, mediaId)
        val sid = ogvElems.first { it.field == 2 }.varint()
        assertEquals(1030026L, sid)
        val title = ogvElems.first { it.field == 24 }.payload().toString(Charsets.UTF_8)
        assertEquals("斗神姬", title)
        val shareUrl = ogvElems.first { it.field == 22 }.payload().toString(Charsets.UTF_8)
        assertEquals("https://www.bilibili.tv/en/play/1030026", shareUrl)
        val shortLink = ogvElems.first { it.field == 23 }.payload().toString(Charsets.UTF_8)
        assertEquals("https://b23.tv/ss1030026", shortLink)
        val totalEp = ogvElems.first { it.field == 14 }.varint()
        assertEquals(12L, totalEp)
        val hasCanPlay = ogvElems.first { it.field == 27 }.varint()
        assertEquals(1L, hasCanPlay)

        // 验证 field 28, 29 已被彻底清除
        assertTrue("field 28 必须被清除", ogvElems.none { it.field == 28 })
        assertTrue("field 29 必须被清除", ogvElems.none { it.field == 29 })

        // 验证 publish (10)
        val pubBytes = ogvElems.first { it.field == 10 }.payload()
        val pubElems = WireSplice.parse(pubBytes)
        val pubDateShow = pubElems.firstOrNull { it.field == 6 }?.payload()?.toString(Charsets.UTF_8)
        assertEquals("2021/10/11 开播", pubDateShow)
        val timeLengthShow = pubElems.firstOrNull { it.field == 7 }?.payload()?.toString(Charsets.UTF_8)
        assertEquals("已完结", timeLengthShow)

        // 2. 验证 tab (5) 中的 modules
        val tab = WireSplice.firstMessage(bytes, 5)
        assertNotNull("tab 必须存在", tab)
        val tm = WireSplice.allMessages(tab!!, 1).first { tm ->
            WireSplice.parse(tm).firstOrNull()?.takeIf { it.field == 1 && it.wireType == 0 }?.varint() == 1L
        }
        val intro = WireSplice.firstMessage(tm, 2)
        assertNotNull("intro 必须存在", intro)
        val modules = WireSplice.allMessages(intro!!, 2)

        // 验证旧 Module 21 (系列)、Module 9 (旧声优)、Module 28 (旧漫画推荐) 全部被过滤
        assertTrue("Module 21 应被过滤", modules.none { WireSplice.parse(it).first().varint() == 21L })
        assertTrue("Module 9 应被过滤", modules.none { WireSplice.parse(it).first().varint() == 9L })
        assertTrue("Module 28 应被过滤", modules.none { WireSplice.parse(it).first().varint() == 28L })

        // 验证 Module 1 (简介) 动态注入了目标番剧元数据
        val m1 = modules.firstOrNull { WireSplice.parse(it).first().varint() == 1L }
        assertNotNull("Module 1 (简介) 必须存在", m1)
        val ogvIntroBytes = WireSplice.firstMessage(m1!!, 2)
        assertNotNull("OgvIntroduction 载荷必须存在", ogvIntroBytes)
        val introElems = WireSplice.parse(ogvIntroBytes!!)
        val overview = introElems.firstOrNull { it.field == 12 }?.payload()?.toString(Charsets.UTF_8)
        assertEquals(detail.evaluate, overview)
        val alias = introElems.firstOrNull { it.field == 11 }?.payload()?.toString(Charsets.UTF_8)
        assertEquals("斗神姬", alias)
        val styles = introElems.firstOrNull { it.field == 10 }?.payload()?.toString(Charsets.UTF_8)
        assertEquals("原创 战斗 科幻 机战", styles)
        val areas = introElems.firstOrNull { it.field == 9 }?.payload()?.toString(Charsets.UTF_8)
        assertEquals("中国大陆", areas)
        val typeName = introElems.firstOrNull { it.field == 8 }?.payload()?.toString(Charsets.UTF_8)
        assertEquals("动画", typeName)

        // 验证 Module 13 存在且紧随 Module 1 之后
        val m1Idx = modules.indexOfFirst { WireSplice.parse(it).first().varint() == 1L }
        val m13Idx = modules.indexOfFirst { WireSplice.parse(it).first().varint() == 13L }
        assertTrue("Module 13 应在 Module 1 之后", m13Idx > m1Idx)
        val m13 = modules[m13Idx]
        val sd = WireSplice.firstMessage(m13, 12)
        assertNotNull("SectionData 必须存在", sd)
        val eps = WireSplice.allMessages(sd!!, 7)
        assertEquals(12, eps.size)
        // 验证第 2 集 (VIP) 的 ViewEpisode 包含 badge_info (field 4)
        val ep2BadgeInfo = WireSplice.firstMessage(eps[1], 4)
        assertNotNull("VIP 剧集必须写入 badge_info(4)", ep2BadgeInfo)
        val badgeText = WireSplice.parse(ep2BadgeInfo!!).firstOrNull { it.field == 1 }?.payload()?.toString(Charsets.UTF_8)
        assertEquals("VIP", badgeText)

        // 验证 Module 2 标题更新为 "斗神姬"
        val m2 = modules.firstOrNull { WireSplice.parse(it).first().varint() == 2L }
        assertNotNull("Module 2 必须存在", m2)
        val f6 = WireSplice.firstMessage(m2!!, 6)
        assertNotNull("Module 2 field 6 必须存在", f6)
        val m2Title = WireSplice.parse(f6!!).first { it.field == 1 }.payload().toString(Charsets.UTF_8)
        assertEquals("斗神姬", m2Title)

        // 3. 验证 arc (1) 中的标题
        val arc = WireSplice.firstMessage(bytes, 1)
        assertNotNull("arc 必须存在", arc)
        val arcTitle = WireSplice.parse(arc!!).first { it.field == 9 }.payload().toString(Charsets.UTF_8)
        assertEquals("斗神姬", arcTitle)

        // 4. 验证 page_ctrl (10) 中 daid 与 season_id
        val pageCtrlElems = WireSplice.parse(bytes).filter { it.field == 10 && it.wireType == 2 }
        val pageCtrlMap = pageCtrlElems.associate { e ->
            val entry = WireSplice.parse(e.payload())
            val k = entry.firstOrNull { it.field == 1 }?.payload()?.toString(Charsets.UTF_8).orEmpty()
            val v = entry.firstOrNull { it.field == 2 }?.payload()?.toString(Charsets.UTF_8).orEmpty()
            k to v
        }
        assertEquals("1030026", pageCtrlMap["daid"])
        assertEquals("1030026", pageCtrlMap["season_id"])
    }

    @Test
    fun `PlayurlParser 解析 Bstar stream_list 与 dash_audio`() {
        val bstarPlayurlJson = """
        {
            "code": 0,
            "message": "success",
            "data": {
                "video_info": {
                    "quality": 32,
                    "format": "flv",
                    "timelength": 1440000,
                    "stream_list": [
                        {
                            "stream_info": {
                                "quality": 32,
                                "format": "flv",
                                "description": "480P 清晰"
                            },
                            "dash_video": {
                                "base_url": "https://upos-bstar.bilivideo.com/video_480.mp4",
                                "backup_url": ["https://backup.bilivideo.com/video_480.mp4"],
                                "bandwidth": 500000,
                                "codecid": 7,
                                "md5": "abcd480",
                                "size": 1234567
                            }
                        },
                        {
                            "stream_info": {
                                "quality": 16,
                                "format": "flv",
                                "description": "360P 流畅"
                            },
                            "dash_video": {
                                "base_url": "https://upos-bstar.bilivideo.com/video_360.mp4",
                                "bandwidth": 300000,
                                "codecid": 7,
                                "md5": "abcd360",
                                "size": 987654
                            }
                        }
                    ],
                    "dash_audio": [
                        {
                            "id": 30280,
                            "base_url": "https://upos-bstar.bilivideo.com/audio.m4a",
                            "bandwidth": 128000,
                            "codecid": 0,
                            "md5": "abcdaudio",
                            "size": 500000
                        }
                    ]
                }
            }
        }
        """.trimIndent()

        val parsed = PlayurlParser.parse(bstarPlayurlJson)
        assertNotNull("PlayurlData 不应为 null", parsed)
        assertEquals(32, parsed!!.quality)
        assertEquals(2, parsed.videos.size)
        assertEquals(1, parsed.audios.size)
        assertEquals("https://upos-bstar.bilivideo.com/video_480.mp4", parsed.videos[0].baseUrl)
        assertEquals("480P 清晰", parsed.videos[0].meta?.description)
        assertEquals("https://upos-bstar.bilivideo.com/audio.m4a", parsed.audios[0].baseUrl)
    }

    @Test
    fun `ViewTabHook buildOgvIntroInfoVo 能够通过反射构造详情浮层模型`() {
        val detail = SeasonParser.parseThSeasonDetail(seasonJson)!!.copy(
            alias = "Ancient Girl's Frame",
            actorInfo = "李星澜：张琦",
            staffInfo = "出品：bilibili",
        )
        val vo = ViewTabHook.buildOgvIntroInfoVo(javaClass.classLoader!!, detail)
            as com.bilibili.ship.theseus.ogv.intro.floatlayer.OgvIntroInfoVo
        assertEquals(1030026L, vo.seasonId)
        assertEquals(1030026L, vo.mediaId)
        assertEquals("斗神姬", vo.mediaTitle)
        assertEquals("动画", vo.typeName)
        assertEquals("Ancient Girl's Frame", vo.alias)
        assertEquals("斗神姬", vo.originName)
        assertEquals(1, vo.areas.size)
        assertEquals("中国大陆", vo.areas[0].name)
        assertEquals(4, vo.styles.size)
        assertEquals("原创", vo.styles[0].name)
        assertEquals("2021/10/11 开播", vo.publish.releaseDateShow)
        assertEquals("已完结", vo.publish.timeLengthShow)
        assertTrue(vo.publish.isStarted)
        assertTrue(vo.publish.isFinish)
        assertEquals("角色声优", vo.actor?.title)
        assertEquals("李星澜：张琦", vo.actor?.info)
        assertEquals("制作信息", vo.staff?.title)
        assertEquals("出品：bilibili", vo.staff?.info)
        assertTrue(vo.evaluate.startsWith("在遥远的过去"))
    }
}


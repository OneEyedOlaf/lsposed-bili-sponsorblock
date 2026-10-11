package com.ctf.bilisb.unlock

import org.json.JSONObject

/**
 * 漫游服务器返回的经典 playurl JSON → 中间模型。纯 JVM，可单测。
 *
 * 映射口径与参考实现的 toVideoInfo 一致（协议事实）：DASH 形态取
 * `dash.video[]`（按 preferCodec 过滤出可用的全集）与 `dash.audio[]`；
 * 字段名是宿主 VodInfo/Stream/DashVideo 的 wire 语义。
 */
data class DashTrack(
    val id: Int,
    val baseUrl: String,
    val backupUrls: List<String>,
    val bandwidth: Int,
    val codecid: Int,
    val md5: String,
    val size: Long,
    /** 该流的 stream_info 元数据（真实响应每条 Stream 都带；缺失会被播放器选流逻辑忽略）。 */
    val meta: StreamMeta? = null,
)

/** Stream 子消息 stream_info 的元数据（字段号实测，见 bilisb_unlock.proto）。 */
data class StreamMeta(
    val quality: Int,
    val format: String,
    val description: String,
    val newDescription: String,
)

data class PlayurlData(
    val quality: Int,
    val format: String,
    val timelength: Long,
    val videoCodecid: Int,
    /** dash.video 轨（按 codecid 过滤后的优选集，含全部清晰度）。 */
    val videos: List<DashTrack>,
    /** dash.audio 轨。 */
    val audios: List<DashTrack>,
    /** support_formats: quality → 描述元数据（stream_info 构建用）。 */
    val formats: Map<Int, JSONObject> = emptyMap(),
)

object PlayurlParser {

    /**
     * 解析经典 playurl JSON。`code != 0` 或缺 dash 时返回 null（调用方降级放行）。
     * 兼容 kghost 形态：外层可能包一层 `result`。
     */
    fun parse(content: String, preferCodecId: Int? = null): PlayurlData? = runCatching {
        var json = JSONObject(content)
        json.opt("result")?.let { result ->
            if (result !is String) json = json.getJSONObject("result")
        }
        if (json.optInt("code", 0) != 0) return null
        val dash = json.optJSONObject("dash")
        if (dash == null) {
            val vinfo = json.optJSONObject("data")?.optJSONObject("video_info")
                ?: json.optJSONObject("video_info")
            if (vinfo != null) {
                val streamList = vinfo.optJSONArray("stream_list")
                val formatMap = LinkedHashMap<Int, JSONObject>()
                val videos = mutableListOf<DashTrack>()
                if (streamList != null) {
                    for (i in 0 until streamList.length()) {
                        val s = streamList.optJSONObject(i) ?: continue
                        val dv = s.optJSONObject("dash_video") ?: continue
                        val baseUrl = dv.optString("base_url")
                        if (baseUrl.isEmpty()) continue
                        val sinfo = s.optJSONObject("stream_info") ?: JSONObject()
                        val qn = sinfo.optInt("quality", 0)
                        val desc = sinfo.optString("description")
                        val newDesc = sinfo.optString("new_description", desc)
                        val bkList = mutableListOf<String>()
                        val bk = dv.optJSONArray("backup_url")
                        if (bk != null) {
                            for (j in 0 until bk.length()) bk.optString(j)?.let { bkList.add(it) }
                        }
                        val meta = StreamMeta(
                            quality = qn,
                            format = sinfo.optString("format", "flv"),
                            description = desc,
                            newDescription = newDesc,
                        )
                        videos.add(
                            DashTrack(
                                id = qn,
                                baseUrl = baseUrl,
                                backupUrls = bkList,
                                bandwidth = dv.optInt("bandwidth"),
                                codecid = dv.optInt("codecid", 7),
                                md5 = dv.optString("md5"),
                                size = dv.optLong("size"),
                                meta = meta,
                            ),
                        )
                        formatMap[qn] = sinfo
                    }
                }
                val audios = mutableListOf<DashTrack>()
                val audioArr = vinfo.optJSONArray("dash_audio")
                if (audioArr != null) {
                    for (i in 0 until audioArr.length()) {
                        val a = audioArr.optJSONObject(i) ?: continue
                        val baseUrl = a.optString("base_url")
                        if (baseUrl.isEmpty()) continue
                        val bkList = mutableListOf<String>()
                        val bk = a.optJSONArray("backup_url")
                        if (bk != null) {
                            for (j in 0 until bk.length()) bk.optString(j)?.let { bkList.add(it) }
                        }
                        audios.add(
                            DashTrack(
                                id = a.optInt("id", 30280),
                                baseUrl = baseUrl,
                                backupUrls = bkList,
                                bandwidth = a.optInt("bandwidth"),
                                codecid = a.optInt("codecid", 0),
                                md5 = a.optString("md5"),
                                size = a.optLong("size"),
                            ),
                        )
                    }
                }
                if (videos.isNotEmpty()) {
                    val quality = vinfo.optInt("quality", videos.first().id)
                    val timelength = vinfo.optLong("timelength", 0L)
                    return@runCatching PlayurlData(
                        quality = quality,
                        format = "flv",
                        timelength = timelength,
                        videoCodecid = videos.firstOrNull()?.codecid ?: 7,
                        videos = videos,
                        audios = audios,
                        formats = formatMap,
                    )
                }
            }
            return null
        }

        fun tracks(key: String): List<DashTrack> {
            val arr = dash.optJSONArray(key) ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val t = arr.optJSONObject(i) ?: return@mapNotNull null
                DashTrack(
                    id = t.optInt("id"),
                    baseUrl = t.optString("base_url"),
                    backupUrls = buildList {
                        val bk = t.optJSONArray("backup_url")
                        for (j in 0 until (bk?.length() ?: 0)) bk?.optString(j)?.let { add(it) }
                    },
                    bandwidth = t.optInt("bandwidth"),
                    codecid = t.optInt("codecid"),
                    md5 = t.optString("md5"),
                    size = t.optLong("size"),
                )
            }
        }

        // support_formats: quality → 描述元数据（stream_info / qn_panel 用）。
        // LinkedHashMap 保序:面板条目按服务器下发的顺序展示（通常高→低）。
        val formatMap = LinkedHashMap<Int, JSONObject>()
        val fmtArr = json.optJSONArray("support_formats")
        if (fmtArr != null) {
            for (i in 0 until fmtArr.length()) {
                val f = fmtArr.optJSONObject(i) ?: continue
                formatMap[f.optInt("quality")] = f
            }
        }
        val videos = tracks("video").map { t ->
            val f = formatMap[t.id]
            if (f == null) t else t.copy(
                meta = StreamMeta(
                    quality = f.optInt("quality", t.id),
                    format = f.optString("codecs", json.optString("format")),
                    description = f.optString("new_description"),
                    newDescription = f.optString("display_desc", f.optString("new_description")),
                ),
            )
        }
        val filtered = preferCodecId?.let { prefer ->
            videos.filter { it.codecid == prefer }
                .takeIf { picked -> picked.map { it.id }.containsAll(videos.map { it.id }.toSet()) }
        } ?: videos

        PlayurlData(
            quality = json.optInt("quality"),
            format = json.optString("format"),
            timelength = json.optLong("timelength"),
            videoCodecid = json.optInt("video_codecid"),
            videos = filtered.ifEmpty { videos },
            audios = tracks("audio"),
            formats = formatMap,
        )
    }.getOrNull()
}

/** 选集面板的剧集条目（view.v1.ViewEpisode / viewunite.common.ViewEpisode 的字段语义）。 */
data class SeasonEpisode(
    val epId: Long,
    val badge: String,
    val badgeType: Int,
    val duration: Long,
    val status: Int,
    val cover: String,
    val aid: Long,
    val title: String,
    val longTitle: String,
    val cid: Long,
    val epIndex: Int,
    /** 缓存页原生每集都带的展示/播放元数据；来源 CN JSON 的 bvid/link/pub_time。 */
    val bvid: String = "",
    val link: String = "",
    val pubTime: Long = 0,
) {
    /**
     * 宿主卡片主标题（实拍模板：title 为数字序号时 = `第N话 <long_title>`；
     * PV/特别篇等非数字序号直接用 long_title，再退 title）。
     */
    fun showTitle(): String = when {
        title.toIntOrNull() != null && longTitle.isNotEmpty() -> "第${title}话 $longTitle"
        longTitle.isNotEmpty() -> longTitle
        else -> title
    }
}

/** 选集分区（viewunite.common.SectionData 的字段语义）。 */
data class SeasonSection(
    val id: Int,
    val sectionId: Int,
    val title: String,
    val type: Int,
    val episodes: List<SeasonEpisode>,
)

/** 东南亚（Bstar/TH）季详情模型（/intl/gateway/v2/ogv/view/app/season 的解析结果）。 */
data class ThSeasonDetail(
    val seasonId: Int,
    val title: String,
    val cover: String,
    val evaluate: String,
    val total: Int,
    val episodes: List<SeasonEpisode>,
    val originName: String = "",
    val alias: String = "",
    val styles: String = "",
    val areas: String = "",
    val typeName: String = "",
    val publishDateShow: String = "",
    val timeLengthShow: String = "",
    val actorTitle: String = "",
    val actorInfo: String = "",
    val staffTitle: String = "",
    val staffInfo: String = "",
)


/**
 * CN season JSON（api.bilibili.com/pgc/view/web/season 匿名可取，CN 直连）
 * → 选集面板中间模型。字段名与 CN 接口对齐：result.seasons[] 每季含 episodes[]。
 */
object SeasonParser {

    fun parseEpisodes(arr: org.json.JSONArray?): List<SeasonEpisode> {
        arr ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val e = arr.optJSONObject(i) ?: return@mapNotNull null
            val rawBadge = e.optString("badge")
            val rawBadgeType = e.optInt("badge_type")
            val status = e.optInt("status")
            val badge = if (rawBadge.isNotEmpty()) rawBadge else if (status == 13) "VIP" else ""
            val badgeType = if (rawBadge.isNotEmpty()) rawBadgeType else if (status == 13) 1 else 0
            SeasonEpisode(
                epId = e.optLong("id"),
                badge = badge,
                badgeType = badgeType,
                duration = e.optLong("duration"),
                status = status,
                cover = e.optString("cover"),
                aid = e.optLong("aid"),
                title = e.optString("title"),
                longTitle = e.optString("long_title"),
                cid = e.optLong("cid"),
                epIndex = e.optInt("index", i + 1),
                bvid = e.optString("bvid"),
                link = e.optString("link"),
                pubTime = e.optLong("pub_time"),
            )
        }
    }

    /**
     * 解析 → 分区列表。
     *
     * CN `pgc/view/web/season` 真实形状（2026-10-05 实测 season 91755 哆啦A梦第五季）：
     * **正片在 `result.episodes[]`**（80 话），花絮/PV 等在 `result.section[]`（各自带
     * `episodes`），而 `result.seasons[]` 只是「其他季」的摘要条目（**无 episodes**，
     * 仅 1 条存根）。旧实现按 `seasons[]` 建分区，得到「42 个分区 / 0 条剧集」的空响应，
     * 缓存页判定无内容 → 「页面加载失败，请重试」（真机实证 2026-10-05）。
     */
    fun parseSections(content: String): List<SeasonSection> = runCatching {
        var json = org.json.JSONObject(content)
        json.opt("result")?.let { result ->
            if (result !is String) json = json.getJSONObject("result")
        }
        val sections = ArrayList<SeasonSection>()
        val seasonId = json.optInt("season_id")
        val main = parseEpisodes(json.optJSONArray("episodes"))
        if (main.isNotEmpty()) {
            sections += SeasonSection(
                id = seasonId,
                sectionId = seasonId,
                title = MAIN_SECTION_TITLE,
                type = MAIN_SECTION_TYPE,
                episodes = main,
            )
        }
        val extras = json.optJSONArray("section")
        if (extras != null) {
            for (i in 0 until extras.length()) {
                val s = extras.optJSONObject(i) ?: continue
                val sid = s.optInt("id")
                if (sid == 0) continue
                sections += SeasonSection(
                    id = sid,
                    sectionId = sid,
                    title = s.optString("title"),
                    type = s.optInt("type", 1),
                    episodes = parseEpisodes(s.optJSONArray("episodes")),
                )
            }
        }
        if (sections.isEmpty()) parseSeasonSummaries(json) else sections
    }.getOrDefault(emptyList())

    /** CN JSON 里正片没有独立条目，需按 season_id 由 `episodes[]` 自建。 */
    /** 原生实拍（seasonSectionsForCache）正片分区标题为「选集」；CN JSON 无该条目，需自建。 */
    private const val MAIN_SECTION_TITLE = "选集"
    private const val MAIN_SECTION_TYPE = 0

    /** 「其他季」摘要回退（接口形态漂移保险）：正片与 section 都取不到时才用。 */
    private fun parseSeasonSummaries(json: org.json.JSONObject): List<SeasonSection> {
        val seasons = json.optJSONArray("seasons") ?: return emptyList()
        return (0 until seasons.length()).mapNotNull { i ->
            val s = seasons.optJSONObject(i) ?: return@mapNotNull null
            val sid = s.optInt("season_id")
            SeasonSection(
                id = sid,
                sectionId = sid,
                title = s.optString("title").ifEmpty { s.optString("season_title") },
                type = i,
                episodes = parseEpisodes(s.optJSONArray("episodes")),
            )
        }
    }

    /**
     * 解析 Bstar modules[].data.episodes。
     */
    fun parseEpisodesFromModules(modules: org.json.JSONArray?): List<SeasonEpisode> {
        modules ?: return emptyList()
        for (i in 0 until modules.length()) {
            val m = modules.optJSONObject(i) ?: continue
            if (m.optString("style") == "positive") {
                val d = m.optJSONObject("data") ?: continue
                val eps = parseEpisodes(d.optJSONArray("episodes"))
                if (eps.isNotEmpty()) return eps
            }
        }
        for (i in 0 until modules.length()) {
            val m = modules.optJSONObject(i) ?: continue
            val d = m.optJSONObject("data") ?: continue
            val eps = parseEpisodes(d.optJSONArray("episodes"))
            if (eps.isNotEmpty()) return eps
        }
        return emptyList()
    }

    /**
     * 解析 Bstar 东南亚季详情（/intl/gateway/v2/ogv/view/app/season）。
     */
    fun parseThSeasonDetail(content: String): ThSeasonDetail? = runCatching {
        var json = org.json.JSONObject(content)
        json.opt("result")?.let { result ->
            if (result !is String) json = json.getJSONObject("result")
        }
        if (json.optInt("code", 0) != 0) return null
        val sid = json.optInt("season_id")
        if (sid <= 0) return null
        val title = json.optString("title")
        val cover = json.optString("cover").ifEmpty { json.optString("square_cover") }
        val evaluate = json.optString("evaluate")
        val total = json.optInt("total")
        val eps = parseEpisodesFromModules(json.optJSONArray("modules"))
            .ifEmpty { parseEpisodes(json.optJSONArray("episodes")) }
            .ifEmpty {
                json.optJSONArray("seasons")?.optJSONObject(0)
                    ?.let { parseEpisodes(it.optJSONArray("episodes")) } ?: emptyList()
            }
        val originName = json.optString("origin_name")
        val alias = json.optString("alias")
        val typeName = json.optString("type_name")
        val stylesList = mutableListOf<String>()
        val stylesArr = json.optJSONArray("styles")
        if (stylesArr != null) {
            for (i in 0 until stylesArr.length()) {
                val s = stylesArr.optJSONObject(i)?.optString("name")
                if (!s.isNullOrEmpty()) stylesList.add(s)
            }
        }
        val areasList = mutableListOf<String>()
        val areasArr = json.optJSONArray("areas")
        if (areasArr != null) {
            for (i in 0 until areasArr.length()) {
                val a = areasArr.optJSONObject(i)?.optString("name")
                if (!a.isNullOrEmpty()) areasList.add(a)
            }
        }
        val publishObj = json.optJSONObject("publish")
        val publishDateShow = publishObj?.optString("release_date_show").orEmpty()
        val timeLengthShow = publishObj?.optString("time_length_show").orEmpty()
        val actorObj = json.optJSONObject("actor")
        val actorTitle = actorObj?.optString("title").orEmpty()
        val actorInfo = actorObj?.optString("info").orEmpty()
        val staffObj = json.optJSONObject("staff")
        val staffTitle = staffObj?.optString("title").orEmpty()
        val staffInfo = staffObj?.optString("info")
            ?: json.opt("staff")?.takeIf { it is String }?.toString().orEmpty()
        ThSeasonDetail(
            seasonId = sid,
            title = title,
            cover = cover,
            evaluate = evaluate,
            total = if (total > 0) total else eps.size,
            episodes = eps,
            originName = originName,
            alias = alias,
            styles = stylesList.joinToString(" "),
            areas = areasList.joinToString(" "),
            typeName = typeName,
            publishDateShow = publishDateShow,
            timeLengthShow = timeLengthShow,
            actorTitle = actorTitle,
            actorInfo = actorInfo,
            staffTitle = staffTitle,
            staffInfo = staffInfo,
        )
    }.getOrNull()

    /**
     * 解析 `pgc/view/web/season` 顶层 `result.episodes[]`（正季剧集平铺形态；
     * 服务端 `/pgc/view/web/season` 代理路线的返回）。顶层为空时回退首个 season 的
     * episodes（部分标题的正季挂在 seasons[0] 下），再回退 modules[].data.episodes（Bstar 东南亚形态）。
     */
    fun parseFlatEpisodes(content: String): List<SeasonEpisode> = runCatching {
        var json = org.json.JSONObject(content)
        json.opt("result")?.let { result ->
            if (result !is String) json = json.getJSONObject("result")
        }
        if (json.optInt("code", 0) != 0) return emptyList()
        val eps = parseEpisodes(json.optJSONArray("episodes"))
        if (eps.isNotEmpty()) return eps
        val seasonEps = json.optJSONArray("seasons")?.optJSONObject(0)
            ?.let { parseEpisodes(it.optJSONArray("episodes")) }
        if (!seasonEps.isNullOrEmpty()) return seasonEps
        parseEpisodesFromModules(json.optJSONArray("modules"))
    }.getOrDefault(emptyList())
}


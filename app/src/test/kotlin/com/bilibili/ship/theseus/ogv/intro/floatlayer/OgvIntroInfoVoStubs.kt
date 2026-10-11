package com.bilibili.ship.theseus.ogv.intro.floatlayer

data class Area(
    val id: Long,
    val name: String,
)

data class Publish(
    val weekday: Int,
    val unknowPubDate: Int,
    val pubTime: String,
    val pubTimeShow: String,
    val releaseDateShow: String,
    val timeLengthShow: String,
    val isStarted: Boolean,
    val isFinish: Boolean,
)

data class Style(
    val id: Long,
    val url: String,
    val name: String,
)

data class Actor(
    val title: String,
    val info: String,
)

data class Staff(
    val title: String,
    val info: String,
)

data class Rating(
    val score: Float,
    val count: String,
)

data class OgvIntroInfoVo(
    val seasonId: Long,
    val mediaType: Int,
    val mediaId: Long,
    val cover: String,
    val mediaTitle: String,
    val seasonTitle: String,
    val typeName: String,
    val areas: List<Area>,
    val publish: Publish,
    val mediaBadgeInfo: Any?,
    val alias: String,
    val originName: String,
    val styles: List<Style>,
    val celebrities: List<Any>,
    val actor: Actor?,
    val staff: Staff?,
    val evaluate: String,
    val rating: Rating,
)

package com.cslknn.kaynaklar

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/*
 * Kaynak tarifi şeması. Her site sources/<id>.json dosyasında bu yapıyla tarif edilir.
 * Alan değerleri "seçici | dönüşüm | dönüşüm" biçimindeki ifadelerdir (bkz. Selector.kt).
 */

@JsonIgnoreProperties(ignoreUnknown = true)
data class Source(
    val id: String = "",
    val name: String = "",
    val enabled: Boolean = true,
    val lang: String = "tr",
    val mainUrl: String = "",
    val icon: String? = null,
    val types: List<String> = listOf("Movie"),
    val description: String? = null,
    val userAgent: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val home: List<Section> = emptyList(),
    val list: ListSpec = ListSpec(),
    val search: SearchSpec? = null,
    val detail: DetailSpec? = null,
    val links: LinkSpec = LinkSpec(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Section(
    val title: String = "",
    val url: String = "",
    val horizontal: Boolean = false,
    val list: ListSpec? = null,
)

/** Ortak istek ayarları */
interface RequestSpec {
    val mode: String?
    val extract: String?
    val method: String?
    val body: String?
    val headers: Map<String, String>?
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class ListSpec(
    override val mode: String? = null,
    override val extract: String? = null,
    override val method: String? = null,
    override val body: String? = null,
    override val headers: Map<String, String>? = null,
    val items: String? = null,
    val title: String? = null,
    val url: String? = null,
    val poster: String? = null,
    val year: String? = null,
    val plot: String? = null,
    val kind: String? = null,
    val data: String? = null,
    val next: String? = null,
    val pageSize: Int? = null,
) : RequestSpec {
    /** Bölüme özel ayarları genel liste ayarlarının üzerine yazar */
    fun merge(o: ListSpec?): ListSpec = if (o == null) this else ListSpec(
        mode = o.mode ?: mode, extract = o.extract ?: extract, method = o.method ?: method,
        body = o.body ?: body, headers = o.headers ?: headers, items = o.items ?: items,
        title = o.title ?: title, url = o.url ?: url, poster = o.poster ?: poster,
        year = o.year ?: year, plot = o.plot ?: plot, kind = o.kind ?: kind, data = o.data ?: data,
        next = o.next ?: next, pageSize = o.pageSize ?: pageSize,
    )
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class SearchSpec(
    val url: String? = null,
    val local: Boolean = false,
    val list: ListSpec? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class DetailSpec(
    override val mode: String? = null,
    override val extract: String? = null,
    override val method: String? = null,
    override val body: String? = null,
    override val headers: Map<String, String>? = null,
    val url: String? = null,
    val root: String? = null,
    val title: String? = null,
    val plot: String? = null,
    val poster: String? = null,
    val background: String? = null,
    val year: String? = null,
    val tags: String? = null,
    val duration: String? = null,
    val rating: String? = null,
    val actors: String? = null,
    val trailer: String? = null,
    val kind: String? = null,
    val data: String? = null,
    val episodes: EpisodeSpec? = null,
    val recommendations: ListSpec? = null,
) : RequestSpec

@JsonIgnoreProperties(ignoreUnknown = true)
data class EpisodeSpec(
    val items: String? = null,
    val name: String? = null,
    val season: String? = null,
    val episode: String? = null,
    val poster: String? = null,
    val plot: String? = null,
    val date: String? = null,
    val data: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class LinkSpec(
    override val mode: String? = "direct",
    override val extract: String? = null,
    override val method: String? = null,
    override val body: String? = null,
    override val headers: Map<String, String>? = null,
    /** auto | hls | dash | video | extractor */
    val type: String? = "auto",
    val url: String? = null,
    val items: String? = null,
    val src: String? = null,
    val label: String? = null,
    val quality: String? = null,
    val referer: String? = null,
    val subtitles: SubtitleSpec? = null,
) : RequestSpec

@JsonIgnoreProperties(ignoreUnknown = true)
data class SubtitleSpec(
    val items: String? = null,
    val src: String? = null,
    val lang: String? = null,
)

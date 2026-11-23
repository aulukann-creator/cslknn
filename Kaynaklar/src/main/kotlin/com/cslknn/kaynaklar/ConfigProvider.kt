package com.cslknn.kaynaklar

import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder

/**
 * Tek bir kaynak tarifini (sources/<id>.json) CloudStream sağlayıcısına dönüştürür.
 * Tarif SourceRegistry'den her çağrıda okunur; böylece uzaktan güncellenen tarif
 * uygulama yeniden başlatılmadan devreye girer.
 */
class ConfigProvider(private val sourceId: String, initial: Source) : MainAPI() {

    private val src: Source get() = SourceRegistry.get(sourceId) ?: fallback
    private val fallback = initial

    override var name = initial.name
    override var mainUrl = initial.mainUrl.trimEnd('/')
    override var lang = initial.lang
    override val hasMainPage = initial.home.isNotEmpty()
    override val hasQuickSearch = false
    override val hasDownloadSupport = true
    override val hasChromecastSupport = true
    override val supportedTypes = initial.types.mapNotNull { typeOf(it) }.toSet().ifEmpty { setOf(TvType.Movie) }

    override val mainPage = initial.home.mapIndexed { i, s ->
        MainPageData(name = s.title, data = i.toString(), horizontalImages = s.horizontal)
    }

    // ------------------------------------------------------------ istek

    private fun fill(template: String, vars: Map<String, String?>): String {
        var out = template
        vars.forEach { (k, v) -> if (v != null) out = out.replace("{$k}", v) }
        return out
    }

    private suspend fun fetch(url: String, spec: RequestSpec?, referer: String? = null): String {
        val headers = buildMap {
            put("User-Agent", src.userAgent ?: USER_AGENT)
            putAll(src.headers)
            spec?.headers?.let { putAll(it) }
        }
        return if (spec?.method.equals("POST", ignoreCase = true)) {
            val body = spec?.body ?: ""
            val type = headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value
                ?: if (body.trimStart().startsWith("{")) "application/json" else "application/x-www-form-urlencoded"
            app.post(url, headers = headers, referer = referer, requestBody = body.toRequestBody(type.toMediaTypeOrNull())).text
        } else {
            app.get(url, headers = headers, referer = referer).text
        }
    }

    // ------------------------------------------------------------ liste

    private data class ListResult(val items: List<SearchResponse>, val hasNext: Boolean)

    private suspend fun list(spec: ListSpec, urlTemplate: String, page: Int, query: String?): ListResult {
        val size = spec.pageSize ?: 0
        val url = fill(
            urlTemplate, mapOf(
                "page" to page.toString(),
                "page0" to (page - 1).toString(),
                "offset" to ((page - 1) * size).toString(),
                "query" to query?.let { URLEncoder.encode(it, "UTF-8").replace("+", "%20") },
                "mainUrl" to mainUrl,
            )
        )
        val doc = Selector.parseDoc(fetch(url, spec), spec.mode, url, spec.extract)
        val noDetail = src.detail == null
        val results = Selector.itemsOf(doc, spec.items).mapNotNull { item ->
            val title = Selector.one(item, spec.title, url)?.trim()
            if (title.isNullOrEmpty()) return@mapNotNull null
            val poster = Selector.one(item, spec.poster, url)
            val kindText = Selector.one(item, spec.kind, url)
            val target = if (noDetail) {
                val data = Selector.select(item, spec.data, url)
                if (data.isEmpty()) return@mapNotNull null
                payload(title, poster, Selector.one(item, spec.plot, url), kindText, data)
            } else {
                Selector.one(item, spec.url, url) ?: return@mapNotNull null
            }
            toSearch(title, target, poster, Selector.one(item, spec.year, url)?.toIntOrNull(), kindText)
        }
        val hasNext = when {
            !spec.next.isNullOrBlank() -> Selector.select(doc, spec.next, url).isNotEmpty()
            size > 0 -> results.size >= size
            else -> results.isNotEmpty() && (urlTemplate.contains("{page") || urlTemplate.contains("{offset"))
        }
        return ListResult(results, hasNext)
    }

    private fun payload(title: String, poster: String?, plot: String?, kind: String?, data: List<String>): String {
        val o = mapper.createObjectNode()
        o.put("title", title)
        poster?.let { o.put("poster", it) }
        plot?.let { o.put("plot", it) }
        kind?.let { o.put("kind", it) }
        if (data.size == 1) o.put("data", data[0]) else o.set<com.fasterxml.jackson.databind.JsonNode>(
            "data", mapper.createArrayNode().also { a -> data.forEach { a.add(it) } }
        )
        return o.toString()
    }

    private fun toSearch(title: String, url: String, poster: String?, year: Int?, kind: String?): SearchResponse {
        val type = typeOf(kind) ?: supportedTypes.first()
        return if (type == TvType.Live) {
            newLiveSearchResponse(title, url, TvType.Live, fix = false) { this.posterUrl = poster }
        } else {
            newMovieSearchResponse(title, url, type, fix = false) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val section = src.home.getOrNull(request.data.toIntOrNull() ?: return null) ?: return null
        val res = list(src.list.merge(section.list), section.url, page, null)
        return newHomePageResponse(request, res.items, res.hasNext)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val s = src.search ?: return null
        if (s.local) {
            if (page > 1) return newSearchResponseList(emptyList(), false)
            val q = query.lowercase(TR)
            val found = LinkedHashMap<String, SearchResponse>()
            src.home.forEach { section ->
                runCatching { list(src.list.merge(section.list), section.url, 1, null) }.getOrNull()
                    ?.items?.filter { it.name.lowercase(TR).contains(q) }
                    ?.forEach { found.putIfAbsent(it.url, it) }
            }
            return newSearchResponseList(found.values.toList(), false)
        }
        val url = s.url ?: return null
        val res = list(src.list.merge(s.list), url, page, query)
        return newSearchResponseList(res.items, res.hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse>? = search(query, 1)?.items

    // ------------------------------------------------------------ detay

    override suspend fun load(url: String): LoadResponse? {
        val det = src.detail
        if (det == null || url.startsWith("{\"")) return loadPayload(url)

        val pageUrl = fill(det.url ?: "{url}", mapOf("url" to url, "mainUrl" to mainUrl))
        val doc = Selector.parseDoc(fetch(pageUrl, det), det.mode, pageUrl, det.extract)
        val root = det.root?.let { Selector.itemsOf(doc, it).firstOrNull() } ?: doc
        fun g(spec: String?) = Selector.one(root, spec, pageUrl)

        val title = g(det.title)?.trim() ?: return null
        val kind = g(det.kind)
        val type = typeOf(kind) ?: supportedTypes.first()
        val poster = g(det.poster)
        val background = g(det.background)
        val plot = g(det.plot)
        val year = g(det.year)?.toIntOrNull()
        val tags = Selector.select(root, det.tags, pageUrl).distinct().take(10)
        val duration = g(det.duration)?.toIntOrNull()
        val rating = g(det.rating)?.toDoubleOrNull()
        val trailer = g(det.trailer)
        val recommendations = det.recommendations?.let { spec ->
            Selector.itemsOf(root, spec.items).mapNotNull { item ->
                val t = Selector.one(item, spec.title, pageUrl) ?: return@mapNotNull null
                val u = Selector.one(item, spec.url, pageUrl) ?: return@mapNotNull null
                toSearch(t, u, Selector.one(item, spec.poster, pageUrl), null, Selector.one(item, spec.kind, pageUrl))
            }
        }

        val episodes = det.episodes?.let { ep ->
            Selector.itemsOf(root, ep.items).mapIndexedNotNull { index, item ->
                val data = Selector.select(item, ep.data, pageUrl)
                if (data.isEmpty()) return@mapIndexedNotNull null
                newEpisode(encodeData(data)) {
                    this.name = Selector.one(item, ep.name, pageUrl)
                    this.season = Selector.one(item, ep.season, pageUrl)?.toIntOrNull()
                    this.episode = Selector.one(item, ep.episode, pageUrl)?.toIntOrNull() ?: (index + 1)
                    this.posterUrl = Selector.one(item, ep.poster, pageUrl)
                    this.description = Selector.one(item, ep.plot, pageUrl)
                }
            }
        }.orEmpty()

        if (type == TvType.Live) {
            val data = det.data?.let { Selector.select(root, it, pageUrl) }.orEmpty().ifEmpty { listOf(url) }
            return newLiveStreamLoadResponse(title, url, encodeData(data)) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.plot = plot
                this.tags = tags
            }
        }

        if (episodes.isNotEmpty()) {
            return newTvSeriesLoadResponse(title, url, if (type == TvType.Movie) TvType.TvSeries else type, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.plot = plot
                this.year = year
                this.tags = tags
                this.duration = duration
                this.score = rating?.let { Score.from10(it) }
                this.recommendations = recommendations
                trailer?.let { addTrailer(it) }
            }
        }

        val data = det.data?.let { Selector.select(root, it, pageUrl) }.orEmpty().ifEmpty { listOf(url) }
        return newMovieLoadResponse(title, url, type, encodeData(data)) {
            this.posterUrl = poster
            this.backgroundPosterUrl = background
            this.plot = plot
            this.year = year
            this.tags = tags
            this.duration = duration
            this.score = rating?.let { Score.from10(it) }
            this.recommendations = recommendations
            trailer?.let { addTrailer(it) }
        }
    }

    private suspend fun loadPayload(url: String): LoadResponse? {
        val o = runCatching { mapper.readTree(url) }.getOrNull() ?: return null
        val title = o.path("title").asText(null) ?: return null
        val poster = o.path("poster").asText(null)
        val plot = o.path("plot").asText(null)
        val type = typeOf(o.path("kind").asText(null)) ?: supportedTypes.first()
        val dataNode = o.path("data")
        val data = if (dataNode.isArray) dataNode.map { it.asText() } else listOfNotNull(dataNode.asText(null))
        return if (type == TvType.Live) {
            newLiveStreamLoadResponse(title, url, encodeData(data)) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            newMovieLoadResponse(title, url, type, encodeData(data)) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
    }

    private fun encodeData(data: List<String>): String =
        if (data.size == 1) data[0] else mapper.writeValueAsString(data)

    private fun decodeData(data: String): List<String> =
        if (data.trimStart().startsWith("[")) {
            runCatching { mapper.readTree(data).map { it.asText() } }.getOrDefault(listOf(data))
        } else listOf(data)

    // ------------------------------------------------------------ oynatma

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ln = src.links
        val referer = ln.referer ?: "$mainUrl/"
        val mode = (ln.mode ?: "direct").lowercase()
        var found = false

        suspend fun emit(link: String, label: String?, quality: Int?) {
            if (emitLink(link, label, quality, referer, ln.type, mode == "extractor", subtitleCallback, callback)) found = true
        }

        for (d in decodeData(data)) {
            if (mode == "direct" || mode == "extractor") {
                emit(d, null, null)
                continue
            }
            val pageUrl = fill(ln.url ?: "{data}", mapOf("data" to d, "mainUrl" to mainUrl))
            val doc = Selector.parseDoc(fetch(pageUrl, ln, referer), ln.mode, pageUrl, ln.extract)
            Selector.itemsOf(doc, ln.items).forEach { item ->
                val label = Selector.one(item, ln.label, pageUrl)
                val quality = Selector.one(item, ln.quality, pageUrl)?.let { Regex("""\d{3,4}""").find(it)?.value?.toIntOrNull() }
                Selector.select(item, ln.src, pageUrl).forEach { emit(it, label, quality) }
            }
            ln.subtitles?.let { sub ->
                Selector.itemsOf(doc, sub.items).forEach { item ->
                    val s = Selector.one(item, sub.src, pageUrl) ?: return@forEach
                    subtitleCallback(newSubtitleFile(Selector.one(item, sub.lang, pageUrl) ?: "Altyazı", s))
                }
            }
        }
        return found
    }

    private suspend fun emitLink(
        link: String,
        label: String?,
        quality: Int?,
        referer: String,
        forcedType: String?,
        forceExtractor: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val forced = (forcedType ?: "auto").lowercase()
        val type = when {
            forced == "hls" -> ExtractorLinkType.M3U8
            forced == "dash" -> ExtractorLinkType.DASH
            forced == "video" -> ExtractorLinkType.VIDEO
            forceExtractor || forced == "extractor" -> null
            else -> mediaType(link)
        }
        if (type == null) {
            // Gömülü oynatıcı (YouTube, Vimeo vb.): CloudStream'in hazır çözücülerine devret
            if (loadExtractor(link, referer, subtitleCallback, callback)) return true
            if (forceExtractor || forced == "extractor") return false
        }
        callback(
            newExtractorLink(
                source = name,
                name = label?.let { "$name • $it" } ?: name,
                url = link,
                type = type ?: INFER_TYPE,
            ) {
                this.referer = referer
                this.quality = quality ?: Regex("""(\d{3,4})p""").find(label ?: link)?.groupValues?.get(1)?.toIntOrNull()
                        ?: Qualities.Unknown.value
                this.headers = src.headers
            }
        )
        return true
    }

    private fun mediaType(link: String): ExtractorLinkType? {
        val ext = Selector.mediaRe.find(link)?.groupValues?.get(1)?.lowercase() ?: return null
        return when (ext) {
            "m3u8" -> ExtractorLinkType.M3U8
            "mpd" -> ExtractorLinkType.DASH
            else -> ExtractorLinkType.VIDEO
        }
    }

    companion object {
        private val TR = java.util.Locale("tr", "TR")
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36"

        fun typeOf(kind: String?): TvType? = when (kind?.trim()?.lowercase()) {
            null, "" -> null
            "movie", "film" -> TvType.Movie
            "series", "tvseries", "dizi" -> TvType.TvSeries
            "live", "canli", "canlı" -> TvType.Live
            "documentary", "belgesel" -> TvType.Documentary
            "cartoon", "cizgi", "çizgi film" -> TvType.Cartoon
            "anime" -> TvType.Anime
            "music", "muzik", "müzik" -> TvType.Music
            "podcast" -> TvType.Podcast
            "video" -> TvType.Video
            "other", "diger", "diğer", "others" -> TvType.Others
            else -> runCatching { TvType.valueOf(kind!!.trim()) }.getOrNull()
        }
    }
}

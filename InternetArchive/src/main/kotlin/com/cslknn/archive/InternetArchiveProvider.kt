package com.cslknn.archive

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addDuration
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import java.net.URLEncoder

/**
 * Internet Archive (archive.org) sağlayıcısı.
 *
 * Resmi ve herkese açık iki uç nokta kullanılır:
 *  - advancedsearch.php : listeleme ve arama
 *  - metadata/{id}      : başlık, açıklama, dosya listesi
 *
 * Bir arşiv kaydı tek bir film olabileceği gibi onlarca bölümden oluşan bir dizi de olabilir.
 * Dosyalar "orijinal dosya + ondan türetilmiş kopyalar" şeklinde gruplanır;
 * birden fazla grup varsa kayıt dizi olarak gösterilir.
 */
class InternetArchiveProvider : MainAPI() {
    override var mainUrl = "https://archive.org"
    override var name = "Internet Archive"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val hasDownloadSupport = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(
        TvType.Movie, TvType.TvSeries, TvType.Cartoon, TvType.Documentary
    )

    // data alanı: "<arama sorgusu>|<sıralama>"
    override val mainPage = mainPageOf(
        "collection:feature_films|downloads desc" to "En Çok İzlenen Filmler",
        "collection:feature_films|addeddate desc" to "Yeni Eklenen Filmler",
        "collection:Film_Noir|downloads desc" to "Kara Film (Film Noir)",
        "collection:SciFi_Horror|downloads desc" to "Bilim Kurgu ve Korku",
        "collection:Comedy_Films|downloads desc" to "Komedi Klasikleri",
        "collection:silent_films|downloads desc" to "Sessiz Sinema",
        "collection:classic_tv|downloads desc" to "Klasik TV Dizileri",
        "collection:animationandcartoons|downloads desc" to "Animasyon ve Çizgi Film",
        "collection:short_films|downloads desc" to "Kısa Filmler",
    )

    private val pageSize = 30

    // Sayfalarda gösterilecek alanlar
    private val searchFields = listOf("identifier", "title", "year", "date", "downloads", "collection")

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

    private fun posterOf(identifier: String) = "$mainUrl/services/img/$identifier"

    private fun detailsUrl(identifier: String) = "$mainUrl/details/$identifier"

    private fun identifierFromUrl(url: String): String =
        url.substringAfter("/details/").substringBefore("/").substringBefore("?")

    private suspend fun advancedSearch(query: String, sort: String, page: Int): Pair<List<SearchResponse>, Boolean> {
        val fields = searchFields.joinToString("") { "&fl%5B%5D=$it" }
        val url = "$mainUrl/advancedsearch.php?q=${encode("($query) AND mediatype:movies")}" +
                fields +
                "&sort%5B%5D=${encode(sort)}" +
                "&rows=$pageSize&page=$page&output=json"

        val root = mapper.readTree(app.get(url).text)
        val response = root.path("response")
        val total = response.path("numFound").asInt(0)
        val docs = response.path("docs")

        val results = docs.mapNotNull { doc -> doc.toSearchResponse() }
        val hasNext = page * pageSize < total
        return results to hasNext
    }

    private fun JsonNode.toSearchResponse(): SearchResponse? {
        val identifier = path("identifier").asText(null) ?: return null
        val title = firstText(path("title")) ?: identifier
        val collections = path("collection").let { node ->
            if (node.isArray) node.map { it.asText() } else listOf(node.asText())
        }
        val type = guessType(collections)
        val year = path("year").asText(null)?.toIntOrNull()
            ?: path("date").asText(null)?.take(4)?.toIntOrNull()

        return newMovieSearchResponse(title, detailsUrl(identifier), type) {
            this.posterUrl = posterOf(identifier)
            this.year = year
        }
    }

    private fun guessType(collections: List<String>): TvType {
        val lower = collections.map { it.lowercase() }
        return when {
            lower.any { it.contains("classic_tv") || it.contains("television") } -> TvType.TvSeries
            lower.any { it.contains("cartoon") || it.contains("animation") } -> TvType.Cartoon
            lower.any { it.contains("documentar") } -> TvType.Documentary
            else -> TvType.Movie
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val query = request.data.substringBefore("|")
        val sort = request.data.substringAfter("|", "downloads desc")
        val (items, hasNext) = advancedSearch(query, sort, page)
        return newHomePageResponse(request, items, hasNext)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val clean = query.replace("\"", " ").trim()
        // Başlıkta geçenler önce, popülerliğe göre sıralanır
        val q = "title:($clean) AND (collection:feature_films OR collection:classic_tv OR " +
                "collection:animationandcartoons OR collection:short_films OR collection:silent_films)"
        val (items, hasNext) = advancedSearch(q, "downloads desc", page)
        return newSearchResponseList(items, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> =
        search(query, 1).items

    // ---------- Detay sayfası ----------

    override suspend fun load(url: String): LoadResponse? {
        val identifier = identifierFromUrl(url)
        val root = mapper.readTree(app.get("$mainUrl/metadata/$identifier").text)
        val meta = root.path("metadata")
        if (meta.isMissingNode || meta.size() == 0) return null

        val title = firstText(meta.path("title")) ?: identifier
        val plot = firstText(meta.path("description"))?.let { cleanHtml(it) }
        val year = meta.path("year").asText(null)?.toIntOrNull()
            ?: firstText(meta.path("date"))?.take(4)?.toIntOrNull()
        val tags = allTexts(meta.path("subject"))
            .flatMap { it.split(";", ",") }
            .map { it.trim() }
            .filter { it.isNotBlank() && it.length < 40 }
            .distinct()
            .take(8)
        val collections = allTexts(meta.path("collection"))
        val runtime = firstText(meta.path("runtime"))
        val poster = posterOf(identifier)
        val background = pickBackground(root, identifier)

        val groups = buildGroups(root.path("files"))
        if (groups.isEmpty()) return null

        val type = guessType(collections)

        return if (groups.size == 1 && type != TvType.TvSeries) {
            val group = groups.first()
            newMovieLoadResponse(title, url, type, PlayData(identifier, group.videos, group.subtitles).toJson()) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.plot = plot
                this.year = year
                this.tags = tags
                this.duration = parseRuntime(runtime) ?: group.durationMinutes
                if (this.duration == null) addDuration(runtime)
            }
        } else {
            val episodes = groups.mapIndexed { index, group ->
                val (season, episode) = parseSeasonEpisode(group.title) ?: (null to index + 1)
                newEpisode(PlayData(identifier, group.videos, group.subtitles)) {
                    this.name = prettifyEpisodeName(group.title, title)
                    this.season = season
                    this.episode = episode
                    this.runTime = group.durationMinutes
                    this.posterUrl = group.thumbnail?.let { fileUrl(identifier, it) }
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        }
    }

    // ---------- Oynatma ----------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val play = tryParseJson<PlayData>(data) ?: return false
        if (play.videos.isEmpty()) return false

        play.videos.forEach { video ->
            callback(
                newExtractorLink(
                    source = name,
                    name = "$name • ${video.label}",
                    url = fileUrl(play.identifier, video.name),
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.quality = video.height ?: Qualities.Unknown.value
                    this.referer = "$mainUrl/"
                }
            )
        }

        play.subtitles.forEach { sub ->
            subtitleCallback(
                newSubtitleFile(sub.language, fileUrl(play.identifier, sub.name))
            )
        }
        return true
    }

    // ---------- Dosya gruplama ----------

    /** Oynatılabilir video formatları ve tercih sırası (küçük sayı = önce) */
    private val videoFormats = mapOf(
        "h.264 HD" to 0,
        "h.264" to 1,
        "h.264 IA" to 2,
        "MPEG4" to 3,
        "512Kb MPEG4" to 4,
        "Matroska" to 5,
        "WebM" to 6,
        "Ogg Video" to 7,
    )

    private val subtitleFormats = setOf("SubRip", "Web Video Text Tracks")

    private data class Group(
        val title: String,
        val videos: List<VideoFile>,
        val subtitles: List<SubFile>,
        val durationMinutes: Int?,
        val thumbnail: String?,
    )

    private fun buildGroups(files: JsonNode): List<Group> {
        if (!files.isArray) return emptyList()

        // Her dosyanın ait olduğu "kök" orijinal dosya adı
        fun rootOf(file: JsonNode): String {
            val name = file.path("name").asText()
            return if (file.path("source").asText() == "derivative") {
                file.path("original").asText(name)
            } else name
        }

        val videoFiles = files.filter { it.path("format").asText() in videoFormats.keys }
        if (videoFiles.isEmpty()) return emptyList()

        val thumbs = files.filter { it.path("format").asText() == "Thumbnail" }
        val subtitleFiles = files.filter { it.path("format").asText() in subtitleFormats }

        val grouped = videoFiles.groupBy { rootOf(it) }

        return grouped.map { (root, list) ->
            val videos = list
                .sortedBy { videoFormats[it.path("format").asText()] ?: 99 }
                .map { file ->
                    val format = file.path("format").asText()
                    val height = file.path("height").asText(null)?.toIntOrNull()?.takeIf { it > 0 }
                    VideoFile(
                        name = file.path("name").asText(),
                        label = listOfNotNull(height?.let { "${it}p" }, format).joinToString(" • "),
                        height = height
                    )
                }
                .distinctBy { it.name }

            val base = root.substringBeforeLast(".")
            val subs = subtitleFiles
                .filter { val n = it.path("name").asText(); n.startsWith(base) || rootOf(it) == root }
                .map { file ->
                    val n = file.path("name").asText()
                    SubFile(n, guessSubtitleLanguage(n))
                }
                .ifEmpty {
                    // Tek grup varsa kayıttaki tüm altyazılar bu gruba aittir
                    if (grouped.size == 1) subtitleFiles.map { SubFile(it.path("name").asText(), guessSubtitleLanguage(it.path("name").asText())) }
                    else emptyList()
                }

            val length = list.firstNotNullOfOrNull { it.path("length").asText(null)?.let(::parseLength) }
            val thumb = thumbs.firstOrNull { it.path("name").asText().startsWith(base) }?.path("name")?.asText()

            Group(
                title = base.substringAfterLast("/"),
                videos = videos,
                subtitles = subs,
                durationMinutes = length,
                thumbnail = thumb
            )
        }.sortedWith(compareBy({ parseSeasonEpisode(it.title)?.first ?: 0 }, { parseSeasonEpisode(it.title)?.second ?: 0 }, { it.title }))
    }

    private fun pickBackground(root: JsonNode, identifier: String): String? {
        val files = root.path("files")
        if (!files.isArray) return null
        val thumbs = files.filter { it.path("format").asText() == "Thumbnail" }
        if (thumbs.isEmpty()) return null
        // Filmin ortalarından bir kare, kapak görseli olarak daha temsili olur
        val middle = thumbs[thumbs.size / 2].path("name").asText()
        return fileUrl(identifier, middle)
    }

    // ---------- Yardımcılar ----------

    private fun fileUrl(identifier: String, name: String): String {
        val path = name.split("/").joinToString("/") { encode(it).replace("+", "%20") }
        return "$mainUrl/download/$identifier/$path"
    }

    private fun firstText(node: JsonNode): String? = when {
        node.isMissingNode || node.isNull -> null
        node.isArray -> node.firstOrNull()?.asText()
        else -> node.asText()
    }?.takeIf { it.isNotBlank() }

    private fun allTexts(node: JsonNode): List<String> = when {
        node.isMissingNode || node.isNull -> emptyList()
        node.isArray -> node.map { it.asText() }
        else -> listOf(node.asText())
    }

    private fun cleanHtml(html: String): String =
        Jsoup.parse(html.replace("<br>", "\n").replace("<br />", "\n")).wholeText()
            .lines().joinToString("\n") { it.trim() }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()

    /** "1:17:26", "77:26", "4646.42" (saniye) biçimlerini dakikaya çevirir */
    private fun parseRuntime(value: String?): Int? {
        if (value.isNullOrBlank()) return null
        val parts = value.trim().split(":").mapNotNull { it.trim().toDoubleOrNull() }
        return when (parts.size) {
            3 -> (parts[0] * 60 + parts[1]).toInt()
            2 -> parts[0].toInt()
            else -> null
        }
    }

    private fun parseLength(value: String): Int? {
        if (value.contains(":")) return parseRuntime(value)
        val seconds = value.toDoubleOrNull() ?: return null
        return (seconds / 60).toInt().takeIf { it > 0 }
    }

    private val seasonEpisodeRegex = Regex("""[Ss](\d{1,2})\s*[Ee](\d{1,3})""")
    private val xEpisodeRegex = Regex("""\b(\d{1,2})x(\d{1,3})\b""")

    private fun parseSeasonEpisode(name: String): Pair<Int, Int>? {
        seasonEpisodeRegex.find(name)?.let { return it.groupValues[1].toInt() to it.groupValues[2].toInt() }
        xEpisodeRegex.find(name)?.let { return it.groupValues[1].toInt() to it.groupValues[2].toInt() }
        return null
    }

    private fun prettifyEpisodeName(fileTitle: String, showTitle: String): String {
        // "Get Smart S01E01 (Mr. Big)" -> "Mr. Big"
        Regex("""\(([^)]+)\)\s*$""").find(fileTitle)?.let { return it.groupValues[1] }
        return fileTitle
            .replace(seasonEpisodeRegex, "")
            .replace(showTitle, "", ignoreCase = true)
            .replace(Regex("[_.]+"), " ")
            .trim(' ', '-', '–')
            .ifBlank { fileTitle }
    }

    private fun guessSubtitleLanguage(fileName: String): String {
        val lower = fileName.lowercase()
        return when {
            ".asr." in lower -> "English (otomatik)"
            Regex("""[._-](tr|tur|turkish)[._-]""").containsMatchIn(lower) -> "Türkçe"
            Regex("""[._-](en|eng|english)[._-]""").containsMatchIn(lower) -> "English"
            else -> "Altyazı"
        }
    }

    // ---------- loadLinks'e taşınan veri ----------

    data class VideoFile(
        @JsonProperty("name") val name: String,
        @JsonProperty("label") val label: String,
        @JsonProperty("height") val height: Int?,
    )

    data class SubFile(
        @JsonProperty("name") val name: String,
        @JsonProperty("language") val language: String,
    )

    data class PlayData(
        @JsonProperty("identifier") val identifier: String,
        @JsonProperty("videos") val videos: List<VideoFile>,
        @JsonProperty("subtitles") val subtitles: List<SubFile>,
    )
}

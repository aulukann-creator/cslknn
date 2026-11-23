package com.cslknn.kaynaklar

import com.fasterxml.jackson.core.type.TypeReference
import com.lagradost.cloudstream3.mapper
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Kaynak tariflerinin tutulduğu yer.
 *  1. Eklentinin içine gömülü kopya (derleme anındaki sources klasörü) -> her zaman var
 *  2. Uzak kopya (builds dalındaki sources.json) -> açılışta arka planda indirilir, gömülüyü günceller
 */
object SourceRegistry {
    const val REMOTE_URL = "https://raw.githubusercontent.com/aulukann-creator/cslknn/builds/sources.json"

    private val sources = ConcurrentHashMap<String, Source>()

    fun get(id: String): Source? = sources[id]

    fun all(): List<Source> = sources.values.toList()

    fun parse(json: String): List<Source> =
        mapper.readValue(json, object : TypeReference<List<Source>>() {})
            .filter { it.id.isNotBlank() && it.name.isNotBlank() && it.mainUrl.isNotBlank() }

    fun bundled(): List<Source> = runCatching {
        parse(String(Base64.getDecoder().decode(BundledSources.BASE64), Charsets.UTF_8))
    }.getOrDefault(emptyList())

    /** Tarifleri kaydeder, daha önce bilinmeyen kimlikleri döndürür */
    fun put(list: List<Source>): List<Source> {
        val fresh = list.filter { !sources.containsKey(it.id) }
        list.forEach { sources[it.id] = it }
        return fresh
    }
}

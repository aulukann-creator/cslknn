package com.cslknn.kaynaklar

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.lagradost.cloudstream3.mapper
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

/*
 * Seçici dili (tools/engine.py ile birebir aynı kurallar)
 *
 *   ifade      := alternatif ( " || " alternatif )*      -> ilk boş olmayan sonuç
 *   alternatif := seçici ( " | " dönüşüm )*
 *
 * JSON modlarında seçici bir yol ifadesidir:
 *   a.b.c   a[0]   a[*]   a[?anahtar=değer]   a[?anahtar!=değer]   a[?anahtar~içerir]
 *   $  -> geçerli düğüm,  ^yol -> belgenin kökünden,  'metin' -> sabit değer
 * HTML/XML modlarında seçici bir CSS seçicisidir:
 *   css            -> metin
 *   css@öznitelik  -> öznitelik (@html, @outer, @own, @text özel)
 */

sealed class Node
class JNode(val value: JsonNode, val root: JsonNode) : Node()
class HNode(val el: Element, val root: Element) : Node()

object Selector {
    private val nodes = JsonNodeFactory.instance
    private val altSplit = Regex("""\s+\|\|\s+""")
    private val pipeSplit = Regex("""\s+\|\s+""")
    private val tokenRe = Regex("""([^.\[\]]*)((?:\[[^\]]*\])*)""")
    private val bracketRe = Regex("""\[([^\]]*)\]""")
    private val filterRe = Regex("""\?\s*([^!=~]+?)\s*(!=|~|=)\s*(.*)$""")
    val mediaRe = Regex("""\.(m3u8|mpd|mp4|webm|mkv|mov|ogv|ogg|m4v|ts)(\?|#|$)""", RegexOption.IGNORE_CASE)

    // ------------------------------------------------------------ JSON yolları

    private fun splitPath(path: String): List<String> {
        val parts = mutableListOf<String>()
        val buf = StringBuilder()
        var depth = 0
        for (ch in path) {
            if (ch == '[') depth++ else if (ch == ']') depth--
            if (ch == '.' && depth == 0) {
                parts.add(buf.toString()); buf.clear()
            } else buf.append(ch)
        }
        parts.add(buf.toString())
        return parts.filter { it.isNotEmpty() }
    }

    fun asText(v: JsonNode?): String? = when {
        v == null || v.isNull || v.isMissingNode -> null
        v.isTextual -> v.textValue()
        v.isIntegralNumber -> v.asLong().toString()
        v.isNumber -> v.asDouble().let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() }
        v.isBoolean -> v.asBoolean().toString()
        else -> v.toString()
    }

    private fun child(values: List<JsonNode>, name: String): List<JsonNode> {
        val out = mutableListOf<JsonNode>()
        for (v in values) {
            when {
                v.isObject -> v.get(name)?.let { out.add(it) }
                v.isArray -> {
                    val idx = name.toIntOrNull()
                    if (idx != null) {
                        if (idx < v.size()) out.add(v.get(idx))
                    } else out.addAll(child(v.toList(), name))
                }
            }
        }
        return out
    }

    private fun bracket(values: List<JsonNode>, raw: String): List<JsonNode> {
        val expr = raw.trim()
        val out = mutableListOf<JsonNode>()
        if (expr == "*") {
            values.forEach { v -> if (v.isArray || v.isObject) out.addAll(v.toList()) }
            return out
        }
        expr.toIntOrNull()?.let { i ->
            values.forEach { v ->
                if (v.isArray) {
                    val idx = if (i < 0) v.size() + i else i
                    if (idx in 0 until v.size()) out.add(v.get(idx))
                }
            }
            return out
        }
        if (expr.startsWith("?")) {
            val m = filterRe.find(expr) ?: return out
            val key = m.groupValues[1]
            val op = m.groupValues[2]
            val want = m.groupValues[3].trim().trim('\'', '"')
            values.forEach { v ->
                val list = if (v.isArray) v.toList() else listOf(v)
                list.forEach { item ->
                    val got = jsonPath(listOf(item), key).firstOrNull()?.let { asText(it) }
                    val ok = when (op) {
                        "=" -> got == want
                        "!=" -> got != want
                        else -> got != null && got.contains(want, ignoreCase = true)
                    }
                    if (ok) out.add(item)
                }
            }
            return out
        }
        return child(values, expr.trim('\'', '"'))
    }

    fun jsonPath(values: List<JsonNode>, path: String): List<JsonNode> {
        var cur = values
        for (part in splitPath(path)) {
            val m = tokenRe.matchEntire(part) ?: return emptyList()
            val name = m.groupValues[1]
            if (name.isNotEmpty()) cur = child(cur, name)
            bracketRe.findAll(m.groupValues[2]).forEach { cur = bracket(cur, it.groupValues[1]) }
        }
        return cur
    }

    // ------------------------------------------------------------ seçim

    private fun htmlSelect(el: Element, sel: String): List<String> {
        val css = sel.substringBefore("@").trim()
        val attr = sel.substringAfter("@", "").trim().ifEmpty { "text" }
        val els = if (css.isEmpty()) listOf(el) else el.select(css).toList()
        return els.mapNotNull { e ->
            when (attr) {
                "text" -> e.text()
                "html" -> e.html()
                "outer" -> e.outerHtml()
                "own" -> e.ownText().trim()
                else -> if (e.hasAttr(attr)) e.attr(attr) else null
            }
        }
    }

    private fun rawSelect(node: Node, selRaw: String): List<String> {
        val sel = selRaw.trim()
        if (sel.length >= 2 && sel.startsWith("'") && sel.endsWith("'")) return listOf(sel.substring(1, sel.length - 1))
        return when (node) {
            is JNode -> {
                val vals = when {
                    sel.isEmpty() || sel == "$" -> listOf(node.value)
                    sel.startsWith("^") -> jsonPath(listOf(node.root), sel.substring(1).trimStart('.'))
                    else -> jsonPath(listOf(node.value), sel)
                }
                vals.mapNotNull { asText(it) }
            }
            is HNode -> if (sel.startsWith("^")) htmlSelect(node.root, sel.substring(1)) else htmlSelect(node.el, sel)
        }
    }

    fun select(node: Node, spec: String?, base: String): List<String> {
        if (spec.isNullOrBlank()) return emptyList()
        for (alt in spec.split(altSplit)) {
            val parts = alt.split(pipeSplit).map { it.trim() }
            val vals = transform(rawSelect(node, parts[0]), parts.drop(1), base)
            if (vals.isNotEmpty()) return vals
        }
        return emptyList()
    }

    fun one(node: Node, spec: String?, base: String): String? = select(node, spec, base).firstOrNull()

    // ------------------------------------------------------------ dönüşümler

    fun transform(input: List<String>, steps: List<String>, base: String): List<String> {
        var values = input
        for (step in steps) {
            val name = step.substringBefore(":").trim().lowercase()
            val arg = step.substringAfter(":", "")
            values = when (name) {
                "first" -> values.take(1)
                "last" -> values.takeLast(1)
                "join" -> if (values.isEmpty()) values else listOf(values.joinToString(arg))
                "split" -> values.flatMap { v -> v.split(arg.ifEmpty { "," }).map { it.trim() }.filter { it.isNotEmpty() } }
                "default" -> values.ifEmpty { listOf(arg) }
                "unique" -> values.distinct()
                else -> values.mapNotNull { v -> one(name, arg, v, base)?.takeIf { it.isNotEmpty() } }
            }
        }
        return values
    }

    private fun one(name: String, arg: String, v: String, base: String): String? = when (name) {
        "absurl" -> absUrl(base, v)
        "text" -> Jsoup.parse(v).text()
        "unescape" -> Parser.unescapeEntities(v, false)
        "trim" -> v.trim()
        "int" -> {
            val clean = if (Regex("""[\d.,]+""").matches(v)) v.replace(",", "").replace(".", "") else v
            Regex("""-?\d+""").find(clean)?.value
        }
        "year" -> Regex("""(18|19|20)\d{2}""").find(v)?.value
        "regex" -> Regex(arg).find(v)?.let { m -> if (m.groupValues.size > 1) m.groupValues[1] else m.value }
        "replace" -> Regex(arg.substringBefore("=>")).replace(v, arg.substringAfter("=>", ""))
        "fmt" -> arg.replace("{}", v)
        "prefix" -> arg + v
        "suffix" -> v + arg
        "sec2min" -> v.toDoubleOrNull()?.let { (it / 60).toInt().toString() }
        "hms2min" -> hmsToMin(v)
        "iso2min" -> isoToMin(v)
        "lower" -> v.lowercase()
        "upper" -> v.uppercase()
        "urlencode" -> URLEncoder.encode(v, "UTF-8").replace("+", "%20")
        "urldecode" -> URLDecoder.decode(v, "UTF-8")
        else -> throw IllegalArgumentException("Bilinmeyen dönüşüm: $name")
    }

    fun absUrl(base: String, v: String): String? {
        val t = v.trim()
        if (t.isEmpty()) return null
        return try { URL(URL(base), t).toString() } catch (e: Exception) { t }
    }

    private fun hmsToMin(v: String): String? {
        val nums = v.trim().split(":").map { it.toDoubleOrNull() ?: return null }
        return when (nums.size) {
            3 -> (nums[0] * 60 + nums[1]).toInt().toString()
            2 -> nums[0].toInt().toString()
            else -> null
        }
    }

    private fun isoToMin(v: String): String? {
        val m = Regex("""P(?:(\d+)D)?T?(?:(\d+)H)?(?:(\d+)M)?(?:([\d.]+)S)?""", RegexOption.IGNORE_CASE)
            .find(v.trim()) ?: return null
        val g = m.groupValues.drop(1).map { it.toDoubleOrNull() ?: 0.0 }
        if (g.all { it == 0.0 }) return null
        return (g[0] * 1440 + g[1] * 60 + g[2] + g[3] / 60).toInt().toString()
    }

    // ------------------------------------------------------------ belge ayrıştırma

    fun parseDoc(textRaw: String, modeRaw: String?, url: String, extract: String?): Node {
        var text = textRaw
        if (!extract.isNullOrBlank()) {
            val m = Regex(extract, RegexOption.DOT_MATCHES_ALL).find(text)
                ?: throw IllegalStateException("extract deseni eşleşmedi")
            text = if (m.groupValues.size > 1) m.groupValues[1] else m.value
        }
        return when ((modeRaw ?: "json").lowercase()) {
            "json" -> mapper.readTree(text).let { JNode(it, it) }
            "next_data" -> {
                val data = Jsoup.parse(text).selectFirst("script#__NEXT_DATA__")?.data()
                    ?: throw IllegalStateException("__NEXT_DATA__ bulunamadı")
                mapper.readTree(data).let { JNode(it, it) }
            }
            "ld_json" -> {
                val arr: ArrayNode = nodes.arrayNode()
                Jsoup.parse(text).select("script[type=application/ld+json]").forEach { s ->
                    val v = try { mapper.readTree(s.data()) } catch (e: Exception) { null } ?: return@forEach
                    val list = if (v.isArray) v.toList() else listOf(v)
                    list.forEach { x -> if (x.has("@graph")) x.get("@graph").forEach { arr.add(it) } else arr.add(x) }
                }
                JNode(arr, arr)
            }
            "m3u" -> parseM3u(text).let { JNode(it, it) }
            "html" -> Jsoup.parse(text, url).let { HNode(it, it) }
            "xml" -> Jsoup.parse(text, url, Parser.xmlParser()).let { HNode(it, it) }
            else -> throw IllegalArgumentException("Bilinmeyen mod: $modeRaw")
        }
    }

    private fun parseM3u(text: String): ArrayNode {
        val out = nodes.arrayNode()
        var cur: com.fasterxml.jackson.databind.node.ObjectNode? = null
        val attrRe = Regex("""([\w-]+)="([^"]*)"""")
        text.lineSequence().map { it.trim() }.forEach { line ->
            if (line.startsWith("#EXTINF")) {
                val attrs = attrRe.findAll(line).associate { it.groupValues[1] to it.groupValues[2] }
                cur = nodes.objectNode().apply {
                    put("name", if (line.contains(",")) line.substringAfterLast(",").trim() else "")
                    put("logo", attrs["tvg-logo"] ?: "")
                    put("group", attrs["group-title"] ?: "")
                    put("id", attrs["tvg-id"] ?: "")
                    set<JsonNode>("attrs", nodes.objectNode().also { o -> attrs.forEach { (k, v) -> o.put(k, v) } })
                }
            } else if (line.isNotEmpty() && !line.startsWith("#") && cur != null) {
                cur!!.put("url", line)
                out.add(cur)
                cur = null
            }
        }
        return out
    }

    fun itemsOf(doc: Node, spec: String?): List<Node> = when (doc) {
        is JNode -> {
            var vals = if (spec.isNullOrBlank() || spec == "$") {
                if (doc.value.isArray) doc.value.toList() else listOf(doc.value)
            } else jsonPath(listOf(doc.value), spec)
            if (!spec.isNullOrBlank() && vals.size == 1 && vals[0].isArray && !spec.endsWith("]")) vals = vals[0].toList()
            vals.map { JNode(it, doc.root) }
        }
        is HNode -> {
            val els = if (spec.isNullOrBlank()) listOf(doc.el) else doc.el.select(spec).toList()
            els.map { HNode(it, doc.root) }
        }
    }
}

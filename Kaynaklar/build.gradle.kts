import java.util.Base64

// Sürüm numarası tam sayı olmalı; motor kodu değiştiğinde bir artırın.
// Yalnızca sources/*.json değişirse artırmaya gerek yok: eklenti tarifleri uzaktan günceller.
version = 2

cloudstream {
    description = "Yapılandırma tabanlı kaynak motoru: NASA+, TRT canlı yayınları ve repoya eklenen tüm yasal kaynaklar"
    language = "tr"
    status = 1
    tvTypes = listOf("Documentary", "Live", "Movie", "TvSeries")
    iconUrl = "https://raw.githubusercontent.com/aulukann-creator/cslknn/main/icons/kaynaklar.png"
}

// sources/*.json dosyalarını derleme anında eklentinin içine gömer (internet yoksa yedek)
val generatedDir = layout.buildDirectory.dir("generated/kaynaklar")

val generateBundledSources = tasks.register("generateBundledSources") {
    val sourcesDir = rootProject.file("sources")
    inputs.dir(sourcesDir)
    outputs.dir(generatedDir)
    doLast {
        val files = sourcesDir.listFiles { f -> f.extension == "json" }?.sortedBy { it.name } ?: emptyList()
        val bundle = files.joinToString(",", "[", "]") { it.readText(Charsets.UTF_8) }
        val b64 = Base64.getEncoder().encodeToString(bundle.toByteArray(Charsets.UTF_8))
        val chunks = b64.chunked(30000).joinToString(",\n") { "        \"$it\"" }
        val out = generatedDir.get().file("com/cslknn/kaynaklar/BundledSources.kt").asFile
        out.parentFile.mkdirs()
        out.writeText(
            "package com.cslknn.kaynaklar\n\n" +
            "internal object BundledSources {\n" +
            "    val BASE64: String = listOf(\n$chunks\n    ).joinToString(\"\")\n" +
            "}\n"
        )
    }
}

extensions.getByName<com.android.build.gradle.BaseExtension>("android")
    .sourceSets.getByName("main").java.srcDir(generatedDir)

tasks.named("preBuild") { dependsOn(generateBundledSources) }

dependencies {
    // Uygulamanın içinde zaten var; yalnızca derleme için gerekli
    "compileOnly"("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.1")
}

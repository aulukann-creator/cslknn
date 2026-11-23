rootProject.name = "cslknn"

// İçinde build.gradle.kts bulunan her klasör otomatik olarak eklenti modülü sayılır.
// Geçici olarak derlemeden çıkarmak istediğiniz modülün adını bu listeye yazın.
val disabled = listOf<String>()

File(rootDir, ".").eachDir { dir ->
    if (!disabled.contains(dir.name) && File(dir, "build.gradle.kts").exists()) {
        include(dir.name)
    }
}

fun File.eachDir(block: (File) -> Unit) {
    listFiles()?.filter { it.isDirectory }?.forEach { block(it) }
}

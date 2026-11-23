package com.cslknn.kaynaklar

import android.content.Context
import android.util.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe

@CloudstreamPlugin
class KaynaklarPlugin : Plugin() {

    override fun load(context: Context) {
        // 1) Gömülü tarifler: internet olmasa da sağlayıcılar hemen görünür
        SourceRegistry.put(SourceRegistry.bundled())
            .filter { it.enabled }
            .forEach { registerMainAPI(ConfigProvider(it.id, it)) }

        // 2) Uzak tarifler: repoya eklenen yeni kaynaklar ve düzeltmeler
        ioSafe {
            try {
                val remote = SourceRegistry.parse(app.get(SourceRegistry.REMOTE_URL).text)
                SourceRegistry.put(remote)
                    .filter { it.enabled }
                    .forEach { registerMainAPI(ConfigProvider(it.id, it)) }
            } catch (e: Exception) {
                Log.w(TAG, "Uzak kaynak listesi alınamadı: ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "cslknn"
    }
}

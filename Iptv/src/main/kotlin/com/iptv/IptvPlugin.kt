//import android.content.Context
//import androidx.appcompat.app.AppCompatActivity
//import com.iptv.PlaylistConfig
//import com.iptv.PlaylistManager
//import com.iptv.VipTVSettings
//import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
//import com.lagradost.cloudstream3.plugins.Plugin
//import kotlinx.coroutines.CoroutineScope
//import kotlinx.coroutines.Dispatchers
//import kotlinx.coroutines.launch
//import okhttp3.OkHttpClient
//import okhttp3.Request
//import java.io.File
//import java.util.concurrent.TimeUnit
//import androidx.fragment.app.FragmentActivity
//import android.widget.Toast
//// ==============================================================================
//// 2. The Plugin Entry Point
//// ==============================================================================
//@CloudstreamPlugin
//class VipTVPlugin : Plugin() {
//    override fun load(context: Context) {
//        val playlists = PlaylistManager.getPlaylists(context)
//        val autoUpdateHours = PlaylistManager.getAutoUpdateInterval(context)
//
//        // Auto-Update Logic
//        if (autoUpdateHours > 0) {
//            val currentTime = System.currentTimeMillis()
//            val intervalMs = autoUpdateHours * 60 * 60 * 1000L
//
//            CoroutineScope(Dispatchers.IO).launch {
//                var updated = false
//                playlists.forEach { playlist ->
//                    if (playlist.enabled && playlist.url.startsWith("http") &&
//                        (currentTime - playlist.lastUpdated > intervalMs)) {
//                        try {
//                            val client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
//                            val request = Request.Builder().url(playlist.url).build()
//                            val response = client.newCall(request).execute()
//                            if (response.isSuccessful) {
//                                val body = response.body?.string()
//                                if (!body.isNullOrBlank()) {
//                                    val fileName = "playlist_${playlist.id}.m3u"
//                                    val file = File(context.filesDir, fileName)
//                                    file.writeText(body)
//                                    playlist.localFileName = fileName
//                                    playlist.lastUpdated = currentTime
//                                    updated = true
//                                }
//                            }
//                        } catch (e: Exception) { e.printStackTrace() }
//                    }
//                }
//                if (updated) {
//                    PlaylistManager.savePlaylists(context, playlists)
//                }
//            }
//        }
//
//        if (playlists.isNotEmpty()) {
//            playlists.forEach { playlist ->
//                if (playlist.enabled) {
//                    registerMainAPI(VipTV(context, playlist))
//                }
//            }
//        } else {
//            registerMainAPI(VipTV(context, PlaylistConfig(name = "Setup", url = "http://localhost"), isSetup = true))
//        }
//
//        // --- تصحيح الخطأ هنا ---
//        openSettings = { ctx ->
//            val frag = VipTVSettings()
//            if (ctx is FragmentActivity) {
//                frag.show(ctx.supportFragmentManager, "VipTVSettings")
//            } else {
//                Toast.makeText(ctx, "Error opening settings", Toast.LENGTH_SHORT).show()
//            }
//        }
//    }
//}
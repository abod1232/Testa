package com.youtube

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class YoutubeTokenPlugin: Plugin() {
    override fun load(context: Context) {
        // إنشاء أو استدعاء SharedPreferences
        val sharedPref = context.getSharedPreferences("YouTube", Context.MODE_PRIVATE)

        // تسجيل المزود مع تمرير الإعدادات إليه
        registerMainAPI(com.lagradost.cloudstream3.ar.youtube.YoutubeProvider(sharedPref))

        // تعريف زر الإعدادات الخاص بالإضافة
        openSettings = { ctx ->
            // استخدام as? بدلاً من as لتجنب الـ Crash إذا كان الـ Context غير متوافق
            val activity = ctx as? AppCompatActivity

            if (activity != null) {
                // هنا قمنا بتمرير sharedPref كمعامل ثانٍ (sp)
                com.youtube.YoutubeSettingsBottomSheet.show(activity.supportFragmentManager, sharedPref)
            }
        }
    }
}


//override suspend fun loadLinks(
//    data: String,
//    isCasting: Boolean,
//    subtitleCallback: (SubtitleFile) -> Unit,
//    callback: (ExtractorLink) -> Unit
//): Boolean {
//    return runCatching {
//        val url = when {
//            data.startsWith("http") -> data
//            data.length == 11 -> "https://www.youtube.com/watch?v=$data"
//            else -> return false
//        }
//
//        YoutubeExtractor().getUrl(
//            url,
//            null,
//            subtitleCallback,
//            callback
//        )
//
//        true
//    }.getOrDefault(false)
//}
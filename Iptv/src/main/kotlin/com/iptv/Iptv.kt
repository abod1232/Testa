package com.iptv

import android.app.Dialog
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Scanner
import java.util.concurrent.TimeUnit

// ==============================================================================
// 1. Data Models & Managers
// ==============================================================================

data class PlaylistConfig(
    var id: String = "", // سيتم تعيينه عند الإنشاء
    var name: String,
    var url: String,
    var enabled: Boolean = true,
    var isFavorite: Boolean = false,
    var localFileName: String? = null,
    var lastUpdated: Long = 0,
    var latency: Long = -1,
    var isValid: Boolean? = null,
    var downloadDurationText: String? = null
)

object PlaylistManager {
    private const val PREF_KEY_PLAYLISTS = "iptv_playlists_data_v4_fixed"
    private const val PREF_KEY_AUTO_UPDATE = "iptv_auto_update_interval"
    private const val PREF_KEY_ONLY_FAV = "iptv_only_favorites_mode"

    fun savePlaylists(context: Context, list: List<PlaylistConfig>) {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        val jsonArray = JSONArray()
        list.forEach { item ->
            val obj = JSONObject()
            obj.put("id", item.id)
            obj.put("name", item.name)
            obj.put("url", item.url)
            obj.put("enabled", item.enabled)
            obj.put("isFavorite", item.isFavorite)
            obj.put("localFileName", item.localFileName ?: "")
            obj.put("lastUpdated", item.lastUpdated)
            jsonArray.put(obj)
        }
        prefs.edit().putString(PREF_KEY_PLAYLISTS, jsonArray.toString()).apply()
    }

    fun getPlaylists(context: Context): MutableList<PlaylistConfig> {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        val jsonString = prefs.getString(PREF_KEY_PLAYLISTS, "[]")
        val list = mutableListOf<PlaylistConfig>()
        try {
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                // ضمان وجود ID
                var id = obj.optString("id")
                if (id.isEmpty()) {
                    id = System.currentTimeMillis().toString() + (Math.random() * 1000).toInt()
                }

                list.add(
                    PlaylistConfig(
                        id = id,
                        name = obj.getString("name"),
                        url = obj.getString("url"),
                        enabled = obj.optBoolean("enabled", true),
                        isFavorite = obj.optBoolean("isFavorite", false),
                        localFileName = obj.optString("localFileName").ifEmpty { null },
                        lastUpdated = obj.optLong("lastUpdated", 0)
                    )
                )
            }
        } catch (e: Exception) { e.printStackTrace() }
        return list
    }

    // دالة مساعدة للحصول على إعداد واحد (للتحديث المباشر)
    fun getPlaylistById(context: Context, id: String): PlaylistConfig? {
        return getPlaylists(context).find { it.id == id }
    }

    fun setAutoUpdateInterval(context: Context, hours: Int) {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().putInt(PREF_KEY_AUTO_UPDATE, hours).apply()
    }

    fun getAutoUpdateInterval(context: Context): Int {
        return androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
            .getInt(PREF_KEY_AUTO_UPDATE, 0)
    }

    fun setOnlyFavoritesMode(context: Context, enabled: Boolean) {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().putBoolean(PREF_KEY_ONLY_FAV, enabled).apply()
    }

    fun isOnlyFavoritesMode(context: Context): Boolean {
        return androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(PREF_KEY_ONLY_FAV, false)
    }
}

// ==============================================================================
// 2. The Plugin Entry Point
// ==============================================================================
@CloudstreamPlugin
class VipTVPlugin : Plugin() {
    override fun load(context: Context) {
        val allPlaylists = PlaylistManager.getPlaylists(context)
        val autoUpdateHours = PlaylistManager.getAutoUpdateInterval(context)
        val onlyFavorites = PlaylistManager.isOnlyFavoritesMode(context)

        val activePlaylists = if (onlyFavorites) {
            allPlaylists.filter { it.enabled && it.isFavorite }
        } else {
            allPlaylists.filter { it.enabled }
        }

        // Auto-Update Logic
        if (autoUpdateHours > 0) {
            val currentTime = System.currentTimeMillis()
            val intervalMs = autoUpdateHours * 60 * 60 * 1000L

            CoroutineScope(Dispatchers.IO).launch {
                var updated = false
                activePlaylists.forEach { playlist ->
                    if (playlist.url.startsWith("http") &&
                        (currentTime - playlist.lastUpdated > intervalMs)) {
                        try {
                            val client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
                            val request = Request.Builder().url(playlist.url).build()
                            val response = client.newCall(request).execute()
                            if (response.isSuccessful) {
                                val body = response.body?.string()
                                if (!body.isNullOrBlank()) {
                                    val fileName = "playlist_${playlist.id}.m3u"
                                    val file = File(context.filesDir, fileName)
                                    file.writeText(body)
                                    playlist.localFileName = fileName
                                    playlist.lastUpdated = currentTime
                                    updated = true
                                }
                            }
                        } catch (e: Exception) { e.printStackTrace() }
                    }
                }
                if (updated) {
                    PlaylistManager.savePlaylists(context, allPlaylists)
                }
            }
        }

        if (activePlaylists.isNotEmpty()) {
            activePlaylists.forEach { playlist ->
                registerMainAPI(VipTV(context, playlist))
            }
        } else {
            val msg = if (onlyFavorites && allPlaylists.isNotEmpty()) "No Favorites Selected" else "Setup"
            registerMainAPI(VipTV(context, PlaylistConfig(name = msg, url = "http://localhost"), isSetup = true))
        }

        openSettings = { ctx ->
            val frag = VipTVSettings()
            if (ctx is FragmentActivity) {
                frag.show(ctx.supportFragmentManager, "VipTVSettings")
            } else {
                Toast.makeText(ctx, "Error opening settings", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

// ==============================================================================
// 3. The Provider Logic (UPDATED)
// ==============================================================================
class VipTV(
    val context: Context,
    val initialConfig: PlaylistConfig,
    val isSetup: Boolean = false
) : MainAPI() {
    override var mainUrl = initialConfig.url
    override var name = initialConfig.name
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "ar"
    override val hasMainPage = true

    private val allChannels = mutableListOf<SearchResponse>()
    private val categories = mutableMapOf<String, MutableList<SearchResponse>>()

    private fun loadData() {
        if (allChannels.isNotEmpty()) return
        if (isSetup) return

        // **هام جداً**: جلب أحدث نسخة من الإعدادات بدلاً من الاعتماد على النسخة القديمة
        // هذا يضمن أنه إذا تم تحميل الملف في الإعدادات، سيراه المشغل فوراً
        val currentConfig = if (initialConfig.id.isNotEmpty()) {
            PlaylistManager.getPlaylistById(context, initialConfig.id) ?: initialConfig
        } else {
            initialConfig
        }

        var rawData: String? = null

        // 1. Try Local File first (from the FRESH config)
        if (currentConfig.localFileName != null) {
            val file = File(context.filesDir, currentConfig.localFileName!!)
            if (file.exists()) {
                try {
                    rawData = file.readText()
                } catch (e: Exception) {
                    e.printStackTrace()
                    // If file fails, fallback to null so we try network
                    rawData = null
                }
            }
        }

        // 2. If no local file, try network (fallback)
        if (rawData == null) {
            try {
                val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).build()
                val request = Request.Builder().url(mainUrl).build()
                val response = client.newCall(request).execute()
                rawData = response.body?.string()
            } catch (e: Exception) { e.printStackTrace() }
        }

        // 3. Parse
        if (rawData != null) {
            parseM3U(rawData)
        }
    }

    private fun parseM3U(content: String) {
        val scanner = Scanner(content)
        var currentGroup = "General"
        var currentLogo: String? = null
        var currentName = ""

        while (scanner.hasNextLine()) {
            val line = scanner.nextLine().trim()
            if (line.startsWith("#EXTINF")) {
                val groupMatch = Regex("group-title=\"(.*?)\"").find(line)
                currentGroup = groupMatch?.groupValues?.get(1) ?: "General"
                val logoMatch = Regex("tvg-logo=\"(.*?)\"").find(line)
                currentLogo = logoMatch?.groupValues?.get(1)
                currentName = line.substringAfterLast(",").trim()
            } else if (line.isNotEmpty() && !line.startsWith("#")) {
                val channelData = newLiveSearchResponse(currentName, line, TvType.Live) {
                    this.posterUrl = currentLogo
                }
                allChannels.add(channelData)
                categories.getOrPut(currentGroup) { mutableListOf() }.add(channelData)
                currentName = ""
                currentLogo = null
            }
        }
        scanner.close()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Clear old data to force reload with fresh config
        allChannels.clear()
        categories.clear()

        loadData()
        val items = mutableListOf<HomePageList>()

        if (isSetup) {
            items.add(HomePageList("Welcome", listOf(newLiveSearchResponse("Click Settings to Add Playlists", "", TvType.Live){})))
        } else {
            categories.forEach { (category, channels) ->
                items.add(HomePageList(name = category, list = channels))
            }
        }
        return newHomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (allChannels.isEmpty()) loadData()
        return allChannels.filter { it.name.contains(query, ignoreCase = true) }
    }

    override suspend fun load(url: String): LoadResponse {
        return newMovieLoadResponse(name, url, TvType.Movie, url) {
            posterUrl = null; plot = "Stream from $name"
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        callback.invoke(newExtractorLink(source = name, name = name, url = data,
        ) {
            referer = mainUrl
            quality = Qualities.Unknown.value
        })
        return true
    }
}

// ==============================================================================
// 4. Custom Settings UI
// ==============================================================================
class VipTVSettings : BottomSheetDialogFragment() {

    private lateinit var containerLayout: LinearLayout
    private lateinit var currentPlaylists: MutableList<PlaylistConfig>
    private lateinit var contextCtx: Context
    private var searchText = ""
    private var currentSortMode = 0
    private var isGlobalFavMode = false

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.setOnShowListener {
            val d = it as BottomSheetDialog
            val bottomSheet = d.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.let { sheet ->
                BottomSheetBehavior.from(sheet).state = BottomSheetBehavior.STATE_EXPANDED
                sheet.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
            }
        }
        return dialog
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        contextCtx = requireContext()
        currentPlaylists = PlaylistManager.getPlaylists(contextCtx)
        isGlobalFavMode = PlaylistManager.isOnlyFavoritesMode(contextCtx)

        // Root Layout
        val root = LinearLayout(contextCtx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.parseColor("#151515"))
        }

        // --- Toolbar ---
        val toolbar = LinearLayout(contextCtx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(30, 40, 30, 30)
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#202020"))
        }

        val searchInput = EditText(contextCtx).apply {
            hint = "بحث..."
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            textSize = 14f
            background = null
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    searchText = s.toString()
                    refreshList()
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
        }

        val favModeBtn = TextView(contextCtx).apply {
            text = if(isGlobalFavMode) "★" else "☆"
            textSize = 24f
            setTextColor(if(isGlobalFavMode) Color.YELLOW else Color.WHITE)
            setPadding(20, 0, 20, 0)
            setOnClickListener {
                isGlobalFavMode = !isGlobalFavMode
                PlaylistManager.setOnlyFavoritesMode(contextCtx, isGlobalFavMode)
                text = if(isGlobalFavMode) "★" else "☆"
                setTextColor(if(isGlobalFavMode) Color.YELLOW else Color.WHITE)
                Toast.makeText(contextCtx, if(isGlobalFavMode) "المفضلة فقط" else "الكل", Toast.LENGTH_SHORT).show()
                refreshList()
            }
        }

        val sortBtn = TextView(contextCtx).apply {
            text = "⋮"
            textSize = 24f
            setTextColor(Color.WHITE)
            setPadding(20, 0, 20, 0)
            setOnClickListener { showSortMenu(this) }
        }

        val addBtn = TextView(contextCtx).apply {
            text = "+"
            textSize = 30f
            setTextColor(Color.parseColor("#FF4081"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(50, 0, 0, 0)
            }
            setOnClickListener { showAddOptions(this) }
        }

        toolbar.addView(searchInput)
        toolbar.addView(favModeBtn)
        toolbar.addView(sortBtn)
        toolbar.addView(addBtn)
        root.addView(toolbar)

        // --- Actions Bar ---
        val actionsBar = LinearLayout(contextCtx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(10, 20, 10, 20)
        }

        fun createActionButton(title: String, color: String, onClick: () -> Unit): TextView {
            return TextView(contextCtx).apply {
                text = title
                setTextColor(Color.WHITE)
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(30, 15, 30, 15)
                val shape = GradientDrawable().apply {
                    cornerRadius = 50f
                    setColor(Color.parseColor(color))
                }
                background = shape
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(10, 0, 10, 0)
                }
                setOnClickListener { onClick() }
            }
        }

        actionsBar.addView(createActionButton("فحص الكل", "#444444") { checkAllValidity() })
        actionsBar.addView(createActionButton("تحميل الكل", "#444444") { downloadAll() })
        actionsBar.addView(createActionButton("تحديث الكل", "#009688") { updateAll() })
        actionsBar.addView(createActionButton("حذف الكل", "#D32F2F") { deleteAll() })

        root.addView(actionsBar)

        // --- Auto Update ---
        val autoUpdateLayout = LinearLayout(contextCtx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(30, 10, 30, 10)
            gravity = Gravity.CENTER_VERTICAL
        }
        val autoUpdateLabel = TextView(contextCtx).apply {
            text = "التحديث التلقائي:"
            setTextColor(Color.GRAY)
            textSize = 12f
        }
        val autoUpdateSpinner = Spinner(contextCtx).apply {
            val options = listOf("معطل", "كل يوم", "كل 3 أيام", "كل أسبوع")
            adapter = ArrayAdapter(contextCtx, android.R.layout.simple_spinner_dropdown_item, options)
            val current = PlaylistManager.getAutoUpdateInterval(contextCtx)
            setSelection(when(current) {
                24 -> 1
                72 -> 2
                168 -> 3
                else -> 0
            })
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p0: AdapterView<*>?, p1: View?, pos: Int, p3: Long) {
                    val hours = when(pos) {
                        1 -> 24
                        2 -> 72
                        3 -> 168
                        else -> 0
                    }
                    PlaylistManager.setAutoUpdateInterval(contextCtx, hours)
                }
                override fun onNothingSelected(p0: AdapterView<*>?) {}
            }
        }
        autoUpdateLayout.addView(autoUpdateLabel)
        autoUpdateLayout.addView(autoUpdateSpinner)
        root.addView(autoUpdateLayout)


        // --- List Container ---
        val scrollView = ScrollView(contextCtx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        containerLayout = LinearLayout(contextCtx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 10, 20, 100)
        }

        scrollView.addView(containerLayout)
        root.addView(scrollView)

        refreshList()
        return root
    }

    // ================== Logic ==================

    private fun showSortMenu(anchor: View) {
        val popup = PopupMenu(contextCtx, anchor)
        popup.menu.add(0, 0, 0, "الترتيب الأصلي")
        popup.menu.add(0, 1, 1, "حسب الاسم (A-Z)")
        popup.menu.add(0, 2, 2, "حسب التأخير (الأسرع أولاً)")

        popup.setOnMenuItemClickListener { item ->
            currentSortMode = item.itemId
            refreshList()
            true
        }
        popup.show()
    }

    private fun showAddOptions(anchor: View) {
        val popup = PopupMenu(contextCtx, anchor)
        popup.menu.add(0, 0, 0, "إضافة رابط فردي")
        popup.menu.add(0, 1, 1, "استيراد كتلة (Bulk)")

        popup.setOnMenuItemClickListener { item ->
            when(item.itemId) {
                0 -> showAddDialog()
                1 -> showBulkImportDialog()
            }
            true
        }
        popup.show()
    }

    private fun getSortedList(): List<PlaylistConfig> {
        var listToShow = currentPlaylists.filter {
            it.name.contains(searchText, true) || it.url.contains(searchText, true)
        }
        if (isGlobalFavMode) {
            listToShow = listToShow.filter { it.isFavorite }
        }
        return when (currentSortMode) {
            1 -> listToShow.sortedBy { it.name }
            2 -> listToShow.sortedBy { if (it.latency == -1L) Long.MAX_VALUE else it.latency }
            else -> listToShow
        }
    }

    private fun refreshList() {
        containerLayout.removeAllViews()
        val list = getSortedList()
        list.forEachIndexed { index, item ->
            containerLayout.addView(createPlaylistItemView(item))
        }
    }

    private fun createPlaylistItemView(item: PlaylistConfig): View {
        val card = LinearLayout(contextCtx).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#252525"))
            setPadding(30, 30, 30, 30)
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, 20)
            }
            val shape = GradientDrawable().apply {
                cornerRadius = 15f
                setColor(Color.parseColor("#252525"))
            }
            background = shape
        }

        // Icons
        val deleteIcon = TextView(contextCtx).apply {
            text = "🗑️"
            textSize = 18f
            setPadding(0, 0, 20, 0)
            setOnClickListener {
                // Delete local file too
                if (item.localFileName != null) {
                    val f = File(contextCtx.filesDir, item.localFileName!!)
                    if(f.exists()) f.delete()
                }
                currentPlaylists.remove(item)
                saveAndRefresh()
            }
        }

        val editIcon = TextView(contextCtx).apply {
            text = "✏️"
            textSize = 18f
            setPadding(0, 0, 30, 0)
            setOnClickListener { showAddDialog(item) }
        }

        val checkBox = CheckBox(contextCtx).apply {
            isChecked = item.enabled
            buttonTintList = ColorStateList.valueOf(Color.parseColor("#FF4081"))
            setOnCheckedChangeListener { _, isChecked ->
                item.enabled = isChecked
                saveAndRefresh() // Save immediately
                card.alpha = if(isChecked) 1.0f else 0.5f
            }
        }
        card.alpha = if(item.enabled) 1.0f else 0.5f

        val favIcon = TextView(contextCtx).apply {
            text = if(item.isFavorite) "★" else "☆"
            textSize = 22f
            setTextColor(if(item.isFavorite) Color.YELLOW else Color.GRAY)
            setPadding(20, 0, 20, 0)
            setOnClickListener {
                item.isFavorite = !item.isFavorite
                text = if(item.isFavorite) "★" else "☆"
                setTextColor(if(item.isFavorite) Color.YELLOW else Color.GRAY)
                saveAndRefresh() // Save immediately
            }
        }

        val contentLayout = LinearLayout(contextCtx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val nameTxt = TextView(contextCtx).apply {
            text = item.name
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            textSize = 16f
        }

        val urlTxt = TextView(contextCtx).apply {
            text = item.url
            setTextColor(Color.GRAY)
            textSize = 12f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        val statusLine = LinearLayout(contextCtx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 10, 0, 0)
        }

        // Download Indicator (Check real file existence)
        val isFileExists = item.localFileName != null && File(contextCtx.filesDir, item.localFileName!!).exists()
        val dlIcon = TextView(contextCtx).apply {
            text = if (isFileExists) "📂" else "⬇️"
            textSize = 12f
            setPadding(0,0,15,0)
            alpha = if (isFileExists) 1.0f else 0.5f
        }

        val latencyTxt = TextView(contextCtx).apply {
            val ms = if (item.latency > -1) "${item.latency}ms" else "---ms"
            text = ms
            setTextColor(if (item.latency in 1..500) Color.GREEN else if (item.latency > 500) Color.YELLOW else Color.GRAY)
            textSize = 12f
            setPadding(0,0,15,0)
        }

        val checkTxt = TextView(contextCtx).apply {
            text = when(item.isValid) {
                true -> "✅"
                false -> "❌"
                else -> ""
            }
            textSize = 12f
            setPadding(0,0,15,0)
        }

        val dlTimeTxt = TextView(contextCtx).apply {
            text = item.downloadDurationText ?: ""
            setTextColor(Color.CYAN)
            textSize = 12f
        }

        statusLine.addView(dlIcon)
        statusLine.addView(latencyTxt)
        statusLine.addView(checkTxt)
        statusLine.addView(dlTimeTxt)

        contentLayout.addView(nameTxt)
        contentLayout.addView(urlTxt)
        contentLayout.addView(statusLine)

        card.addView(deleteIcon)
        card.addView(editIcon)
        card.addView(checkBox)
        card.addView(favIcon)
        card.addView(contentLayout)

        return card
    }

    // ================== Actions Implementation ==================

    private fun saveAndRefresh() {
        PlaylistManager.savePlaylists(contextCtx, currentPlaylists)
        // لا نحتاج لإعادة رسم القائمة بالكامل إذا كان مجرد تغيير بسيط، لكن للأمان نعيد الرسم
        // refreshList() // يمكن إزالتها إذا سببت بطء
    }

    private fun checkAllValidity() {
        Toast.makeText(contextCtx, "جاري الفحص السريع...", Toast.LENGTH_SHORT).show()
        val client = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()
        val targetList = getSortedList()

        CoroutineScope(Dispatchers.IO).launch {
            targetList.forEach { item ->
                if (!item.enabled) return@forEach

                val start = System.currentTimeMillis()
                try {
                    val req = Request.Builder().url(item.url).head().build()
                    val resp = client.newCall(req).execute()
                    item.isValid = resp.isSuccessful
                    resp.close()
                } catch (e: Exception) {
                    item.isValid = false
                }
                val end = System.currentTimeMillis()
                item.latency = if (item.isValid == true) (end - start) else -1
            }
            withContext(Dispatchers.Main) {
                refreshList()
                PlaylistManager.savePlaylists(contextCtx, currentPlaylists)
            }
        }
    }

    private fun downloadAll() {
        Toast.makeText(contextCtx, "بدء التحميل... يرجى الانتظار", Toast.LENGTH_SHORT).show()
        val client = OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build()
        val targetList = getSortedList()

        CoroutineScope(Dispatchers.IO).launch {
            targetList.forEach { item ->
                if (!item.enabled) return@forEach
                // Skip if already downloaded
                if (item.localFileName != null && File(contextCtx.filesDir, item.localFileName!!).exists()) return@forEach

                val start = System.currentTimeMillis()
                try {
                    val req = Request.Builder().url(item.url).build()
                    val resp = client.newCall(req).execute()
                    if (resp.isSuccessful) {
                        val body = resp.body?.string()
                        if (body != null) {
                            // استخدام ID ثابت في اسم الملف
                            val fileName = "playlist_${item.id}.m3u"
                            val file = File(contextCtx.filesDir, fileName)
                            file.writeText(body)

                            // تحديث الكائن
                            item.localFileName = fileName
                            item.lastUpdated = System.currentTimeMillis()
                            item.isValid = true

                            val duration = (System.currentTimeMillis() - start) / 1000.0
                            item.downloadDurationText = "${duration}s"

                            // **حفظ فوري بعد كل ملف**
                            PlaylistManager.savePlaylists(contextCtx, currentPlaylists)
                        }
                    } else {
                        item.isValid = false
                    }
                    resp.close()
                } catch (e: Exception) {
                    item.isValid = false
                    e.printStackTrace()
                }

                withContext(Dispatchers.Main) {
                    refreshList()
                }
            }
        }
    }

    private fun updateAll() {
        currentPlaylists.forEach { it.localFileName = null }
        downloadAll()
    }

    private fun deleteAll() {
        AlertDialog.Builder(contextCtx)
            .setTitle("حذف الكل")
            .setMessage("هل أنت متأكد؟ سيتم حذف جميع الملفات المحفوظة.")
            .setPositiveButton("نعم") { _, _ ->
                currentPlaylists.forEach {
                    if (it.localFileName != null) File(contextCtx.filesDir, it.localFileName!!).delete()
                }
                currentPlaylists.clear()
                PlaylistManager.savePlaylists(contextCtx, currentPlaylists)
                refreshList()
                Toast.makeText(contextCtx, "تم الحذف. أعد تشغيل التطبيق.", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun showAddDialog(existingItem: PlaylistConfig? = null) {
        val layout = LinearLayout(contextCtx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }
        val nameInput = EditText(contextCtx).apply { hint = "الاسم"; setText(existingItem?.name ?: "") }
        val urlInput = EditText(contextCtx).apply { hint = "الرابط (M3U)"; setText(existingItem?.url ?: "") }

        layout.addView(nameInput)
        layout.addView(urlInput)

        AlertDialog.Builder(contextCtx)
            .setTitle(if (existingItem == null) "إضافة جديد" else "تعديل")
            .setView(layout)
            .setPositiveButton("حفظ") { _, _ ->
                if (existingItem != null) {
                    existingItem.name = nameInput.text.toString()
                    existingItem.url = urlInput.text.toString()
                    existingItem.localFileName = null
                    existingItem.isValid = null
                } else {
                    currentPlaylists.add(PlaylistConfig(
                        id = System.currentTimeMillis().toString() + (Math.random() * 100).toInt(),
                        name = nameInput.text.toString(),
                        url = urlInput.text.toString()
                    ))
                }
                saveAndRefresh()
                refreshList()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun showBulkImportDialog() {
        val input = EditText(contextCtx).apply {
            hint = "الصق محتوى M3U أو قائمة روابط هنا..."
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 5; maxLines = 15
        }
        val layout = LinearLayout(contextCtx).apply {
            orientation = LinearLayout.VERTICAL; setPadding(50, 40, 50, 10); addView(input)
        }

        AlertDialog.Builder(contextCtx)
            .setTitle("استيراد (Bulk)")
            .setView(layout)
            .setPositiveButton("استيراد") { _, _ ->
                val rawText = input.text.toString()
                var count = 0

                val entryPattern = Regex("""#EXTINF.*Server:\s*(.*?)\s*Exp:.*?\n(http.*)""")
                for (match in entryPattern.findAll(rawText)) {
                    val name = match.groupValues[1].trim()
                        .replace("http://", "").replace("https://", "")
                        .substringBeforeLast(":").uppercase()
                    val url = match.groupValues[2].trim()
                    currentPlaylists.add(PlaylistConfig(
                        id = System.currentTimeMillis().toString() + count,
                        name = name,
                        url = url
                    ))
                    count++
                }

                if (count == 0) {
                    val simpleMatches = Regex("""(http.*?output=ts|http.*?\.m3u8|http.*?\.ts)""").findAll(rawText)
                    for (match in simpleMatches) {
                        val url = match.groupValues[1].trim()
                        val name = try { java.net.URI(url).host } catch (e: Exception) { "Server" }
                        currentPlaylists.add(PlaylistConfig(
                            id = System.currentTimeMillis().toString() + count,
                            name = name,
                            url = url
                        ))
                        count++
                    }
                }

                if (count > 0) {
                    PlaylistManager.savePlaylists(contextCtx, currentPlaylists)
                    refreshList()
                    Toast.makeText(contextCtx, "تمت إضافة $count مصادر", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(contextCtx, "لم يتم العثور على روابط صالحة", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("إلغاء", null).show()
    }
}
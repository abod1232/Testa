package com.replaymatch

import android.annotation.SuppressLint
import android.content.Context
import android.net.http.SslError
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.Button
import android.widget.FrameLayout
import androidx.fragment.app.FragmentContainerView
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class ReplaymatchSettings : BottomSheetDialogFragment() {

    class PrefsFragment : PreferenceFragmentCompat() {
        @SuppressLint("SetJavaScriptEnabled")
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val context = requireContext()
            val screen = preferenceManager.createPreferenceScreen(context)
            preferenceScreen = screen

            val targetUrl = "https://web11112x.faselhdx.life/video_player?player_token=VnFJTnhBcDdJZ2daWVlEVHZhckJnOXM4RFVTQjdnQWtXYXVPdk04bU03ZlVvTVNXSzhZN1dZMUg2Vm9GZGw4a204YnNGWkVNNmVPUENKUy9uNW1aczBtNmlOcEJMbjdXYVRQb0pnRlY0WkJ6UjIrSXN1NmlVbUJ3Z0FRL0Vxc3BFYkc0bUlQLzFxMVU5elE0NnhsSkpybjRldEtVcVdMM2EzZlZzc0dtMXh6OHBvd2xwblVvWENlb2VQdnVDMHVOc0tEeEo2ejNnb29kbmZEbzlNUG5HQkNMSnQ1dnZ0SzJnaHlQTVlaeVRQZz06OuerGytsGQy%2B92bETzzPxBY%3D"

            val testPreference = Preference(context).apply {
                title = "فتح الرابط (متصفح خارق الصلاحيات)"
                summary = "أقصى صلاحيات، تجاوز الشهادات، ودعم تفعيل الميديا والكوكيز"
                setOnPreferenceClickListener {
                    showUltimatePoweredWebView(context, targetUrl)
                    true
                }
            }

            screen.addPreference(testPreference)
        }

        @SuppressLint("SetJavaScriptEnabled", "CookieSetup")
        private fun showUltimatePoweredWebView(context: Context, url: String) {
            val activity = requireActivity()
            val rootView = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
            val container = FrameLayout(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(android.graphics.Color.BLACK)
            }
            val cookieManager = CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(null, true)
            }

            val webView = WebView(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                alpha = 1f
                visibility = View.VISIBLE
                setLayerType(View.LAYER_TYPE_HARDWARE, null)

                settings.apply {
                    javaScriptEnabled = true
                    javaScriptCanOpenWindowsAutomatically = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    allowFileAccess = true
                    allowContentAccess = true
                    allowFileAccessFromFileURLs = true
                    allowUniversalAccessFromFileURLs = true
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    builtInZoomControls = true
                    displayZoomControls = false
                    supportZoom = true
                    cacheMode = WebSettings.LOAD_DEFAULT
                    loadsImagesAutomatically = true
                    mediaPlaybackRequiresUserGesture = false // تشغيل الفيديوهات تلقائياً بدون تداخل
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    blockNetworkLoads = false
                    blockNetworkImage = false
                    databaseEnabled = true
                    
                    userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.0.0 Mobile Safari/537.36"
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        return false // البقاء دائماً داخل نفس الـ WebView
                    }

                    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                        handler?.proceed() // تجاوز أخطاء شهادات الأمان والسيرفرات الوهمية لمنع توقف الموقع
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onPermissionRequest(request: PermissionRequest?) {
                        request?.grant(request.resources) // منح كافة الأذونات المطلوبة من الموقع تلقائياً
                    }

                    override fun onGeolocationPermissionsShowPrompt(
                        origin: String?,
                        callback: GeolocationPermissions.Callback?
                    ) {
                        callback?.invoke(origin, true, false) // السماح بصلاحيات الموقع الجغرافي إن طلبها الموقع
                    }
                }
            }
            val headers = mapOf(
                "sec-ch-ua" to "\"Not:A-Brand\";v=\"8\", \"Chromium\";v=\"156\", \"Google Chrome\";v=\"156\"",
                "sec-ch-ua-mobile" to "?1",
                "sec-ch-ua-platform" to "\"Android\"",
                "upgrade-insecure-requests" to "1",
                "accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/jxl,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
                "sec-fetch-site" to "none",
                "sec-fetch-mode" to "navigate",
                "sec-fetch-user" to "?1",
                "sec-fetch-dest" to "document",
                "accept-language" to "ar-IQ,ar;q=0.9,en-US;q=0.8,en;q=0.7"
            )

            webView.loadUrl(url, headers)
            cookieManager.flush()
            val closeButton = Button(context).apply {
                text = "✕ إغلاق"
                setTextColor(android.graphics.Color.WHITE)
                setBackgroundColor(android.graphics.Color.parseColor("#CC000000"))
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = android.view.Gravity.TOP or android.view.Gravity.END
                    setMargins(32, 64, 32, 32)
                }
                setOnClickListener {
                    try {
                        webView.stopLoading()
                        webView.clearCache(true)
                        webView.destroy()
                        rootView.removeView(container)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }

            container.addView(webView)
            container.addView(closeButton)
            rootView.addView(container)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val fragmentContainer = FragmentContainerView(requireContext())
        fragmentContainer.id = View.generateViewId()
        return fragmentContainer
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        childFragmentManager.beginTransaction()
            .replace(view.id, PrefsFragment())
            .commit()
    }
}

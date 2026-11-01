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
import java.net.HttpURLConnection
import java.net.URL

class ReplaymatchSettings : BottomSheetDialogFragment() {

    class PrefsFragment : PreferenceFragmentCompat() {
        @SuppressLint("SetJavaScriptEnabled")
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val context = requireContext()
            val screen = preferenceManager.createPreferenceScreen(context)
            preferenceScreen = screen

            val targetUrl = "https://web11112x.faselhdx.life/video_player?player_token=VnFJTnhBcDdJZ2daWVlEVHZhckJnOXM4RFVTQjdnQWtXYXVPdk04bU03ZlVvTVNXSzhZN1dZMUg2Vm9GZGw4a204YnNGWkVNNmVPUENKUy9uNW1aczBtNmlOcEJMbjdXYVRQb0pnRlY0WkJ6UjIrSXN1NmlVbUJ3Z0FRL0Vxc3BFYkc0bUlQLzFxMVU5elE0NnhsSkpybjRldEtVcVdMM2EzZlZzc0dtMXh6OHBvd2xwblVvWENlb2VQdnVDMHVOc0tEeEo2ejNnb29kbmZEbzlNUG5HQkNMSnQ1dnZ0SzJnaHlQTVlaeVRQZz06OuerGytsGQy%2B92bETzzPxBY%3D"

            val testPreference = Preference(context).apply {
                title = "مشغل الفيديو"
                summary = "فتح الرابط مع حجب x-requested-with وتجاوز SSL"
                setOnPreferenceClickListener {
                    showCleanWebView(context, targetUrl)
                    true
                }
            }

            screen.addPreference(testPreference)
        }

        @SuppressLint("SetJavaScriptEnabled")
        private fun showCleanWebView(context: Context, urlString: String) {
            val activity = requireActivity()
            val rootView = activity.findViewById<ViewGroup>(android.R.id.content) ?: return

            val container = FrameLayout(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(android.graphics.Color.BLACK)
            }

            val webView = WebView(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                alpha = 1f
                visibility = View.VISIBLE

                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.0.0 Mobile Safari/537.36"
                }

                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        return false
                    }

                    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                        handler?.proceed() // تجاوز أخطاء SSL
                    }

                    // هنا السر: اعتراض الطلب وإرساله يدوياً بدون أي آثار لـ x-requested-with الخاصة بالتطبيق
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val reqUrl = request?.url.toString()
                        if (reqUrl.contains("faselhdx.life")) {
                            try {
                                val url = URL(reqUrl)
                                val connection = (url.openConnection() as HttpURLConnection).apply {
                                    requestMethod = "GET"
                                    // وضع الترويسات النظيفة التي أرسلتها أنت فقط حرفياً
                                    setRequestProperty("sec-ch-ua", "\"Not:A-Brand\";v=\"8\", \"Chromium\";v=\"156\", \"Android WebView\";v=\"156\"")
                                    setRequestProperty("sec-ch-ua-mobile", "?1")
                                    setRequestProperty("sec-ch-ua-platform", "\"Android\"")
                                    setRequestProperty("upgrade-insecure-requests", "1")
                                    setRequestProperty("user-agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.0.0 Mobile Safari/537.36")
                                    setRequestProperty("accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/jxl,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
                                    setRequestProperty("sec-fetch-mode", "navigate")
                                    setRequestProperty("sec-fetch-site", "none")
                                    setRequestProperty("accept-language", "ar-IQ,ar;q=0.9,en-US;q=0.8,en;q=0.7")
                                    setRequestProperty("sec-fetch-user", "?1")
                                    setRequestProperty("sec-fetch-dest", "document")
                                    // ملاحظة هامة: تم إسقاط ورفض x-requested-with تماماً هنا
                                    connect()
                                }

                                val mimeType = connection.contentType?.substringBefore(";") ?: "text/html"
                                val encoding = connection.contentEncoding ?: "utf-8"
                                val stream = connection.inputStream

                                return WebResourceResponse(mimeType, encoding, stream)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                }
            }

            webView.loadUrl(urlString)

            val closeButton = Button(context).apply {
                text = "✕ إغلاق"
                setTextColor(android.graphics.Color.WHITE)
                setBackgroundColor(android.graphics.Color.parseColor("#99000000"))
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

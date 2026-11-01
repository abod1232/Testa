package com.replaymatch

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
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
                title = "فتح الرابط في WebView"
                summary = "اضغط هنا لمعاينة الـ WebView بشكل ظاهر"
                setOnPreferenceClickListener {
                    showVisibleWebView(context, targetUrl)
                    true
                }
            }

            screen.addPreference(testPreference)
        }

        @SuppressLint("SetJavaScriptEnabled")
        private fun showVisibleWebView(context: Context, url: String) {
            val activity = requireActivity()
            val rootView = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
            val webView = WebView(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                alpha = 1f
                translationX = 0f
                visibility = View.VISIBLE

                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    loadWithOverviewMode = true
                    useWideViewPort = true
                }
                webViewClient = WebViewClient()
            }
            rootView.addView(webView)
            webView.loadUrl(url)
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

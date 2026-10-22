package com.sticktv.player

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

/**
 * Player de reserva para links que são páginas web (não streams diretos).
 * Bloqueia anúncios, pop-ups, redirecionamentos e alertas, e deixa o vídeo em tela cheia.
 */
class WebPlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_UA = "ua"
        const val EXTRA_REFERER = "referer"
        const val EXTRA_ZAP = "zap"
    }

    private lateinit var webView: WebView
    private lateinit var container: FrameLayout
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var allowedBase = ""
    private val handler = Handler(Looper.getMainLooper())
    private var injectCount = 0

    private val injectRunnable = object : Runnable {
        override fun run() {
            if (isFinishing || !::webView.isInitialized) return
            webView.evaluateJavascript(CLEANER_JS, null)
            if (++injectCount < 8) handler.postDelayed(this, 2000)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val url = intent.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) {
            finish()
            return
        }
        allowedBase = AdBlocker.baseDomain(Uri.parse(url).host ?: "")

        container = FrameLayout(this)
        container.setBackgroundColor(Color.BLACK)

        webView = WebView(this)
        webView.setBackgroundColor(Color.BLACK)
        container.addView(
            webView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        val hint = TextView(this).apply {
            text = "▲/▼ trocar canal   •   VOLTAR abre a lista"
            setTextColor(Color.WHITE)
            textSize = 16f
            setBackgroundResource(R.drawable.bg_rounded)
            setPadding(32, 16, 32, 16)
        }
        container.addView(
            hint,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply { bottomMargin = 48 }
        )
        handler.postDelayed({ hint.visibility = View.GONE }, 5000)

        setContentView(container)

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            loadWithOverviewMode = true
            useWideViewPort = true
            intent.getStringExtra(EXTRA_UA)?.takeIf { it.isNotBlank() }?.let { userAgentString = it }
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = AdBlockClient()
        webView.webChromeClient = ChromeClient()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (customView != null) hideCustomView() else finish()
            }
        })

        val referer = intent.getStringExtra(EXTRA_REFERER)
        if (!referer.isNullOrBlank()) {
            webView.loadUrl(url, mapOf("Referer" to referer))
        } else {
            webView.loadUrl(url)
        }
        webView.requestFocus()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                    finishWithZap(1); return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                    finishWithZap(-1); return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun finishWithZap(delta: Int) {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_ZAP, delta))
        finish()
    }

    private fun hideCustomView() {
        customView?.let { container.removeView(it) }
        customView = null
        webView.visibility = View.VISIBLE
        customCallback?.onCustomViewHidden()
        customCallback = null
    }

    private fun shouldBlock(uri: Uri, mainFrame: Boolean, redirect: Boolean): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return true
        if (scheme != "http" && scheme != "https") return true
        if (AdBlocker.isAd(uri)) return true
        if (!mainFrame || redirect) return false
        val host = uri.host ?: return true
        // Bloqueia redirecionamentos da página principal para outros sites (pop-unders)
        return AdBlocker.baseDomain(host) != allowedBase
    }

    private inner class AdBlockClient : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            return if (AdBlocker.isAd(request.url)) AdBlocker.emptyResponse() else null
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val redirect = Build.VERSION.SDK_INT >= 24 && request.isRedirect
            return shouldBlock(request.url, request.isForMainFrame, redirect)
        }

        @Deprecated("Deprecated in Java")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
            return shouldBlock(Uri.parse(url), mainFrame = true, redirect = false)
        }

        override fun onPageFinished(view: WebView, url: String) {
            injectCount = 0
            handler.removeCallbacks(injectRunnable)
            handler.post(injectRunnable)
        }
    }

    private inner class ChromeClient : WebChromeClient() {
        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (customView != null) {
                callback.onCustomViewHidden()
                return
            }
            customView = view
            customCallback = callback
            container.addView(
                view,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
            webView.visibility = View.GONE
        }

        override fun onHideCustomView() {
            hideCustomView()
        }

        override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean = false

        override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult): Boolean {
            result.cancel(); return true
        }

        override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult): Boolean {
            result.cancel(); return true
        }

        override fun getDefaultVideoPoster(): Bitmap? = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    }

    override fun onPause() {
        super.onPause()
        if (::webView.isInitialized) webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) webView.onResume()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::webView.isInitialized) {
            webView.stopLoading()
            container.removeView(webView)
            webView.destroy()
        }
        super.onDestroy()
    }
}

private val CLEANER_JS = """
(function(){try{
window.open=function(){return null;};
if(!document.getElementById('stv-css')){var s=document.createElement('style');s.id='stv-css';s.textContent='.adsbygoogle,ins.adsbygoogle,[id^="google_ads"],[id*="popup"],[class*="popup"],[id*="Popup"],[class*="Popup"],[class*="adsbox"],[class*="ad-container"],[id*="ad-container"],[class*="banner-ad"],[id*="banner-ad"]{display:none!important;}';(document.head||document.documentElement).appendChild(s);}
var b=document.body;if(!b)return;
var kids=b.children;for(var i=kids.length-1;i>=0;i--){var el=kids[i];var cs=window.getComputedStyle(el);var z=parseInt(cs.zIndex)||0;if((cs.position==='fixed'||cs.position==='absolute')&&z>999&&el.tagName!=='VIDEO'&&el.tagName!=='IFRAME'&&!el.querySelector('video,iframe')){el.remove();}}
var full='position:fixed!important;top:0!important;left:0!important;width:100vw!important;height:100vh!important;z-index:2147483646!important;background:#000!important;border:0!important;margin:0!important;';
var v=document.querySelector('video');
if(v){v.style.cssText+=';'+full+'object-fit:contain!important;';if(v.paused){var p=v.play();if(p&&p.catch){p.catch(function(){v.muted=true;v.play();});}}}
else{var fr=document.getElementsByTagName('iframe');var best=null,area=0;for(var j=0;j<fr.length;j++){var r=fr[j].getBoundingClientRect();var a=r.width*r.height;if(a>area){area=a;best=fr[j];}}if(best&&area>40000){best.style.cssText+=';'+full;}}
b.style.overflow='hidden';
}catch(e){}})();
"""

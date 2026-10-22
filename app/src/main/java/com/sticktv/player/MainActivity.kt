package com.sticktv.player

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
class MainActivity : AppCompatActivity() {

    private enum class Mode { AUTO, HLS }

    companion object {
        private const val CAT_ALL = "Todos os canais"
        private const val CAT_FAV = "★ Favoritos"
        private const val MAX_RETRIES = 3
        private val FORMAT_ERRORS = setOf(
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED
        )
    }

    private val scope = MainScope()
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var prefs: Prefs
    private lateinit var repo: PlaylistRepository
    private lateinit var player: ExoPlayer

    private lateinit var playerView: PlayerView
    private lateinit var loading: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var numberInput: TextView
    private lateinit var infoBanner: LinearLayout
    private lateinit var infoLogo: ImageView
    private lateinit var infoName: TextView
    private lateinit var infoGroup: TextView
    private lateinit var panel: LinearLayout
    private lateinit var searchBox: EditText
    private lateinit var settingsButton: Button
    private lateinit var categoryList: RecyclerView
    private lateinit var channelList: RecyclerView
    private lateinit var countText: TextView

    private lateinit var channelAdapter: ChannelAdapter
    private lateinit var categoryAdapter: CategoryAdapter

    private var allChannels: List<Channel> = emptyList()
    private var filtered: List<Channel> = emptyList()
    private var zapList: List<Channel> = emptyList()
    private var categories: List<String> = emptyList()
    private var favorites: Set<String> = emptySet()
    private var selectedCategory = CAT_ALL
    private var pendingCategory: String? = null

    private var current: Channel? = null
    private var pendingChannel: Channel? = null
    private var mode = Mode.AUTO
    private var reachedReady = false
    private var retryCount = 0
    private var inWebFallback = false
    private var stoppedInBackground = false
    private var swallowKey = -1
    private var numberBuffer = ""
    private var lastBackPress = 0L
    private var panelShownAt = 0L
    private var settingsDialog: AlertDialog? = null

    private val extractorsFactory by lazy {
        DefaultExtractorsFactory()
            .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES)
    }

    private val hideInfoRunnable = Runnable { infoBanner.visibility = View.GONE }
    private val retryRunnable = Runnable { current?.let { play(it, mode, isRetry = true) } }
    private val pendingPlayRunnable = Runnable {
        val ch = pendingChannel
        pendingChannel = null
        if (ch != null) play(ch)
    }
    private val searchRunnable = Runnable { applyFilter() }
    private val numberRunnable = Runnable { commitNumber() }
    private val categoryRunnable = Runnable {
        val cat = pendingCategory
        pendingCategory = null
        if (cat != null) selectCategory(cat)
    }

    private val webLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val delta = result.data?.getIntExtra(WebPlayerActivity.EXTRA_ZAP, 0) ?: 0
            if (delta != 0) zap(delta) else showPanel()
        }

    // ---------------------------------------------------------------- ciclo de vida

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = Prefs(this)
        repo = PlaylistRepository(this, prefs)
        favorites = prefs.favorites

        bindViews()
        setupPlayer()
        setupLists()
        setupSearch()
        setupBack()
        settingsButton.setOnClickListener { showSettings() }

        val tablet = resources.configuration.smallestScreenWidthDp >= 600
        if (tablet) {
            panel.layoutParams = panel.layoutParams.apply {
                width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
            }
            categoryList.layoutParams = categoryList.layoutParams.apply {
                width = (160 * resources.displayMetrics.density).toInt()
            }
            showPanel()
        }
        playerView.setOnClickListener {
            if (panel.isVisible) hidePanel() else showPanel()
        }

        loadPlaylist(initial = true)
    }

    override fun onStart() {
        super.onStart()
        if (stoppedInBackground) {
            stoppedInBackground = false
            if (!inWebFallback) current?.let { play(it, mode) }
        }
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(retryRunnable)
        handler.removeCallbacks(pendingPlayRunnable)
        pendingChannel = null
        if (!inWebFallback) {
            player.stop()
            stoppedInBackground = true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        handler.removeCallbacksAndMessages(null)
        settingsDialog?.dismiss()
        if (::player.isInitialized) player.release()
    }

    // ---------------------------------------------------------------- setup

    private fun bindViews() {
        playerView = findViewById(R.id.playerView)
        loading = findViewById(R.id.loading)
        statusText = findViewById(R.id.statusText)
        numberInput = findViewById(R.id.numberInput)
  …4011 tokens truncated…
    }

    private fun focusChannelAt(pos: Int, attempt: Int = 0) {
        if (pos !in filtered.indices) return
        val lm = channelList.layoutManager as LinearLayoutManager
        if (attempt == 0) lm.scrollToPositionWithOffset(pos, channelList.height / 3)
        channelList.post {
            val vh = channelList.findViewHolderForAdapterPosition(pos)
            when {
                vh != null -> vh.itemView.requestFocus()
                attempt < 4 -> handler.postDelayed({ focusChannelAt(pos, attempt + 1) }, 50)
                else -> channelList.requestFocus()
            }
        }
    }

    private fun focusSelectedCategory() {
        val idx = categories.indexOf(selectedCategory).coerceAtLeast(0)
        if (idx >= categories.size) return
        (categoryList.layoutManager as LinearLayoutManager)
            .scrollToPositionWithOffset(idx, categoryList.height / 3)
        categoryList.post {
            categoryList.findViewHolderForAdapterPosition(idx)?.itemView?.requestFocus()
        }
    }

    private fun showInfo(ch: Channel) {
        infoName.text = "${ch.number}   ${ch.name}"
        infoGroup.text = ch.group
        LogoLoader.load(infoLogo, ch.logo)
        if (!panel.isVisible) infoBanner.visibility = View.VISIBLE
        handler.removeCallbacks(hideInfoRunnable)
        handler.postDelayed(hideInfoRunnable, 4000)
    }

    private fun showStatus(msg: String) {
        statusText.text = msg
        statusText.visibility = View.VISIBLE
    }

    private fun hideStatus() {
        statusText.visibility = View.GONE
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(searchBox.windowToken, 0)
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------------- configurações

    private fun showSettings() {
        if (settingsDialog?.isShowing == true) return
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)
        val input = view.findViewById<EditText>(R.id.urlInput)
        val info = view.findViewById<TextView>(R.id.settingsInfo)
        input.setText(prefs.playlistUrl)
        input.setSelection(input.text.length)
        info.text = "Canais carregados: ${allChannels.size}\n" +
            "Dica: segure OK sobre um canal para favoritar.\nStickTV v1.0"

        settingsDialog = AlertDialog.Builder(this)
            .setTitle("Configurações")
            .setView(view)
            .setPositiveButton("Salvar e recarregar") { _, _ ->
                val url = input.text.toString().trim()
                if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
                    toast("URL inválida. Ela deve começar com http:// ou https://")
                    return@setPositiveButton
                }
                applyUrl(url)
            }
            .setNeutralButton("Restaurar padrão") { _, _ ->
                applyUrl(Config.DEFAULT_PLAYLIST_URL)
            }
            .setNegativeButton("Cancelar", null)
            .create()
        settingsDialog?.show()
    }

    private fun applyUrl(url: String) {
        val changed = url != prefs.playlistUrl
        prefs.playlistUrl = url
        if (changed) {
            handler.removeCallbacks(retryRunnable)
            current = null
            player.stop()
            channelAdapter.setPlaying(null)
        }
        loadPlaylist(initial = false)
    }
}

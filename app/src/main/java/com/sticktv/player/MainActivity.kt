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
        infoBanner = findViewById(R.id.infoBanner)
        infoLogo = findViewById(R.id.infoLogo)
        infoName = findViewById(R.id.infoName)
        infoGroup = findViewById(R.id.infoGroup)
        panel = findViewById(R.id.panel)
        searchBox = findViewById(R.id.searchBox)
        settingsButton = findViewById(R.id.settingsButton)
        categoryList = findViewById(R.id.categoryList)
        channelList = findViewById(R.id.channelList)
        countText = findViewById(R.id.countText)
    }

    private fun setupPlayer() {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(10_000, 30_000, 1_500, 3_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val renderers = DefaultRenderersFactory(this).setEnableDecoderFallback(true)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        player = ExoPlayer.Builder(this, renderers)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, true)
            .build()
        player.playWhenReady = true
        playerView.player = player

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                loading.isVisible = playbackState == Player.STATE_BUFFERING
                when (playbackState) {
                    Player.STATE_READY -> {
                        reachedReady = true
                        retryCount = 0
                        hideStatus()
                    }
                    Player.STATE_ENDED -> if (current != null) scheduleRetry()
                    else -> Unit
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                handleError(error)
            }
        })
    }

    private fun setupLists() {
        channelAdapter = ChannelAdapter(
            onClick = { ch -> onChannelChosen(ch) },
            onLongClick = { ch, pos -> toggleFavorite(ch, pos) },
            onLeft = { focusSelectedCategory() }
        )
        channelList.layoutManager = LinearLayoutManager(this)
        channelList.adapter = channelAdapter
        channelList.itemAnimator = null
        channelList.setHasFixedSize(true)
        channelList.setItemViewCacheSize(20)

        categoryAdapter = CategoryAdapter { cat -> onCategoryFocused(cat) }
        categoryList.layoutManager = LinearLayoutManager(this)
        categoryList.adapter = categoryAdapter
        categoryList.itemAnimator = null
        categoryList.setHasFixedSize(true)
    }

    private fun setupSearch() {
        searchBox.doAfterTextChanged {
            handler.removeCallbacks(searchRunnable)
            handler.postDelayed(searchRunnable, 250)
        }
        searchBox.setOnEditorActionListener { _, _, _ ->
            handler.removeCallbacks(searchRunnable)
            applyFilter()
            hideKeyboard()
            if (filtered.isNotEmpty()) focusChannelAt(0)
            true
        }
    }

    private fun setupBack() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    panel.isVisible && searchBox.hasFocus() && !searchBox.text.isNullOrEmpty() ->
                        searchBox.setText("")
                    panel.isVisible && current != null && !inWebFallback ->
                        hidePanel()
                    else -> {
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastBackPress < 2000) {
                            finish()
                        } else {
                            lastBackPress = now
                            toast("Pressione VOLTAR novamente para sair")
                        }
                    }
                }
            }
        })
    }

    // ---------------------------------------------------------------- lista de canais

    private fun loadPlaylist(initial: Boolean) {
        val url = prefs.playlistUrl.trim()
        if (url.isEmpty()) {
            showStatus("Nenhuma lista configurada.\nPressione MENU para configurar.")
            showSettings()
            return
        }
        scope.launch {
            if (initial) {
                val cached = try {
                    repo.loadCached(url)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emptyList()
                }
                if (cached.isNotEmpty()) setChannels(cached, autoplay = true)
            }

            if (allChannels.isEmpty()) {
                showStatus("Carregando lista de canais…")
                loading.visibility = View.VISIBLE
            }

            try {
                val fresh = repo.download(url)
                setChannels(fresh, autoplay = current == null)
                if (!initial) toast("Lista atualizada: ${fresh.size} canais")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (allChannels.isEmpty() || current == null) {
                    showStatus(
                        "Não foi possível carregar a lista.\n${e.message ?: ""}\n\n" +
                            "Pressione MENU para trocar a URL."
                    )
                    if (allChannels.isEmpty()) showSettings()
                } else {
                    toast("Sem conexão com a lista. Usando a cópia salva.")
                }
            } finally {
                loading.isVisible = player.playbackState == Player.STATE_BUFFERING
            }
        }
    }

    private fun setChannels(list: List<Channel>, autoplay: Boolean) {
        allChannels = list
        val groups = LinkedHashSet<String>()
        for (ch in list) groups.add(ch.group)
        categories = listOf(CAT_ALL, CAT_FAV) + groups
        if (selectedCategory !in categories) selectedCategory = CAT_ALL
        categoryAdapter.submit(categories, selectedCategory)
        zapList = list
        applyFilter()

        if (autoplay) {
            val last = prefs.lastChannelUrl
            val ch = list.firstOrNull { it.url == last } ?: list.firstOrNull()
            if (ch != null) play(ch)
        } else {
            val cur = current
            if (cur != null) current = list.firstOrNull { it.url == cur.url } ?: cur
        }
    }

    private fun applyFilter() {
        val q = searchBox.text?.toString().orEmpty().normalizeForSearch()
        filtered = when {
            q.isNotEmpty() -> allChannels.filter { it.searchKey.contains(q) }
            selectedCategory == CAT_ALL -> allChannels
            selectedCategory == CAT_FAV -> allChannels.filter { it.url in favorites }
            else -> allChannels.filter { it.group == selectedCategory }
        }
        channelAdapter.submit(filtered, favorites, current?.url)
        val title = if (q.isNotEmpty()) "Pesquisa" else selectedCategory
        countText.text = "$title  •  ${filtered.size} canais"
    }

    private fun onCategoryFocused(cat: String) {
        if (SystemClock.uptimeMillis() - panelShownAt < 300) return
        pendingCategory = cat
        handler.removeCallbacks(categoryRunnable)
        handler.postDelayed(categoryRunnable, 150)
    }

    private fun selectCategory(cat: String) {
        val searching = !searchBox.text.isNullOrEmpty()
        if (cat == selectedCategory && !searching) return
        selectedCategory = cat
        categoryAdapter.setSelected(cat)
        if (searching) searchBox.setText("")
        handler.removeCallbacks(searchRunnable)
        applyFilter()
        (channelList.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(0, 0)
    }

    private fun onChannelChosen(ch: Channel) {
        zapList = if (searchBox.text.isNullOrEmpty()) filtered else allChannels
        play(ch)
        hidePanel()
    }

    private fun toggleFavorite(ch: Channel, pos: Int) {
        val adding = ch.url !in favorites
        favorites = if (adding) favorites + ch.url else favorites - ch.url
        prefs.favorites = favorites
        channelAdapter.setFavorites(favorites)
        if (pos != RecyclerView.NO_POSITION) channelAdapter.notifyItemChanged(pos)
        toast(if (adding) "★ ${ch.name} adicionado aos favoritos" else "${ch.name} removido dos favoritos")
    }

    // ---------------------------------------------------------------- reprodução

    private fun defaultModeFor(ch: Channel): Mode =
        if (ch.url.contains("m3u8", ignoreCase = true)) Mode.HLS else Mode.AUTO

    private fun play(ch: Channel, newMode: Mode = defaultModeFor(ch), isRetry: Boolean = false) {
        handler.removeCallbacks(retryRunnable)
        handler.removeCallbacks(pendingPlayRunnable)
        pendingChannel = null
        if (!isRetry) retryCount = 0

        current = ch
        mode = newMode
        reachedReady = false
        inWebFallback = false
        prefs.lastChannelUrl = ch.url
        if (!isRetry) hideStatus()

        player.setMediaSource(buildMediaSource(ch, newMode == Mode.HLS))
        player.prepare()
        player.playWhenReady = true

        channelAdapter.setPlaying(ch.url)
        if (!isRetry) showInfo(ch)
    }

    private fun buildMediaSource(ch: Channel, hls: Boolean): MediaSource {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(ch.userAgent ?: Config.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(15_000)
        ch.referer?.let { http.setDefaultRequestProperties(mapOf("Referer" to it)) }

        val dataSource = DefaultDataSource.Factory(this, http)
        val builder = MediaItem.Builder().setUri(ch.url)
        if (hls) builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        val item = builder.build()

        return if (hls) {
            HlsMediaSource.Factory(dataSource)
                .setAllowChunklessPreparation(true)
                .createMediaSource(item)
        } else {
            DefaultMediaSourceFactory(dataSource, extractorsFactory).createMediaSource(item)
        }
    }

    private fun handleError(error: PlaybackException) {
        val ch = current ?: return
        loading.visibility = View.GONE
        val formatError = error.errorCode in FORMAT_ERRORS
        when {
            error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> {
                player.seekToDefaultPosition()
                player.prepare()
            }
            // Link sem extensão que na verdade é HLS
            formatError && !reachedReady && mode == Mode.AUTO ->
                play(ch, Mode.HLS, isRetry = true)
            // Não é stream: é uma página web -> abre no modo web com bloqueio de anúncios
            formatError && !reachedReady && ch.url.startsWith("http", ignoreCase = true) ->
                openWeb(ch)
            else -> scheduleRetry()
        }
    }

    private fun scheduleRetry() {
        if (retryCount < MAX_RETRIES) {
            retryCount++
            showStatus("Reconectando… ($retryCount/$MAX_RETRIES)")
            handler.removeCallbacks(retryRunnable)
            handler.postDelayed(retryRunnable, 2000L * retryCount)
        } else {
            showStatus("Canal indisponível no momento.\nEscolha outro canal ou tente mais tarde.")
        }
    }

    private fun openWeb(ch: Channel) {
        player.stop()
        inWebFallback = true
        hideStatus()
        val intent = Intent(this, WebPlayerActivity::class.java)
            .putExtra(WebPlayerActivity.EXTRA_URL, ch.url)
            .putExtra(WebPlayerActivity.EXTRA_UA, ch.userAgent)
            .putExtra(WebPlayerActivity.EXTRA_REFERER, ch.referer)
        webLauncher.launch(intent)
    }

    private fun zap(delta: Int) {
        val list = zapList.ifEmpty { allChannels }
        if (list.isEmpty()) return
        val base = pendingChannel ?: current
        var idx = if (base == null) -1 else list.indexOf(base)
        if (idx < 0 && base != null) idx = list.indexOfFirst { it.url == base.url }
        val next = list[if (idx < 0) 0 else (idx + delta).mod(list.size)]
        pendingChannel = next
        showInfo(next)
        handler.removeCallbacks(pendingPlayRunnable)
        handler.postDelayed(pendingPlayRunnable, 350)
    }

    // ---------------------------------------------------------------- controle remoto

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code == swallowKey) {
            if (event.action == KeyEvent.ACTION_UP) swallowKey = -1
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_SETTINGS) {
                showSettings()
                return true
            }
            if (!panel.isVisible && handlePlayerKey(event)) return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun handlePlayerKey(event: KeyEvent): Boolean {
        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_DPAD_LEFT -> {
                swallowKey = event.keyCode
                showPanel()
                true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                zap(1); true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                zap(-1); true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_INFO -> {
                (pendingChannel ?: current)?.let { showInfo(it) }
                true
            }
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                onDigit(event.keyCode - KeyEvent.KEYCODE_0)
                true
            }
            else -> false
        }
    }

    private fun onDigit(d: Int) {
        if (numberBuffer.length >= 5) numberBuffer = ""
        numberBuffer += d.toString()
        numberInput.text = numberBuffer
        numberInput.visibility = View.VISIBLE
        handler.removeCallbacks(numberRunnable)
        handler.postDelayed(numberRunnable, 1500)
    }

    private fun commitNumber() {
        val n = numberBuffer.toIntOrNull()
        numberBuffer = ""
        numberInput.visibility = View.GONE
        if (n == null) return
        val ch = allChannels.firstOrNull { it.number == n }
        if (ch != null) {
            zapList = allChannels
            play(ch)
        } else {
            toast("Canal $n não encontrado")
        }
    }

    // ---------------------------------------------------------------- interface

    private fun showPanel() {
        if (panel.isVisible) return
        panelShownAt = SystemClock.uptimeMillis()
        handler.removeCallbacks(hideInfoRunnable)
        infoBanner.visibility = View.GONE
        panel.visibility = View.VISIBLE
        val cur = current
        val pos = if (cur == null) -1 else filtered.indexOf(cur)
        when {
            pos >= 0 -> focusChannelAt(pos)
            filtered.isNotEmpty() -> focusChannelAt(0)
            else -> settingsButton.requestFocus()
        }
    }

    private fun hidePanel() {
        hideKeyboard()
        panel.visibility = View.GONE
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

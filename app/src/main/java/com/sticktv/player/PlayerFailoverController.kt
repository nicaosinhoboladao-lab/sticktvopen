package com.sticktv.player

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
class PlayerFailoverController(
    private val player: ExoPlayer,
    private val scope: CoroutineScope,
    private val health: StreamHealth,
    private val onStatus: (String) -> Unit
) : Player.Listener {

    private var failover: ChannelFailover? = null
    private var switchJob: Job? = null
    private var stallJob: Job? = null
    private var requestedHlsForChannel = false

    init {
        player.addListener(this)
    }

    fun play(channel: Channel, forceHls: Boolean = false) {
        switchJob?.cancel()
        stallJob?.cancel()
        requestedHlsForChannel = forceHls
        val fo = ChannelFailover(channel, health)
        failover = fo
        start(fo.current, forceHls)
    }

    private fun start(variant: StreamVariant, forceHls: Boolean = false) {
        val headers = HashMap<String, String>()
        variant.referer?.takeIf { it.isNotBlank() }?.let { headers["Referer"] = it }

        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(variant.userAgent?.takeIf { it.isNotBlank() } ?: Config.USER_AGENT)
            .setDefaultRequestProperties(headers)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(15_000)

        val item = MediaItem.Builder().setUri(variant.url).apply {
            if (forceHls || variant.url.contains(".m3u8", ignoreCase = true)) {
                setMimeType(MimeTypes.APPLICATION_M3U8)
            }
        }.build()
        val source = DefaultMediaSourceFactory(http).createMediaSource(item)

        player.setMediaSource(source)
        player.prepare()
        player.playWhenReady = true
    }

    override fun onPlayerError(error: PlaybackException) {
        // Ficou atrÃ¡s da janela ao vivo: nÃ£o Ã© falha do canal, sÃ³ volta ao "ao vivo"
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            player.seekToDefaultPosition()
            player.prepare()
            return
        }
        switchToNext()
    }

    override fun onPlaybackStateChanged(state: Int) {
        when (state) {
            Player.STATE_READY -> {
                stallJob?.cancel()
                failover?.markWorking()
            }
            Player.STATE_BUFFERING -> {
                stallJob?.cancel()
                stallJob = scope.launch {
                    delay(STALL_TIMEOUT_MS)
                    if (player.playbackState == Player.STATE_BUFFERING) switchToNext()
                }
            }
            Player.STATE_ENDED -> switchToNext() // canal ao vivo "terminou" = sinal caiu
            else -> Unit
        }
    }

    private fun switchToNext() {
        val fo = failover ?: return
        if (switchJob?.isActive == true) return
        stallJob?.cancel()

        switchJob = scope.launch {
            onStatus("Canal fora do ar, procurando outra fonteâ€¦")
            val next = fo.next()
            if (fo !== failover) return@launch // o usuÃ¡rio trocou de canal no meio do processo

            if (next != null) {
                onStatus("Usando fonte alternativa ${fo.position} de ${fo.total}")
                start(next, forceHls = requestedHlsForChannel || next.url.contains(".m3u8", ignoreCase = true))
            } else {
                player.stop()
                onStatus("Nenhuma fonte disponÃ­vel para este canal.")
            }
        }
    }

    fun release() {
        switchJob?.cancel()
        stallJob?.cancel()
        player.removeListener(this)
    }

    private companion object {
        const val STALL_TIMEOUT_MS = 20_000L
    }
}

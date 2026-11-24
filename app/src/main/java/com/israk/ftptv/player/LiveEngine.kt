package com.israk.ftptv.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.israk.ftptv.FtpTvApp
import com.israk.ftptv.data.Channel
import com.israk.ftptv.data.Playable
import com.israk.ftptv.data.StreamRef
import com.israk.ftptv.data.sources.Net
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One ExoPlayer for the whole session (re-creating it per channel is what makes cheap sticks stutter).
 * Picks the fastest server, falls over to the next one on errors or long stalls, and lets the user pin one.
 */
class LiveEngine(context: Context, private val scope: CoroutineScope, private val listener: Listener) {

    sealed interface State {
        data class Connecting(val ch: Channel, val server: String?, val index: Int, val total: Int) : State
        data class Playing(val ch: Channel, val server: String, val index: Int, val total: Int) : State
        data class Failed(val ch: Channel, val message: String) : State
    }

    fun interface Listener { fun onState(s: State) }

    companion object { const val TAG = "FTPTV" }

    private val app = FtpTvApp.instance
    private val repo = app.repo
    private val main = Handler(Looper.getMainLooper())

    val player: ExoPlayer

    var channel: Channel? = null
        private set
    var order: List<StreamRef> = emptyList()
        private set
    var active: StreamRef? = null
        private set

    private val tried = HashSet<String>()
    private var job: Job? = null
    private var rounds = 0
    private var startedAt = 0L

    init {
        val stable = app.prefs.bufferMode == 1
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                if (stable) 12_000 else 5_000,
                if (stable) 40_000 else 18_000,
                if (stable) 2_500 else 900,
                if (stable) 5_000 else 2_000,
            )
            .setTargetBufferBytes(if (stable) 24 * 1024 * 1024 else 12 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        val renderers = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        val tracks = DefaultTrackSelector(context).apply {
            setParameters(buildUponParameters().setMaxVideoSize(1920, 1080).setExceedRendererCapabilitiesIfNecessary(true).build())
        }
        player = ExoPlayer.Builder(context, renderers)
            .setLoadControl(load)
            .setTrackSelector(tracks)
            .build()
        player.playWhenReady = true
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> {
                        stopWatchdog()
                        rounds = 0
                        val ch = channel ?: return
                        val ref = active ?: return
                        val took = (System.currentTimeMillis() - startedAt).toInt()
                        Log.i(TAG, "OK|${ch.number}|${ch.name}|${repo.sourceTitle(ref.sourceId)}|${took}ms|${order.size}")
                        repo.noteSpeed(ref.sourceId, took.coerceAtMost(8000) / 2, true)
                        listener.onState(State.Playing(ch, repo.sourceTitle(ref.sourceId), order.indexOf(ref) + 1, order.size))
                    }
                    Player.STATE_BUFFERING -> startWatchdog()
                    Player.STATE_ENDED -> { // a live stream should never end: the server dropped us
                        failover("Stream ended")
                    }
                    else -> Unit
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition(); player.prepare(); return
                }
                failover(error.errorCodeName)
            }
        })
    }

    fun play(ch: Channel) {
        job?.cancel()
        stopWatchdog()
        channel = ch
        tried.clear()
        rounds = 0
        order = repo.ordered(ch)
        active = null
        player.stop()
        player.clearMediaItems()
        listener.onState(State.Connecting(ch, null, 0, order.size))
        next()
    }

    /** Manual pick from the server menu; also remembered for this channel. */
    fun useServer(ref: StreamRef) {
        val ch = channel ?: return
        app.prefs.setPinnedSource(ch.id, repo.keyOf(ref))
        job?.cancel()
        tried.clear()
        order = repo.ordered(ch)
        listener.onState(State.Connecting(ch, repo.sourceTitle(ref.sourceId), order.indexOf(ref) + 1, order.size))
        job = scope.launch {
            val p = runCatching { repo.resolve(ref) }.getOrNull()
            if (p == null) { tried += repo.keyOf(ref); next(); return@launch }
            attach(ref, p)
        }
    }

    fun retry() { channel?.let { play(it) } }

    private fun next() {
        val ch = channel ?: return
        job?.cancel()
        job = scope.launch {
            val pick = repo.fastest(order, tried)
            if (pick == null) {
                if (rounds < 1 && order.isNotEmpty()) {
                    rounds++
                    tried.clear()
                    delay(2500)
                    next()
                } else {
                    Log.w(TAG, "FAIL|${ch.number}|${ch.name}|tried ${tried.size} of ${order.size}")
                    listener.onState(State.Failed(ch, if (order.isEmpty()) "No server has this channel right now" else "All servers failed — try again later"))
                }
                return@launch
            }
            val (ref, playable) = pick
            listener.onState(State.Connecting(ch, repo.sourceTitle(ref.sourceId), order.indexOf(ref) + 1, order.size))
            attach(ref, playable)
        }
    }

    private fun attach(ref: StreamRef, p: Playable) {
        active = ref
        startedAt = System.currentTimeMillis()
        player.setMediaSource(mediaSource(p))
        player.prepare()
        player.play()
        startWatchdog()
    }

    private fun mediaSource(p: Playable): MediaSource {
        val ds = OkHttpDataSource.Factory(app.http)
            .setUserAgent(p.headers["User-Agent"] ?: Net.UA)
            .setDefaultRequestProperties(p.headers.filterKeys { it != "User-Agent" })
        val path = p.url.substringBefore('?').lowercase()
        val progressive = path.endsWith(".ts") || path.endsWith(".mp4") || path.endsWith(".flv") || path.endsWith(".mkv")
        val item = MediaItem.Builder().setUri(p.url)
            .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(C.TIME_UNSET).build())
            .apply { if (!progressive) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .build()
        return if (progressive) ProgressiveMediaSource.Factory(ds).createMediaSource(item)
        else HlsMediaSource.Factory(ds).setAllowChunklessPreparation(true).createMediaSource(item)
    }

    private fun failover(why: String) {
        stopWatchdog()
        val ref = active
        if (ref != null) {
            Log.w(TAG, "SKIP|${channel?.number}|${channel?.name}|${repo.sourceTitle(ref.sourceId)}|$why")
            tried += repo.keyOf(ref)
            repo.noteSpeed(ref.sourceId, 0, false)
            // a pinned server that keeps failing shouldn't trap the channel
            channel?.let { ch -> if (app.prefs.pinnedSource(ch.id) == repo.keyOf(ref)) app.prefs.setPinnedSource(ch.id, null) }
        }
        active = null
        player.stop()
        next()
    }

    // a stream stuck "buffering" this long is as good as dead: move on
    private val watchdog = Runnable { if (player.playbackState == Player.STATE_BUFFERING || player.playbackState == Player.STATE_IDLE) failover("Stalled") }

    private fun startWatchdog() {
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, if (app.prefs.bufferMode == 1) 20_000 else 12_000)
    }

    private fun stopWatchdog() = main.removeCallbacks(watchdog)

    fun release() {
        job?.cancel()
        stopWatchdog()
        player.release()
    }
}

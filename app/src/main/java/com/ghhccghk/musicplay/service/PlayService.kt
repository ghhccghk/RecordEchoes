package com.ghhccghk.musicplay.service

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothCodecStatus
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.media.AudioDeviceInfo
import android.media.audiofx.AudioEffect
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util.isBitmapFactorySupportedMimeType
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.util.EventLogger
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import coil3.BitmapImage
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import com.ghhccghk.musicplay.BuildConfig
import com.ghhccghk.musicplay.MainActivity
import com.ghhccghk.musicplay.MainActivity.Companion.playbar
import com.ghhccghk.musicplay.R
import com.ghhccghk.musicplay.data.getLyricCode
import com.ghhccghk.musicplay.data.libraries.RedirectingDataSourceFactory
import com.ghhccghk.musicplay.data.libraries.albumAudioId
import com.ghhccghk.musicplay.data.libraries.lrcAccesskey
import com.ghhccghk.musicplay.data.libraries.lrcId
import com.ghhccghk.musicplay.data.libraries.songHash
import com.ghhccghk.musicplay.data.libraries.songtitle
import com.ghhccghk.musicplay.data.libraries.uri
import com.ghhccghk.musicplay.data.objects.MainViewModelObject
import com.ghhccghk.musicplay.data.objects.MediaViewModelObject
import com.ghhccghk.musicplay.data.searchLyric.searchLyricBase
import com.ghhccghk.musicplay.util.AfFormatInfo
import com.ghhccghk.musicplay.util.AfFormatTracker
import com.ghhccghk.musicplay.util.AudioTrackInfo
import com.ghhccghk.musicplay.util.BtCodecInfo
import com.ghhccghk.musicplay.util.Flags
import com.ghhccghk.musicplay.util.LyricSyncManager
import com.ghhccghk.musicplay.util.NodeBridge
import com.ghhccghk.musicplay.util.ReplayGainAudioProcessor
import com.ghhccghk.musicplay.util.ReplayGainUtil
import com.ghhccghk.musicplay.util.SmartImageCache
import com.ghhccghk.musicplay.util.Tools
import com.ghhccghk.musicplay.util.Tools.getBitrate
import com.ghhccghk.musicplay.util.Tools.getStringStrict
import com.ghhccghk.musicplay.util.Tools.toFramework
import com.ghhccghk.musicplay.util.apihelp.KugouAPi
import com.ghhccghk.musicplay.util.exoplayer.CircularShuffleOrder
import com.ghhccghk.musicplay.util.exoplayer.EndedWorkaroundPlayer
import com.ghhccghk.musicplay.util.exoplayer.GramophoneRenderFactory
import com.ghhccghk.musicplay.util.exoplayer.LastPlayedManager
import com.ghhccghk.musicplay.util.exoplayer.MeiZuLyricsMediaNotificationProvider
import com.ghhccghk.musicplay.util.exoplayer.isManualNotificationUpdate
import com.ghhccghk.musicplay.util.getBooleanStrict
import com.ghhccghk.musicplay.util.getIntStrict
import com.ghhccghk.musicplay.util.others.PlaylistRepository
import com.ghhccghk.musicplay.util.others.toMediaItem
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.gson.Gson
import com.hchen.superlyricapi.SuperLyricData
import com.hchen.superlyricapi.SuperLyricPush
import com.hyperfocus.api.IslandApi
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import com.mocharealm.accompanist.lyrics.core.model.synced.toSyncedLine
import com.mocharealm.accompanist.lyrics.core.parser.AutoParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.random.Random


private val TAG: String
    get() = "PlayService"

@UnstableApi
class PlayService : MediaLibraryService(), MediaSessionService.Listener,
    MediaLibraryService.MediaLibrarySession.Callback, Player.Listener, AnalyticsListener,
    SharedPreferences.OnSharedPreferenceChangeListener {

    companion object {
        const val CHANNEL_ID = "audio_player_channel"
        const val NOTIF_ID = 101
        private const val PENDING_INTENT_SESSION_ID = 0
        private const val PLAYBACK_SHUFFLE_ACTION_ON = "shuffle_on"
        private const val PLAYBACK_SHUFFLE_ACTION_OFF = "shuffle_off"
        private const val PLAYBACK_REPEAT_OFF = "repeat_off"
        private const val PLAYBACK_REPEAT_ALL = "repeat_all"
        private const val PLAYBACK_REPEAT_ONE = "repeat_one"
        const val SERVICE_SET_TIMER = "set_timer"
        const val SERVICE_QUERY_TIMER = "query_timer"
        const val SERVICE_GET_AUDIO_FORMAT = "get_audio_format"
        const val SERVICE_TIMER_CHANGED = "changed_timer"
    }

    private lateinit var mediaSession: MediaLibrarySession
    private var lyric: String = ""
    private var lastSessionId = 0

    // 当前歌词行数
    private var currentLyricIndex: Int = 0
    val gson = Gson()


    //Node js 服务相关
    var isNodeRunning = false
    var isNodeRunError: String = ""
    private val nodeReadyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == NodeBridge.ACTION_NODE_READY) {
                isNodeRunning = true
            }
        }
    }

    // 创建一个 CoroutineScope，默认用 SupervisorJob 和 Main 调度器（UI线程）
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var customCommands: List<CommandButton>
    private lateinit var afFormatTracker: AfFormatTracker
    private var downstreamFormat = hashSetOf<Pair<Any, Pair<Int, Format>>>()
    private val pendingDownstreamFormat = hashSetOf<Pair<Any, Pair<Int, Format>>>()
    private lateinit var playbackHandler: Handler
    private lateinit var handler: Handler
    private var audioSinkInputFormat: Format? = null
    private var audioTrackInfo: AudioTrackInfo? = null
    private var audioTrackInfoCounter = 0
    private var audioTrackReleaseCounter = 0
    private var btInfo: BtCodecInfo? = null
    private var bitrate: Long? = null
    private val bitrateFetcher = CoroutineScope(Dispatchers.IO.limitedParallelism(1))
    private lateinit var repo: PlaylistRepository
    private lateinit var prefs: SharedPreferences
    private val nfBundle : Bundle = Bundle()
    val subDir = "cache/lyrics"
    private val internalPlaybackThread = HandlerThread("ExoPlayer:Playback", Process.THREAD_PRIORITY_AUDIO)
    private var proxy: BtCodecInfo.Companion.Proxy? = null
    private var afTrackFormat: Pair<Any, AfFormatInfo>? = null
    private val pendingAfTrackFormats = hashMapOf<Any, AfFormatInfo>()
    private lateinit var lastPlayedManager: LastPlayedManager
    val endedWorkaroundPlayer
        get() = mediaSession?.player as EndedWorkaroundPlayer?

    private fun getRepeatCommand() =
        when (mediaSession.player!!.repeatMode) {
            Player.REPEAT_MODE_OFF -> customCommands[2]
            Player.REPEAT_MODE_ALL -> customCommands[3]
            Player.REPEAT_MODE_ONE -> customCommands[4]
            else -> throw IllegalArgumentException()
        }

    private fun getShufflingCommand() =
        if (mediaSession.player.shuffleModeEnabled)
            customCommands[1]
        else
            customCommands[0]

    private val timer: Runnable = Runnable {
        if (timerPauseOnEnd) {
            endedWorkaroundPlayer!!.exoPlayer.pauseAtEndOfMediaItems = true
        } else {
            endedWorkaroundPlayer!!.exoPlayer.pause()
        }
        timerDuration = null
    }
    private var timerPauseOnEnd = false
    private var timerDuration: Long? = null
        set(value) {
            field = value
            if (value != null && value > 0) {
                handler.postDelayed(timer, value - SystemClock.elapsedRealtime())
            } else {
                handler.removeCallbacks(timer)
            }
            mediaSession!!.broadcastCustomCommand(
                SessionCommand(SERVICE_TIMER_CHANGED, Bundle.EMPTY),
                Bundle.EMPTY
            )
        }

    private lateinit var rgAp: ReplayGainAudioProcessor
    private var rgMode = 0 // 0 = disabled, 1 = track, 2 = album, 3 = smart

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action.equals("android.bluetooth.a2dp.profile.action.CODEC_CONFIG_CHANGED") &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O /* before 8, only sbc was supported */
            ) {
                btInfo = BtCodecInfo.fromCodecConfig(
                    @SuppressLint("NewApi") IntentCompat.getParcelableExtra(
                        intent, "android.bluetooth.extra.CODEC_STATUS", BluetoothCodecStatus::class.java
                    )?.codecConfig
                )
                Log.d(TAG, "new bluetooth codec config $btInfo")
            }
        }
    }



    @SuppressLint("UseCompatLoadingForDrawables")
    fun run() {
        val base64 = Tools.drawableToBase64(getDrawable(R.drawable.ic_cd)!!)
        val handler by lazy { Handler(Looper.getMainLooper()) }


        val updateLyricsRunnable = object : Runnable {
            override fun run() {
                runCatching {
                    var isPlaying: Boolean?
                    var liveTime: Long
                    var lastLyric = ""
                    val play_bar_lyrics = prefs.getBoolean("play_bar_lyrics",false)

                    handler.post {
                        isPlaying = mediaSession.player.isPlaying

                        runCatching {

                            if (isPlaying) {
                                val car_lyrics = prefs.getBoolean("car_lyrics", false)
                                val status_bar_lyrics = prefs.getBoolean("status_bar_lyrics", false)
                                val newlyric = MediaViewModelObject.newLrcEntries.value

                                MainViewModelObject.syncLyricIndex.intValue = currentLyricIndex

                                liveTime = mediaSession.player.currentPosition

//                                val lrcEntries = MediaViewModelObject.lrcEntries.value

                                val nextIndex = newlyric.lines.indexOfFirst { line ->
                                    line.start >= liveTime
                                }

                                val sendLyric = fun() {
                                    try {
                                        val newLine = newlyric.lines[currentLyricIndex]
                                        val metadata = mediaSession.player.mediaMetadata.toFramework()

                                        when (newLine){
                                            is KaraokeLine -> {
                                                if (lastLyric == newLine.toSyncedLine().content) return
                                            }
                                            is SyncedLine -> {
                                                if (lastLyric == newLine.content) return
                                            }
                                        }

                                        val lyricb = StringBuffer("")
                                        //翻译
                                        val translation = StringBuffer("")

                                        when (newLine){
                                            is KaraokeLine -> {
                                                lyricb.append(newLine.toSyncedLine().content)
                                                translation.append(newLine.toSyncedLine().translation)

                                            }
                                            is SyncedLine -> {
                                                lyricb.append(newLine.content)
                                                translation.append(newLine.translation)
                                            }
                                        }


                                        val lyricResult = lyricb.toString()
                                        val translationResult = translation.toString()

                                        if (playbar.visibility != View.GONE && play_bar_lyrics) {
                                            playbar.findViewById<TextView>(R.id.playbar_artist).text = lyricResult
                                        }

                                        bitrateFetcher.launch {
                                            withContext(Dispatchers.IO){
                                                LyricSyncManager(
                                                    this@PlayService,
                                                    MediaViewModelObject.newLrcEntries.value
                                                ).sync(currentLyricIndex)
                                            }
                                        }

                                        if (car_lyrics || status_bar_lyrics) {
                                            lyric = lyricResult
                                            if (car_lyrics) {
                                                val sessionMetadata =
                                                    mediaSession.player.mediaMetadata
                                                val sessionMediaItem =
                                                    mediaSession.player.currentMediaItem

                                                val newdata =
                                                    sessionMetadata.buildUpon().setTitle(lyric)
                                                        .build()
                                                val newmedia = sessionMediaItem?.buildUpon()
                                                    ?.setMediaMetadata(newdata)?.build()

                                                mediaSession.player.replaceMediaItem(
                                                    mediaSession.player.currentMediaItemIndex,
                                                    newmedia!!
                                                )
                                            }
                                            if (status_bar_lyrics) {// 请注意，非常建议您设置包名，这是判断当前播放应用的唯一途径！！
                                                if (translationResult != "null"){
                                                    SuperLyricPush.onSuperLyric(
                                                        SuperLyricData()
                                                            .setLyric(lyricResult) // 设置歌词
                                                            .setBase64Icon(base64)
                                                            .setPackageName(BuildConfig.APPLICATION_ID) // 设置本软件包名
                                                            .setMediaMetadata(metadata)
                                                            .setTranslation(translationResult)
                                                    ) // 发送歌词
                                                } else {
                                                    SuperLyricPush.onSuperLyric(
                                                        SuperLyricData()
                                                            .setLyric(lyricResult) // 设置歌词
                                                            .setBase64Icon(base64)
                                                            .setMediaMetadata(metadata)
                                                            .setPackageName(BuildConfig.APPLICATION_ID) // 设置本软件包名
                                                    ) // 发送歌词
                                                }
                                            }

                                            mediaSession?.let {
                                                if (Looper.myLooper() != it.player.applicationLooper)
                                                    throw UnsupportedOperationException("wrong looper for triggerNotificationUpdate")
                                                isManualNotificationUpdate = true
                                                triggerNotificationUpdate()
                                                isManualNotificationUpdate = false
                                            }
                                        }
                                        lastLyric = lyricResult
                                    } catch (_: Exception) {
                                    }
                                }

                                var newIndex = currentLyricIndex

                                if (nextIndex != -1 && nextIndex - 1 != currentLyricIndex) {
                                    newIndex = nextIndex - 1
                                } else if (nextIndex == -1 && currentLyricIndex != newlyric.lines.size - 1) {
                                    newIndex = newlyric.lines.size - 1
                                }

                                if (newIndex != currentLyricIndex) {
                                    currentLyricIndex = newIndex
                                    sendLyric()
                                }

                            } else {
                                val sessionMetadata = mediaSession.player.mediaMetadata
                                val sessionMediaItem = mediaSession.player.currentMediaItem
                                val t = sessionMediaItem?.songtitle?.toString()

                                if (sessionMetadata.title != t) {
                                    val newdata = sessionMetadata.buildUpon().setTitle(t).build()
                                    val newmedia =
                                        sessionMediaItem?.buildUpon()?.setMediaMetadata(newdata)
                                            ?.build()
                                    mediaSession.player.replaceMediaItem(
                                        mediaSession.player.currentMediaItemIndex,
                                        newmedia!!
                                    )
                                }


                            }
                        }
                    }

                    handler.postDelayed(this, 70)
                }
            }
        }
        CoroutineScope(Dispatchers.IO).launch {
            handler.post(updateLyricsRunnable)
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): PlayService = this@PlayService
    }

    @UnstableApi
    override fun onCreate() {
        super.onCreate()
        prefs = this.getSharedPreferences("play_setting_prefs", MODE_PRIVATE)
        repo = PlaylistRepository(applicationContext)
        handler = Handler(Looper.getMainLooper())
        rgAp = ReplayGainAudioProcessor()
        val cacheSizeMBa = prefs.getString("image_cache_size", "50")?.toLongOrNull() ?: 950L

        val cacheSizeBytesa = cacheSizeMBa * 1024 * 1024
        SmartImageCache.init(applicationContext, maxSize = cacheSizeBytesa)

        val filter = IntentFilter(NodeBridge.ACTION_NODE_READY)
        LocalBroadcastManager.getInstance(this).registerReceiver(nodeReadyReceiver, filter)
        serviceScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    NodeBridge.startNode() // 这里调用 native 方法
                    isNodeRunning = true
                } catch (e: Exception) {
                    isNodeRunning = false
                    isNodeRunError = e.toString()
                }
            }
        }
        val cacheSizeMB = prefs.getString("song_cache_size", "50")?.toLongOrNull() ?: 950L

        val cacheSizeBytes = cacheSizeMB * 1024 * 1024


        val cache = SimpleCache(
            File(this.getExternalFilesDir(null), "cache/exo_music_cache"),
            LeastRecentlyUsedCacheEvictor(cacheSizeBytes), // 100MB
            StandaloneDatabaseProvider(this)
        )

        val cacheKeyFactory = CacheKeyFactory { dataSpec ->
            val quality = prefs.getString("song_quality", "128").toString()
            val uri = dataSpec.uri
            val ida = uri.getQueryParameter("id")
            val id = (ida + quality)
            if (id != null) {
                id
            } else {
                dataSpec.key ?: uri.toString() // fallback
            }
        }


        val dhttp = DefaultHttpDataSource.Factory()

        val redirectingFactory = RedirectingDataSourceFactory(dhttp)

        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(redirectingFactory) // 自动联网
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .setCacheKeyFactory(cacheKeyFactory)


        playbackHandler = Handler(Looper.getMainLooper())

        customCommands =
            listOf(
                CommandButton.Builder(CommandButton.ICON_SHUFFLE_OFF) // shuffle currently disabled, click will enable
                    .setDisplayName(getString(R.string.shuffle))
                    .setSessionCommand(
                        SessionCommand(PLAYBACK_SHUFFLE_ACTION_ON, Bundle.EMPTY)
                    )
                    .build(),
                CommandButton.Builder(CommandButton.ICON_SHUFFLE_ON) // shuffle currently enabled, click will disable
                    .setDisplayName(getString(R.string.shuffle))
                    .setSessionCommand(
                        SessionCommand(PLAYBACK_SHUFFLE_ACTION_OFF, Bundle.EMPTY)
                    )
                    .build(),
                CommandButton.Builder(CommandButton.ICON_REPEAT_OFF) // repeat currently disabled, click will repeat all
                    .setDisplayName(getString(R.string.repeat_mode))
                    .setSessionCommand(
                        SessionCommand(PLAYBACK_REPEAT_ALL, Bundle.EMPTY)
                    )
                    .build(),
                CommandButton.Builder(CommandButton.ICON_REPEAT_ALL) // repeat all currently enabled, click will repeat one
                    .setDisplayName(getString(R.string.repeat_mode))
                    .setSessionCommand(
                        SessionCommand(PLAYBACK_REPEAT_ONE, Bundle.EMPTY)
                    )
                    .build(),
                CommandButton.Builder(CommandButton.ICON_REPEAT_ONE) // repeat one currently enabled, click will disable
                    .setDisplayName(getString(R.string.repeat_mode))
                    .setSessionCommand(
                        SessionCommand(PLAYBACK_REPEAT_OFF, Bundle.EMPTY)
                    )
                    .build(),
            )


        afFormatTracker = AfFormatTracker(this, playbackHandler,handler)
        afFormatTracker.formatChangedCallback = { format, period ->
            if (period != null) {
                handler.post {
                    val controller = mediaSession.player
                    val currentPeriod = controller?.currentPeriodIndex?.takeIf {
                        it != C.INDEX_UNSET &&
                                (controller?.currentTimeline?.periodCount ?: 0) > it
                    }
                        ?.let { controller!!.currentTimeline.getUidOfPeriod(it) }
                    if (currentPeriod != period) {
                        if (format != null) {
                            pendingAfTrackFormats[period] = format
                        } else {
                            pendingAfTrackFormats.remove(period)
                        }
                    } else {
                        afTrackFormat = format?.let { period to it }
                        mediaSession?.broadcastCustomCommand(
                            SessionCommand(SERVICE_GET_AUDIO_FORMAT, Bundle.EMPTY),
                            Bundle.EMPTY
                        )
                    }
                }
            } else {
                Log.e(TAG, "mediaPeriodId is NULL in formatChangedCallback!!")
            }
        }

        // 初始化 ExoPlayer
        val player = EndedWorkaroundPlayer(
            ExoPlayer.Builder(
            this, GramophoneRenderFactory(
                this, rgAp,this::onAudioSinkInputFormatChanged,
                afFormatTracker::setAudioSink
            ).setPcmEncodingRestrictionLifted(
                prefs.getBoolean("floatoutput", false)
            )
                .setEnableDecoderFallback(true)
                .setEnableAudioTrackPlaybackParams(true)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        )
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setAudioAttributes(
                AudioAttributes
                    .Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(), true
            )
            .setTrackSelector(DefaultTrackSelector(this).apply {
                setParameters(buildUponParameters()
                    .setAllowInvalidateSelectionsOnRendererCapabilitiesChange(true)
                    .setAudioOffloadPreferences(
                        TrackSelectionParameters.AudioOffloadPreferences.Builder()
                            .apply {
                                val config = prefs.getStringStrict("offload", "0")?.toIntOrNull()
                                if (config != null && config > 0 && Flags.OFFLOAD) {
                                    setAudioOffloadMode(TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED)
                                    setIsGaplessSupportRequired(config == 2)
                                }
                            }
                            .build()))
            })
            .setMediaSourceFactory(DefaultMediaSourceFactory(cacheDataSourceFactory))
            .setPlaybackLooper(internalPlaybackThread.looper)
            .build()
        )

        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                // https://github.com/androidx/media/issues/2739
                // TODO(ASAP) wasn't that bug supposed to be fixed?!
                this@PlayService.onAudioSessionIdChanged(audioSessionId)
            }
        })
        player.exoPlayer.addAnalyticsListener(EventLogger())
        player.exoPlayer.addAnalyticsListener(afFormatTracker)
        player.exoPlayer.addAnalyticsListener(this)
        player.exoPlayer.setShuffleOrder(CircularShuffleOrder(player, 0, 0, Random.nextLong()))
        lastPlayedManager = LastPlayedManager(this, player)
        lastPlayedManager.allowSavingState = false
        setListener(this)

        mediaSession =
            MediaLibrarySession
                .Builder(this, player, this)
                // CacheBitmapLoader is required for MeiZuLyricsMediaNotificationProvider
                .setBitmapLoader(CacheBitmapLoader(object : BitmapLoader {
                    // Coil-based bitmap loader to reuse Coil's caching and to make sure we use
                    // the same cover art as the rest of the app, ie MediaStore's cover

                    private val limit by lazy { MediaSession.getBitmapDimensionLimit(this@PlayService) }

                    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> {
                        return CallbackToFutureAdapter.getFuture { completer ->
                            imageLoader.enqueue(
                                ImageRequest.Builder(this@PlayService)
                                    .data(data)
                                    .memoryCacheKey(data.hashCode().toString())
                                    .size(limit, limit)
                                    .allowHardware(false)
                                    .target(
                                        onStart = { _ ->
                                            // We don't need or want a placeholder.
                                        },
                                        onSuccess = { result ->
                                            completer.set((result as BitmapImage).bitmap)
                                        },
                                        onError = { e ->
                                            completer.setException(
                                                Exception(
                                                    "coil onError called for byte array"
                                                )
                                            )
                                        }
                                    )
                                    .build())
                                .also {
                                    completer.addCancellationListener(
                                        { it.dispose() },
                                        ContextCompat.getMainExecutor(
                                            this@PlayService
                                        )
                                    )
                                }
                            "coil load for ${data.hashCode()}"
                        }
                    }

                    fun loadBitmap(
                        uri: Uri,
                        hash: String?
                    ): ListenableFuture<Bitmap> {
                        return CallbackToFutureAdapter.getFuture { completer ->
                            imageLoader.enqueue(
                                ImageRequest.Builder(this@PlayService)
                                    .data(SmartImageCache.getCachedUri(uri.toString(),hash))
                                    .size(limit, limit)
                                    .allowHardware(false)
                                    .target(
                                        onStart = { _ ->
                                            // We don't need or want a placeholder.
                                        },
                                        onSuccess = { result ->
                                            completer.set((result as BitmapImage).bitmap)
                                        },
                                        onError = { _ ->
                                            completer.setException(
                                                Exception(
                                                    "coil onError called" +
                                                            " (normal if no album art exists)"
                                                )
                                            )
                                        }
                                    )
                                    .build())
                                .also {
                                    completer.addCancellationListener(
                                        { it.dispose() },
                                        ContextCompat.getMainExecutor(
                                            this@PlayService
                                        )
                                    )
                                }
                            "coil load for $uri"
                        }
                    }

                    override fun supportsMimeType(mimeType: String): Boolean {
                        return isBitmapFactorySupportedMimeType(mimeType)
                    }

                    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
                        return loadBitmap(uri, uri.toString())
                    }

                    override fun loadBitmapFromMetadata(metadata: MediaMetadata): ListenableFuture<Bitmap>? {
                        val hash = metadata.songHash
                        return metadata.artworkUri?.let { loadBitmap(it, hash) }
                    }
                }))
                .setSessionActivity(
                    PendingIntent.getActivity(
                        this,
                        PENDING_INTENT_SESSION_ID,
                        Intent(this, MainActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                )
                .setSystemUiPlaybackResumptionOptIn(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                .build()
        addSession(mediaSession!!)

        run()

        CoroutineScope(Dispatchers.Main).launch {
            val list = repo.loadPlaylist().first()  // 只取一次
            if (list.isNotEmpty()) {
                val mediaItems = list.map { it.toMediaItem() }
                val last = prefs.getInt("lastplayitem", -1)
                player.setMediaItems(mediaItems)
                player.prepare()
                if (last != -1) {
                    player.seekToDefaultPosition(last)
                }
            }
        }

        mediaSession.broadcastCustomCommand(
            SessionCommand(SERVICE_GET_AUDIO_FORMAT, Bundle.EMPTY),
            Bundle.EMPTY
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O /* before 8, only sbc was supported */) {
            proxy = BtCodecInfo.getCodec(this) {
                Log.d("GramophonePlaybackService", "first bluetooth codec config $btInfo")
                btInfo = it
                mediaSession?.broadcastCustomCommand(
                    SessionCommand(SERVICE_GET_AUDIO_FORMAT, Bundle.EMPTY),
                    Bundle.EMPTY
                )
            }
        }

        player.exoPlayer.addAnalyticsListener(afFormatTracker)

        val name = "Media Control"
        val descriptionText = "Media Control Notification Channel"
        val importance = NotificationManager.IMPORTANCE_LOW
        val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
            description = descriptionText
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        val notificationManager: NotificationManager =
            ContextCompat.getSystemService(
                this,
                NotificationManager::class.java
            ) as NotificationManager
        notificationManager.createNotificationChannel(channel)

        val notificationProvider =
            DefaultMediaNotificationProvider.Builder(this)
                .setNotificationId(NOTIF_ID)
                .setChannelId(CHANNEL_ID)
                .build()

        notificationProvider.setSmallIcon(R.drawable.ic_cd)

        player.addListener(this)

        this.setMediaNotificationProvider(notificationProvider)
        this.setMediaNotificationProvider(MeiZuLyricsMediaNotificationProvider(this, { lyric }, nfBundle))

    }

    // When destroying, we should release server side player
    // alongside with the mediaSession.
    override fun onDestroy() {
        Log.i(TAG, "+onDestroy()")
        unregisterReceiver(btReceiver)
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        // Important: this must happen before sending stop() as that changes state ENDED -> IDLE
        lastPlayedManager.save()
        mediaSession!!.player.stop()
        handler.removeCallbacks(timer)
        proxy?.let {
            it.adapter.closeProfileProxy(BluetoothProfile.A2DP, it.a2dp)
        }
        mediaSession!!.release()
        mediaSession!!.player.release()
        broadcastAudioSessionClose()
        internalPlaybackThread.quitSafely()
        super.onDestroy()
        Log.i(TAG, "-onDestroy()")
    }

    // Configure commands available to the controller in onConnect()
    override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo)
            : MediaSession.ConnectionResult {
        Log.i(TAG, "onConnect(): $controller")
        val builder = MediaSession.ConnectionResult.AcceptedResultBuilder(session)
        val availableSessionCommands =
            MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
        if (session.isMediaNotificationController(controller)
            || session.isAutoCompanionController(controller)
            || session.isAutomotiveController(controller)
        ) {
            // currently, all custom actions are only useful when used by notification
            // other clients hopefully have repeat/shuffle buttons like MCT does
            for (commandButton in customCommands) {
                // Add custom command to available session commands.
                commandButton.sessionCommand?.let { availableSessionCommands.add(it) }
            }
            if (this.mediaSession.player?.currentTimeline?.isEmpty == false) {
                builder.setCustomLayout(
                    ImmutableList.of(
                        getRepeatCommand(),
                        getShufflingCommand()
                    )
                )
            }
        }
        if (controller.connectionHints.getBoolean("PrepareWhenReady", false) &&
            this.mediaSession.player?.currentTimeline?.isEmpty == false) {
            handler.post { this.mediaSession.player?.prepare() }
        }
        availableSessionCommands.add(SessionCommand(SERVICE_SET_TIMER, Bundle.EMPTY))
        availableSessionCommands.add(SessionCommand(SERVICE_QUERY_TIMER, Bundle.EMPTY))
        availableSessionCommands.add(SessionCommand(SERVICE_GET_AUDIO_FORMAT, Bundle.EMPTY))
        return builder.setAvailableSessionCommands(availableSessionCommands.build()).build()
    }

    override fun onMediaMetadataChanged(metadata: MediaMetadata) {
        val albumId = mediaSession.player.currentMediaItem?.albumAudioId?: ""
        val hash = mediaSession.player.currentMediaItem?.songHash

        Log.d("分享歌曲","hash = $hash albumId = $albumId")

        val bundle = IslandApi.isLandMusicShare(
            addpic = Bundle(),
            title = mediaSession.player.mediaMetadata.title.toString(),
            content = mediaSession.player.mediaMetadata.artist.toString(),
            shareContent = "https://activity.kugou.com/share/v-98650b10/index.html?hash=$hash&album_audio_id=$albumId"
        )
        nfBundle.putAll(bundle)
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int
    ) {
        if (oldPosition.periodUid != newPosition.periodUid) {
            var changed = false
            downstreamFormat.toSet().forEach {
                if (newPosition.periodUid != it.first) {
                    downstreamFormat.remove(it)
                    changed = true
                }
            }
            pendingDownstreamFormat.toSet().forEach {
                if (newPosition.periodUid == it.first) {
                    downstreamFormat.add(it)
                    pendingDownstreamFormat.remove(it)
                    changed = true
                }
            }
            if (afTrackFormat?.first != newPosition.periodUid) {
                afTrackFormat = null
                changed = true
            }
            pendingAfTrackFormats[newPosition.periodUid]?.let { format ->
                afTrackFormat = newPosition.periodUid!! to format
                pendingAfTrackFormats.remove(newPosition.periodUid)
                changed = true
            }
            if (changed) {
                mediaSession?.broadcastCustomCommand(
                    SessionCommand(SERVICE_GET_AUDIO_FORMAT, Bundle.EMPTY),
                    Bundle.EMPTY
                )
            }
        }
    }


    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle
    ): ListenableFuture<SessionResult> {
        return Futures.immediateFuture(
            when (customCommand.customAction) {
                PLAYBACK_SHUFFLE_ACTION_ON -> {
                    this.mediaSession.player!!.shuffleModeEnabled = true
                    SessionResult(SessionResult.RESULT_SUCCESS)
                }

                PLAYBACK_SHUFFLE_ACTION_OFF -> {
                    this.mediaSession.player!!.shuffleModeEnabled = false
                    SessionResult(SessionResult.RESULT_SUCCESS)
                }

                SERVICE_SET_TIMER -> {
                    // 0 = clear timer; 0 with pauseOnEnd true will pause on end of current song
                    val duration = customCommand.customExtras.getInt("duration")
                    val pauseOnEnd = customCommand.customExtras.getBoolean("pauseOnEnd")
                    if (duration > 0) {
                        timerPauseOnEnd = pauseOnEnd
                        timerDuration = SystemClock.elapsedRealtime() + duration
                    } else {
                        timerDuration = null
                        this.endedWorkaroundPlayer!!.exoPlayer.pauseAtEndOfMediaItems = pauseOnEnd
                    }
                    SessionResult(SessionResult.RESULT_SUCCESS)
                }

                SERVICE_QUERY_TIMER -> {
                    SessionResult(SessionResult.RESULT_SUCCESS).also {
                        timerDuration?.let { td ->
                            it.extras.putInt("duration", (td - SystemClock.elapsedRealtime()).toInt())
                            it.extras.putBoolean("pauseOnEnd", timerPauseOnEnd)
                        } ?: it.extras.putBoolean("pauseOnEnd",
                            this.endedWorkaroundPlayer!!.exoPlayer.pauseAtEndOfMediaItems)
                    }
                }

                SERVICE_GET_AUDIO_FORMAT -> {
                    SessionResult(SessionResult.RESULT_SUCCESS).also { res ->
                        if (downstreamFormat.isNotEmpty()) {
                            res.extras.putParcelableArrayList(
                                "file_format",
                                ArrayList(downstreamFormat.map { Bundle().apply {
                                    putInt("type", it.second.first)
                                    val bitrate = bitrate
                                    // TODO: should this be done here? this will create a new format object every query
                                    val format = if (it.second.first == C.TRACK_TYPE_AUDIO &&
                                        bitrate != null &&
                                        it.second.second.sampleMimeType == MimeTypes.AUDIO_OPUS) {
                                        it.second.second.buildUpon().setAverageBitrate(bitrate.toInt()).build()
                                    } else it.second.second
                                    putBundle("format", format.toBundle())
                                } })
                            )
                        }
                        res.extras.putBundle("sink_format", audioSinkInputFormat?.toBundle())
                        res.extras.putParcelable("track_format", audioTrackInfo)
                        res.extras.putParcelable("hal_format", afTrackFormat?.second)
                        bitrate?.let { value -> res.extras.putLong("bitrate", value) }
                        if (afFormatTracker.format?.routedDeviceType == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) {
                            res.extras.putParcelable("bt", btInfo)
                        }
                    }
                }


                PLAYBACK_REPEAT_OFF -> {
                    this.mediaSession.player!!.repeatMode = Player.REPEAT_MODE_OFF
                    SessionResult(SessionResult.RESULT_SUCCESS)
                }

                PLAYBACK_REPEAT_ONE -> {
                    this.mediaSession.player!!.repeatMode = Player.REPEAT_MODE_ONE
                    SessionResult(SessionResult.RESULT_SUCCESS)
                }

                PLAYBACK_REPEAT_ALL -> {
                    this.mediaSession.player!!.repeatMode = Player.REPEAT_MODE_ALL
                    SessionResult(SessionResult.RESULT_SUCCESS)
                }

                else -> {
                    SessionResult(SessionError.ERROR_BAD_VALUE)
                }
            })
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        super<Player.Listener>.onMediaItemTransition(mediaItem, reason)
        val prevIndex = mediaSession.player.getPreviousMediaItemIndex()
        val car_lyrics = prefs.getBoolean("car_lyrics", false)

        if (car_lyrics) {
            if (prevIndex != C.INDEX_UNSET) {
                val previousItem = mediaSession.player.getMediaItemAt(prevIndex)
                val sessionMetadata = previousItem.mediaMetadata
                val te = previousItem?.songtitle?.toString()
                if (sessionMetadata.title != te) {
                    val newdata = sessionMetadata.buildUpon().setTitle(te).build()
                    val newmedia = previousItem?.buildUpon()?.setMediaMetadata(newdata)?.build()
                    mediaSession.player.replaceMediaItem(
                        prevIndex,
                        newmedia!!
                    )
                }
            }
        }

        bitrate = null
        bitrateFetcher.launch {
            bitrate = mediaItem?.getBitrate() // TODO subtract cover size
            mediaSession?.broadcastCustomCommand(
                SessionCommand(SERVICE_GET_AUDIO_FORMAT, Bundle.EMPTY),
                Bundle.EMPTY
            )
        }

        val fileName = Tools.sanitizeFileName("${mediaSession.player.currentMediaItem?.mediaId}.lrc")

        val cachedData = Tools.readFromSubdirCache(this.applicationContext, subDir, fileName)

        if (cachedData != null) {
            val autoParserLyric = AutoParser.Builder()
                .build()
            val lyricss = autoParserLyric.parse(cachedData)
            MediaViewModelObject.newLrcEntries.value = lyricss
        } else {
            serviceScope.launch {
                if (!MainActivity.isNodeRunning?: false) return@launch

                val item = mediaSession.player.currentMediaItem
                val hashA = item?.uri?.getQueryParameter("hash") ?: ""
                val lyricId = item?.lrcId.orEmpty()
                val lyricAccess = item?.lrcAccesskey.orEmpty()

                // 1. 先直接用 lyricId + accessKey 尝试获取
                val firstAttempt = fetchLyrics(lyricId, lyricAccess)
                if (firstAttempt != null) {
                    cacheAndLoadLyrics(firstAttempt)
                    return@launch
                }

                // 2. 如果失败，尝试搜索
                val searchJson = withContext(Dispatchers.IO) {
                    KugouAPi.getSearchSongLyrics(hash = hashA)
                }
                if (searchJson.isNullOrEmpty() || searchJson == "502" || searchJson == "404") {
                    return@launch
                }

                try {
                    val searchResult = gson.fromJson(searchJson, searchLyricBase::class.java)
                    val candidate = searchResult.candidates.getOrNull(0)
                    val secondAttempt = fetchLyrics(
                        id = candidate?.id.orEmpty(),
                        accessKey = candidate?.accesskey.orEmpty()
                    )
                    if (secondAttempt != null) {
                        cacheAndLoadLyrics(secondAttempt)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

        }

    }

    override fun onAudioSessionIdChanged(audioSessionId: Int) {
        if (audioSessionId != lastSessionId) {
            broadcastAudioSessionClose()
            lastSessionId = audioSessionId
            broadcastAudioSession()
        }
    }


    private fun broadcastAudioSessionClose() {
        if (lastSessionId != 0) {
            Log.i(TAG, "broadcast audio session close: $lastSessionId")
            sendBroadcast(Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, lastSessionId)
            })
            lastSessionId = 0
        }
    }

    private suspend fun fetchLyrics(id: String, accessKey: String): String? {
        val json = withContext(Dispatchers.IO) {
            KugouAPi.getSongLyrics(id = id, accesskey = accessKey, fmt = "krc", decode = true)
        }
        return try {
            val result = gson.fromJson(json, getLyricCode::class.java)
            result.decodeContent
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun cacheAndLoadLyrics(content: String) {
        val fileName = Tools.sanitizeFileName("${mediaSession.player.currentMediaItem?.mediaId}.lrc")
        Tools.writeToSubdirCache(this.applicationContext, subDir, fileName, content)
        Tools.readFromSubdirCache(this.applicationContext, subDir, fileName)?.let { cached ->
            val autoParserLyric = AutoParser.Builder().build()
            val lyricss = autoParserLyric.parse(cached)
            MediaViewModelObject.newLrcEntries.value = lyricss
        }


    }


    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) {
            serviceScope.launch {
                if (mediaSession.player.playbackState != Player.STATE_IDLE && mediaSession.player.currentTimeline.isEmpty.not()) {
                    val index = mediaSession.player.currentMediaItemIndex
                    Tools.saveCurrentPlaylist(mediaSession.player, repo)
                    prefs.edit().putInt("lastplayitem", index).apply()
                    Log.d("ExoPlayer", "当前播放索引：$index")
                } else {
                    Log.d("ExoPlayer", "播放列表未就绪")
                }

            }
        }
    }

    override fun onPlaybackStateChanged(state: Int) {
        val car_lyrics = prefs.getBoolean("car_lyrics", false)
        when (state) {
            Player.STATE_IDLE -> {
                println("空闲")
                var changed = false
                if (afTrackFormat != null) {
                    Log.e(TAG, "leaked track format: $afTrackFormat")
                    afTrackFormat = null
                    changed = true
                }
                if (downstreamFormat.isNotEmpty()) {
                    Log.e(TAG, "leaked downstream formats: $downstreamFormat")
                    downstreamFormat.clear()
                    changed = true
                }
                if (pendingAfTrackFormats.isNotEmpty()) {
                    Log.e(TAG, "leaked pending track formats: $pendingAfTrackFormats")
                    pendingAfTrackFormats.clear()
                }
                if (pendingDownstreamFormat.isNotEmpty()) {
                    Log.e(TAG, "leaked pending downstream formats: $pendingDownstreamFormat")
                    pendingDownstreamFormat.clear()
                }
                if (changed) {
                    mediaSession?.broadcastCustomCommand(
                        SessionCommand(SERVICE_GET_AUDIO_FORMAT, Bundle.EMPTY),
                        Bundle.EMPTY
                    )
                }
            }
            Player.STATE_BUFFERING -> println("缓冲中")
            Player.STATE_READY -> println("准备好")
            Player.STATE_ENDED -> {
                if (car_lyrics) {
                    val sessionMetadata = mediaSession.player.mediaMetadata
                    val sessionMediaItem = mediaSession.player.currentMediaItem
                    val t = sessionMediaItem?.songtitle?.toString()

                    if (t != sessionMetadata.title){
                        val newdata = sessionMetadata.buildUpon().setTitle(t).build()
                        val newmedia =
                            sessionMediaItem?.buildUpon()?.setMediaMetadata(newdata)?.build()

                        mediaSession.player.replaceMediaItem(
                            mediaSession.player.currentMediaItemIndex,
                            newmedia!!
                        )
                    }
                }
                println("播放结束")
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession {
        return mediaSession
    }

    override fun onTimelineChanged(timeline: Timeline, reason: @Player.TimelineChangeReason Int) {
        if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
            refreshMediaButtonCustomLayout()
            computeRgMode()
        }
        pendingDownstreamFormat.toSet().forEach {
            if (timeline.getIndexOfPeriod(it.first) == C.INDEX_UNSET) {
                // This period is going away.
                pendingDownstreamFormat.remove(it)
            }
        }
        pendingAfTrackFormats.toMap().forEach { (key, _) ->
            if (timeline.getIndexOfPeriod(key) == C.INDEX_UNSET) {
                // This period is going away.
                pendingAfTrackFormats.remove(key)
            }
        }
    }

    override fun onAudioTrackInitialized(
        eventTime: AnalyticsListener.EventTime,
        audioTrackConfig: AudioSink.AudioTrackConfig
    ) {
        audioTrackInfoCounter++
        audioTrackInfo = AudioTrackInfo.fromMedia3AudioTrackConfig(audioTrackConfig)
        mediaSession?.broadcastCustomCommand(
            SessionCommand(SERVICE_GET_AUDIO_FORMAT, Bundle.EMPTY),
            Bundle.EMPTY
        )
    }

    override fun onAudioTrackReleased(
        eventTime: AnalyticsListener.EventTime,
        audioTrackConfig: AudioSink.AudioTrackConfig
    ) {
        // Normally called after the replacement has been initialized, but if old track is released
        // without replacement, we want to instantly know that instead of keeping stale data.
        if (++audioTrackReleaseCounter == audioTrackInfoCounter) {
            audioTrackInfo = null
            mediaSession?.broadcastCustomCommand(
                SessionCommand(SERVICE_GET_AUDIO_FORMAT, Bundle.EMPTY),
                Bundle.EMPTY
            )
        }
    }

    override fun onLoadCanceled(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData
    ) {
        pendingDownstreamFormat.removeAll { eventTime.mediaPeriodId?.periodUid == it.first }
    }


    override fun onDownstreamFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        mediaLoadData: MediaLoadData
    ) {
        if (eventTime.mediaPeriodId == null) { // https://github.com/androidx/media/issues/2812
            Log.e(TAG, "mediaPeriodId is NULL in onDownstreamFormatChanged()!!")
            return
        }
        val controller = mediaSession.player
        val currentPeriod = controller?.currentPeriodIndex?.takeIf { it != C.INDEX_UNSET &&
                (controller?.currentTimeline?.periodCount ?: 0) > it }
            ?.let { controller!!.currentTimeline.getUidOfPeriod(it) }
        val item = eventTime.mediaPeriodId!!.periodUid to
                (mediaLoadData.trackType to mediaLoadData.trackFormat!!)
        if (currentPeriod != item.first) {
            pendingDownstreamFormat += item
        } else {
            downstreamFormat += item
            mediaSession?.broadcastCustomCommand(
                SessionCommand(SERVICE_GET_AUDIO_FORMAT, Bundle.EMPTY),
                Bundle.EMPTY
            )
        }
    }

    private fun onAudioSinkInputFormatChanged(inputFormat: Format?) {
        audioSinkInputFormat = inputFormat
        mediaSession?.broadcastCustomCommand(
            SessionCommand(SERVICE_GET_AUDIO_FORMAT, Bundle.EMPTY),
            Bundle.EMPTY
        )
    }

    override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
       Log.i(TAG, "onDisconnected(): $controller")
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        if (key == null || key == "rg_mode") {
            rgMode = prefs.getStringStrict("rg_mode", "0")!!.toInt()
            computeRgMode()
        }
        if (key == null || key == "rg_drc") {
            val drc = prefs.getBooleanStrict("rg_drc", true)
            rgAp.setReduceGain(!drc)
        }
        if (key == null || key == "rg_rg_gain") {
            val rgGain = prefs.getIntStrict("rg_rg_gain", 15)
            rgAp.setRgGain(rgGain - 15)
        }
        if (key == null || key == "rg_no_rg_gain" || key == "rg_boost_gain") {
            val nonRgGain = prefs.getIntStrict("rg_no_rg_gain", 0)
            val boostGain = prefs.getIntStrict("rg_boost_gain", 0)
            rgAp.setNonRgGain(-nonRgGain - boostGain)
            rgAp.setBoostGain(boostGain)
        }
    }

    private fun refreshMediaButtonCustomLayout() {
        val isEmpty = mediaSession.player?.currentTimeline?.isEmpty != false
        mediaSession!!.connectedControllers.forEach {
            if (mediaSession!!.isMediaNotificationController(it)
                || mediaSession!!.isAutoCompanionController(it)
                || mediaSession!!.isAutomotiveController(it)) {
                mediaSession!!.setCustomLayout(it, if (isEmpty) emptyList() else
                    ImmutableList.of(getRepeatCommand(), getShufflingCommand()))
            }
        }
    }

    private fun computeRgMode() {
        val controller = mediaSession.player
        rgAp.setMode(when (rgMode) {
            0 -> ReplayGainUtil.Mode.None
            1 -> ReplayGainUtil.Mode.Track
            2 -> ReplayGainUtil.Mode.Album
            3 -> {
                val item = controller?.currentMediaItem
                val idx = controller?.currentMediaItemIndex ?: 0
                val count = controller?.mediaItemCount
                val next = if (idx + 1 >= (count ?: 0)) null else
                    controller?.getMediaItemAt(idx + 1)
                val prev = if (idx - 1 < 0 || (count ?: 0) == 0) null else
                    controller?.getMediaItemAt(idx - 1)
                if (item != null && (item.mediaMetadata.songHash == next?.mediaMetadata?.songHash ||
                            item.mediaMetadata.songHash == prev?.mediaMetadata?.songHash))
                    ReplayGainUtil.Mode.Album
                else ReplayGainUtil.Mode.Track
            }
            else -> throw IllegalArgumentException("invalid rg mode $rgMode")
        })
    }

    private fun broadcastAudioSession() {
        if (lastSessionId != 0) {
            Log.i(TAG, "broadcast audio session open: $lastSessionId")
            sendBroadcast(Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, lastSessionId)
                putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
            })
        } else {
            Log.e(TAG, "session id is 0? why????? THIS MIGHT BREAK EQUALIZER")
        }
    }

}
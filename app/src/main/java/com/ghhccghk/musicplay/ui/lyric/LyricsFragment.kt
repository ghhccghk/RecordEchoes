@file:OptIn(ExperimentalMaterial3WindowSizeClassApi::class)

package com.ghhccghk.musicplay.ui.lyric

import android.animation.ValueAnimator
import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.scale
import androidx.core.graphics.toColorInt
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.ghhccghk.musicplay.MainActivity
import com.ghhccghk.musicplay.data.libraries.artists
import com.ghhccghk.musicplay.data.libraries.songHash
import com.ghhccghk.musicplay.data.libraries.songtitle
import com.ghhccghk.musicplay.data.objects.MediaViewModelObject
import com.ghhccghk.musicplay.databinding.FragmentLyricsBinding
import com.ghhccghk.musicplay.ui.components.ModalScaffold
import com.ghhccghk.musicplay.ui.components.adaptive.AdaptiveLayoutProvider
import com.ghhccghk.musicplay.ui.components.background.BackgroundVisualState
import com.ghhccghk.musicplay.ui.components.share.ShareContext
import com.ghhccghk.musicplay.ui.components.share.ShareScreen
import com.ghhccghk.musicplay.ui.components.share.ShareViewModel
import com.ghhccghk.musicplay.util.SmartImageCache
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.ui.composable.lyrics.KaraokeBreathingDotsDefaults
import com.mocharealm.accompanist.lyrics.ui.composable.lyrics.KaraokeLyricsView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.compose.koinViewModel

class LyricsFragment : Fragment() {

    private var _binding: FragmentLyricsBinding? = null
    private val binding get() = _binding!!

    private val play: Player get() = MainActivity.controllerFuture.get()
    private lateinit var context: Context
    private var bitmap: Bitmap? = null
    private var currentJob: Job? = null
    private var wrappedContext: Context? = null

    private val prefs by lazy { MainActivity.lontext.getSharedPreferences("play_setting_prefs", MODE_PRIVATE) }
    private val colorbg by lazy { prefs.getBoolean("setting_color_background_set", false) }

    private var fullPlayerFinalColor: Int = MediaViewModelObject.surfaceTransition.intValue
    private var colorSecondaryContainerFinalColor: Int = Color.BLACK
    private var colorOnSecondaryContainerFinalColor: Int = Color.BLACK

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: android.os.Bundle?
    ): View {
        _binding = FragmentLyricsBinding.inflate(inflater, container, false)
        context = requireContext()

        // Compose UI
        binding.lyricsContainerComposeView.setContent {
            AdaptiveLayoutProvider {
                LyricsScreen()
            }
        }

        addPlayerListener()
        updateBg()
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        removePlayerListener()
        _binding = null
    }

    private fun addPlayerListener() {
        play.addListener(object : Player.Listener {
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                if (isAdded && view != null) updateBg()
            }
        })
    }

    private fun removePlayerListener() {
        play.removeListener(object : Player.Listener {})
    }

    /** --------------------- Compose UI --------------------- **/

    @Composable
    fun LyricsScreen(shareViewModel: ShareViewModel = koinViewModel()) {
        val listState = rememberLazyListState()
        val lyrics = MediaViewModelObject.newLrcEntries.value
        var isShareVisible by remember { mutableStateOf(false) }

        val currentPosition by rememberPlaybackPosition(play)

        Box(modifier = Modifier.fillMaxSize()) {
            KaraokeLyricsView(
                listState = listState,
                lyrics = lyrics,
                currentPosition = currentPosition,
                onLineClicked = { play.seekTo(it.start.toLong()) },
                onLinePressed = { line ->
                    val context = ShareContext(
                        lyrics = lyrics,
                        initialLine = line as KaraokeLine,
                        backgroundState = BackgroundVisualState(bitmap?.asImageBitmap(), false),
                        title = play.currentMediaItem?.songtitle ?: "",
                        artist = play.currentMediaItem?.artists?.joinToString() ?: ""
                    )
                    shareViewModel.prepareForSharing(context)
                    isShareVisible = true
                },
                modifier = Modifier.padding(horizontal = 12.dp),
                normalLineTextStyle = LocalTextStyle.current.copy(
                    fontSize = 34.sp,
                    fontStyle = FontStyle.Normal,
                    fontWeight = when (prefs.getString("lyric_font_weight", "Bold")) {
                        "Thin" -> FontWeight.Thin
                        "ExtraLight" -> FontWeight.ExtraLight
                        "Light" -> FontWeight.Light
                        "Regular" -> FontWeight.Normal
                        "Medium" -> FontWeight.Medium
                        "SemiBold" -> FontWeight.SemiBold
                        "Bold" -> FontWeight.Bold
                        "ExtraBold" -> FontWeight.ExtraBold
                        "Black" -> FontWeight.Black
                        else -> FontWeight.ExtraBold
                    },
                    textMotion = TextMotion.Animated,
                ),
                verticalFadeMask = Modifier,
                textColor = if (colorbg) { androidx.compose.ui.graphics.Color(colorOnSecondaryContainerFinalColor) } else { androidx.compose.ui.graphics.Color(Color.WHITE) },
                breathingDotsDefaults = KaraokeBreathingDotsDefaults(
                    number = 4,
                    breathingDotsColor = if (colorbg) {
                        androidx.compose.ui.graphics.Color(colorOnSecondaryContainerFinalColor)
                    } else {
                        androidx.compose.ui.graphics.Color(Color.WHITE)
                    }
                ),
                blendMode = BlendMode.SrcOver
            )

            if (isShareVisible) {
                ShareModal(isVisible = true, onDismiss = { isShareVisible = false }, shareViewModel)
            }
        }
    }

    @Composable
    fun ShareModal(isVisible: Boolean, onDismiss: () -> Unit, shareViewModel: ShareViewModel) {
        if (isVisible) {
            ModalScaffold(
                isModalOpen = isVisible,
                onDismissRequest = onDismiss,
                modalContent = {
                    ShareScreen(it, shareViewModel)
                }
            ) {}
        }
    }

    @Composable
    private fun rememberPlaybackPosition(player: Player): State<Long> {
        val positionState = remember { mutableStateOf(0L) }
        LaunchedEffect(player) {
            while (true) {
                if (player.isPlaying) positionState.value = player.currentPosition
                kotlinx.coroutines.delay(32)
            }
        }
        return positionState
    }

    /** --------------------- 背景处理 --------------------- **/

    private fun updateBg() {
        val imageUrl = play.mediaMetadata.artworkUri ?: return
        val hash = play.currentMediaItem?.songHash
        val fileUrl = SmartImageCache.getCachedUri(imageUrl.toString(), hash)
        Glide.with(this)
            .asBitmap()
            .load(fileUrl)
            .into(object : CustomTarget<Bitmap>() {
                val blurTimes = 4
                val blurRadius = 25f

                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    bitmap = resource
                    if (colorbg) {
                        addColorScheme(resource)
                    } else {
                        setBlurredBackground(resource, blurRadius, blurTimes)
                    }
                }

                override fun onLoadCleared(placeholder: android.graphics.drawable.Drawable?) {}
            })
    }

    private fun setBlurredBackground(resource: Bitmap, radius: Float, times: Int) {
        lifecycleScope.launch(Dispatchers.Default) {
            val blurred = blurMultipleTimes(requireContext(), resource, radius, times)
            withContext(Dispatchers.Main) {
                _binding?.backgroundImage?.setImageBitmap(blurred)
                _binding?.backgroundImage?.setColorFilter(
                    "#66000000".toColorInt(),
                    PorterDuff.Mode.DARKEN
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    _binding?.backgroundImage?.setRenderEffect(
                        RenderEffect.createBlurEffect(25f, 25f, Shader.TileMode.CLAMP)
                    )
                }
            }
        }
    }

    private fun blurBitmapLegacy(context: Context, bitmap: Bitmap, radius: Float): Bitmap {
        val input = bitmap.scale(bitmap.width / 2, bitmap.height / 2, false)
        val output = Bitmap.createBitmap(input)
        val rs = android.renderscript.RenderScript.create(context)
        val inputAlloc = android.renderscript.Allocation.createFromBitmap(rs, input)
        val outputAlloc = android.renderscript.Allocation.createFromBitmap(rs, output)
        val blur = android.renderscript.ScriptIntrinsicBlur.create(rs, android.renderscript.Element.U8_4(rs))
        blur.setRadius(radius.coerceIn(0f, 25f))
        blur.setInput(inputAlloc)
        blur.forEach(outputAlloc)
        outputAlloc.copyTo(output)
        rs.destroy()
        return output
    }

    private fun blurMultipleTimes(context: Context, bitmap: Bitmap, radius: Float, times: Int): Bitmap {
        var input = bitmap
        repeat(times) { input = blurBitmapLegacy(context, input, radius) }
        return input
    }

    /** --------------------- 动态色彩（保留原逻辑） --------------------- **/

    private fun addColorScheme(bitmap: Bitmap) {
        currentJob?.cancel()
        currentJob = CoroutineScope(Dispatchers.Default).launch {
            val targetWidth = 16
            val targetHeight = 16
            val scaledBitmap = bitmap.scale(targetWidth, targetHeight, false)

            val options = com.google.android.material.color.DynamicColorsOptions.Builder()
                .setContentBasedSource(scaledBitmap)
                .build()

            wrappedContext = com.google.android.material.color.DynamicColors.wrapContextIfAvailable(context, options)
            applyColorScheme()
        }
    }

    private suspend fun applyColorScheme() {
        val ctx = wrappedContext ?: context
        val colorSurface = com.google.android.material.color.MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorSurface, -1)
        val colorSecondaryContainer = com.google.android.material.color.MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorSecondaryContainer, -1)
        val colorOnSecondaryContainer = com.google.android.material.color.MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOnSecondaryContainer, -1)
        val backgroundProcessedColor = colorSurface // 简化处理

        val surfaceTransition = ValueAnimator.ofArgb(fullPlayerFinalColor, backgroundProcessedColor)
        val secondaryContainerTransition = ValueAnimator.ofArgb(colorSecondaryContainerFinalColor, colorSecondaryContainer)
        val onSecondaryContainerTransition = ValueAnimator.ofArgb(colorOnSecondaryContainerFinalColor, colorOnSecondaryContainer)

        surfaceTransition.addUpdateListener { anim ->
            MediaViewModelObject.surfaceTransition.intValue = anim.animatedValue as Int
            if (colorbg) _binding?.backgroundImage?.setBackgroundColor(anim.animatedValue as Int)
        }
        surfaceTransition.duration = 400

        withContext(Dispatchers.Main) {
            surfaceTransition.start()
            secondaryContainerTransition.start()
            onSecondaryContainerTransition.start()
        }

        colorSecondaryContainerFinalColor = colorSecondaryContainer
        colorOnSecondaryContainerFinalColor = colorOnSecondaryContainer
        MediaViewModelObject.colorOnSecondaryContainerFinalColor.intValue = colorOnSecondaryContainer
        MediaViewModelObject.colorSecondaryContainerFinalColor.intValue = colorSecondaryContainer
        currentJob = null
    }
}

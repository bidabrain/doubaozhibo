package com.doubao.live

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import coil.load
import com.doubao.live.databinding.ActivityPlayerBinding
import com.google.android.material.chip.Chip
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PLAY_ID = "playId"
        private const val MAX_RETRY = 5
    }

    private lateinit var b: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private var playId = ""
    private var fullscreen = false
    private var retry = 0
    private var job: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(b.root)

        playId = intent.getStringExtra(EXTRA_PLAY_ID).orEmpty()
        b.playerView.keepScreenOn = true
        b.playerView.setFullscreenButtonClickListener {
            android.util.Log.d("DoubaoPlayer", "fullscreen click: $it")
            setFullscreen(it)
        }
        // 直播不需要上一个/下一个/快进快退
        b.playerView.setShowPreviousButton(false)
        b.playerView.setShowNextButton(false)
        b.playerView.setShowRewindButton(false)
        b.playerView.setShowFastForwardButton(false)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (fullscreen) setFullscreen(false) else finish()
            }
        })

        initPlayer()
        loadDetail()
        startStream()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_PLAY_ID)?.let { switchLine(it) }
    }

    // ---------------- 播放器 ----------------

    private fun initPlayer() {
        val dataSource = OkHttpDataSource.Factory(Api.http).setUserAgent(Api.USER_AGENT)
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(dataSource))
            .build().also { p ->
                p.playWhenReady = true
                p.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY) {
                            retry = 0
                            hideMessage()
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                            p.seekToDefaultPosition()
                            p.prepare()
                            return
                        }
                        if (retry < MAX_RETRY) {
                            retry++
                            showMessage("播放中断，正在重连（$retry/$MAX_RETRY）…")
                            // 重新拉取地址：直播流带 auth_key，可能已过期
                            startStream(delayMs = 2000)
                        } else {
                            showMessage("播放失败：${error.errorCodeName}\n可尝试切换其他信号")
                        }
                    }
                })
            }
        b.playerView.player = player
    }

    /** 调用 /play 接口提取真实流地址并播放 */
    private fun startStream(delayMs: Long = 0) {
        job?.cancel()
        val id = playId
        job = lifecycleScope.launch {
            if (delayMs > 0) delay(delayMs)
            if (retry == 0) showMessage("正在获取直播流…")
            try {
                val info = Api.play(id)
                if (!info.playable || info.url.isEmpty()) {
                    player?.stop()
                    showMessage(info.message.ifEmpty { "该信号暂未开播" })
                    return@launch
                }
                b.streamUrl.text = "流地址（${info.kind}）：\n${info.url}"
                val builder = MediaItem.Builder().setUri(Uri.parse(info.url))
                when {
                    info.kind.equals("hls", true) || info.url.contains(".m3u8") ->
                        builder.setMimeType(MimeTypes.APPLICATION_M3U8)
                    info.kind.equals("dash", true) || info.url.contains(".mpd") ->
                        builder.setMimeType(MimeTypes.APPLICATION_MPD)
                    // flv / mp4 等交给 ExoPlayer 自动识别容器
                }
                player?.run {
                    setMediaItem(builder.build())
                    prepare()
                    play()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                showMessage("获取直播流失败：${e.message}")
            }
        }
    }

    private fun switchLine(newId: String) {
        if (newId == playId) return
        playId = newId
        retry = 0
        player?.stop()
        startStream()
    }

    // ---------------- 比赛信息 & 线路 ----------------

    private fun loadDetail() {
        lifecycleScope.launch {
            try {
                val d = Api.signal(playId)
                val s = d.schedule
                if (s != null) {
                    b.league.text = listOf(s.league, localTime(s.matchTime)).filter { it.isNotBlank() }.joinToString("  ")
                    b.home.text = s.home
                    b.away.text = s.away
                    b.score.text = "${s.homeScore} : ${s.awayScore}"
                    b.homeLogo.load(s.homeLogo)
                    b.awayLogo.load(s.awayLogo)
                } else {
                    b.scoreRow.visibility = View.GONE
                    b.league.text = d.title
                }
                b.state.text = d.state
                b.lines.removeAllViews()
                val lines = d.lines.ifEmpty { listOf(SignalLine(0, d.name, d.playId)) }
                lines.forEach { line ->
                    val chip = Chip(this@PlayerActivity).apply {
                        text = line.name
                        isCheckable = true
                        isCheckedIconVisible = false
                        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
                        chipBackgroundColor = ColorStateList(states, intArrayOf(0xFFDFC79C.toInt(), 0xFF2A2D36.toInt()))
                        setTextColor(ColorStateList(states, intArrayOf(0xFF1C1E25.toInt(), 0xFFDDDDDD.toInt())))
                        chipStrokeWidth = 0f
                        isChecked = line.playId == playId
                        setOnClickListener { switchLine(line.playId); isChecked = true }
                    }
                    b.lines.addView(chip)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(this@PlayerActivity, "比赛信息加载失败：${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---------------- 全屏 / 画中画 ----------------

    private fun setFullscreen(on: Boolean) {
        fullscreen = on
        b.playerView.setFullscreenButtonState(on)
        val ctl = WindowCompat.getInsetsController(window, window.decorView)
        val lp = b.playerBox.layoutParams as LinearLayout.LayoutParams
        if (on) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            b.info.visibility = View.GONE
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT
            ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctl.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            b.info.visibility = View.VISIBLE
            lp.height = resources.displayMetrics.widthPixels.coerceAtMost(resources.displayMetrics.heightPixels) * 9 / 16
            ctl.show(WindowInsetsCompat.Type.systemBars())
        }
        b.playerBox.layoutParams = lp
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!fullscreen) setFullscreen(false) // 按 16:9 设置竖屏下的播放器高度
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && player?.isPlaying == true) {
            enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
        }
    }

    override fun onPictureInPictureModeChanged(isInPip: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPip, newConfig)
        b.playerView.useController = !isInPip
        b.info.visibility = if (isInPip || fullscreen) View.GONE else View.VISIBLE
    }

    // ---------------- 生命周期 ----------------

    override fun onStart() {
        super.onStart()
        player?.play()
    }

    override fun onStop() {
        super.onStop()
        // 画中画关闭时也会走到 onStop，此时暂停
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        job?.cancel()
        player?.release()
        player = null
    }

    /** 接口返回 UTC 时间（如 2026-11-08T16:30:00Z），转为本地时间显示 */
    private fun localTime(iso: String): String = try {
        val utc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(utc.parse(iso.take(19))!!)
    } catch (e: Exception) {
        iso.replace('T', ' ').take(16)
    }

    private fun showMessage(msg: String) {
        b.message.text = msg
        b.message.visibility = View.VISIBLE
    }

    private fun hideMessage() {
        b.message.visibility = View.GONE
    }
}

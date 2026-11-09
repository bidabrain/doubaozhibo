package com.doubao.live

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.KeyEvent
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PLAY_ID = "playId"
        private const val MAX_RETRY = 5

        fun start(ctx: Context, playId: String) {
            ctx.startActivity(Intent(ctx, PlayerActivity::class.java).putExtra(EXTRA_PLAY_ID, playId))
        }
    }

    private lateinit var b: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private var playId = ""
    private var lines: List<SignalLine> = emptyList()
    private var fullscreen = false
    private var tv = false
    private var retry = 0
    private var job: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(b.root)

        tv = isTv()
        playId = intent.getStringExtra(EXTRA_PLAY_ID).orEmpty()
        b.playerView.keepScreenOn = true
        b.playerView.setFullscreenButtonClickListener { setFullscreen(it) }
        // 直播不需要上一个/下一个/快进快退
        b.playerView.setShowPreviousButton(false)
        b.playerView.setShowNextButton(false)
        b.playerView.setShowRewindButton(false)
        b.playerView.setShowFastForwardButton(false)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    // 遥控器：控制栏显示时先收起
                    tv && b.playerView.isControllerFullyVisible -> b.playerView.hideController()
                    fullscreen && phoneForcedLandscape -> setFullscreen(false)
                    else -> finish()
                }
            }
        })

        initPlayer()
        loadDetail()
        startStream()

        // 电视 / 横屏设备直接全屏
        setFullscreen(tv || isLandscape())
        if (tv) {
            b.playerView.requestFocus()
            Toast.makeText(this, "遥控器 ↑↓ 切换信号，菜单键选择线路", Toast.LENGTH_LONG).show()
        }
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
            } catch (e: CancellationException) {
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
        syncChips()
        startStream()
    }

    /** 遥控器上下键：切换到相邻线路 */
    private fun stepLine(delta: Int) {
        if (lines.size < 2) {
            Toast.makeText(this, "只有一个直播信号", Toast.LENGTH_SHORT).show()
            return
        }
        val i = lines.indexOfFirst { it.playId == playId }.coerceAtLeast(0)
        val next = lines[(i + delta + lines.size) % lines.size]
        Toast.makeText(this, "切换到：${next.name}", Toast.LENGTH_SHORT).show()
        switchLine(next.playId)
    }

    private fun showLinePicker() {
        if (lines.isEmpty()) return
        val names = lines.map { it.name }.toTypedArray()
        val current = lines.indexOfFirst { it.playId == playId }
        MaterialAlertDialogBuilder(this)
            .setTitle("选择直播信号")
            .setSingleChoiceItems(names, current) { d, which ->
                d.dismiss()
                switchLine(lines[which].playId)
            }
            .show()
    }

    // ---------------- 遥控器按键 ----------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val controllerHidden = !b.playerView.isControllerFullyVisible
            when (event.keyCode) {
                KeyEvent.KEYCODE_CHANNEL_UP -> { stepLine(-1); return true }
                KeyEvent.KEYCODE_CHANNEL_DOWN -> { stepLine(1); return true }
                KeyEvent.KEYCODE_MENU -> { showLinePicker(); return true }
                KeyEvent.KEYCODE_DPAD_UP -> if (fullscreen && controllerHidden) { stepLine(-1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> if (fullscreen && controllerHidden) { stepLine(1); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                    if (fullscreen && controllerHidden) {
                        b.playerView.showController()
                        return true
                    }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    player?.let { if (it.isPlaying) it.pause() else it.play() }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ---------------- 比赛信息 & 线路 ----------------

    private fun loadDetail() {
        lifecycleScope.launch {
            try {
                val d = Api.signal(playId)
                val s = d.schedule
                if (s != null) {
                    b.league.text = listOf(s.league, Times.format(s.matchTime, "MM-dd HH:mm"))
                        .filter { it.isNotBlank() }.joinToString("  ")
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
                lines = d.lines.ifEmpty { listOf(SignalLine(0, d.name, d.playId)) }
                b.lines.removeAllViews()
                val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
                lines.forEach { line ->
                    val chip = Chip(this@PlayerActivity).apply {
                        text = line.name
                        tag = line.playId
                        isCheckable = true
                        isCheckedIconVisible = false
                        chipBackgroundColor = ColorStateList(states, intArrayOf(0xFFDFC79C.toInt(), 0xFF2A2D36.toInt()))
                        setTextColor(ColorStateList(states, intArrayOf(0xFF1C1E25.toInt(), 0xFFDDDDDD.toInt())))
                        chipStrokeWidth = 0f
                        setOnClickListener { switchLine(line.playId) }
                    }
                    b.lines.addView(chip)
                }
                syncChips()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(this@PlayerActivity, "比赛信息加载失败：${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun syncChips() {
        for (i in 0 until b.lines.childCount) {
            val chip = b.lines.getChildAt(i) as Chip
            chip.isChecked = chip.tag == playId
        }
    }

    // ---------------- 全屏 / 画中画 ----------------

    /** 是否是手机竖屏时点全屏按钮强制转成的横屏（区别于平板/盒子本身就是横屏） */
    private var phoneForcedLandscape = false

    private fun isLandscape() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun setFullscreen(on: Boolean) {
        fullscreen = on
        b.playerView.setFullscreenButtonState(on)
        val ctl = WindowCompat.getInsetsController(window, window.decorView)
        val lp = b.playerBox.layoutParams as LinearLayout.LayoutParams
        if (on) {
            if (!tv && !isLandscape()) {
                phoneForcedLandscape = true
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
            b.info.visibility = View.GONE
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT
            ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctl.hide(WindowInsetsCompat.Type.systemBars())
            b.root.fitsSystemWindows = false
        } else {
            if (phoneForcedLandscape) {
                phoneForcedLandscape = false
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            b.info.visibility = View.VISIBLE
            val dm = resources.displayMetrics
            lp.height = minOf(dm.widthPixels, dm.heightPixels) * 9 / 16
            ctl.show(WindowInsetsCompat.Type.systemBars())
            b.root.fitsSystemWindows = true
        }
        b.playerBox.layoutParams = lp
        b.root.requestApplyInsets()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (tv || phoneForcedLandscape) return
        // 平板等可自由旋转的设备：横屏自动全屏，转回竖屏退出全屏
        val landscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (landscape != fullscreen) setFullscreen(landscape)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!tv && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && player?.isPlaying == true) {
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

    private fun showMessage(msg: String) {
        b.message.text = msg
        b.message.visibility = View.VISIBLE
    }

    private fun hideMessage() {
        b.message.visibility = View.GONE
    }
}

package com.doubao.live

import android.app.Application
import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.View
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.AppCompatImageView
import com.google.android.material.R as MR
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.applyTheme(this)
    }
}

object Prefs {
    private const val FILE = "settings"
    private const val KEY_THEME = "theme"

    /** 0 跟随系统 / 1 浅色 / 2 深色 */
    val themeLabels = arrayOf("跟随系统", "浅色", "深色")

    fun theme(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt(KEY_THEME, 0)

    fun setTheme(ctx: Context, mode: Int) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putInt(KEY_THEME, mode).apply()
        applyTheme(ctx)
    }

    fun applyTheme(ctx: Context) {
        AppCompatDelegate.setDefaultNightMode(
            when (theme(ctx)) {
                1 -> AppCompatDelegate.MODE_NIGHT_NO
                2 -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }
}

/** 直播 / 赛程共用的分类 */
val CATEGORIES = listOf(
    "all" to "全部",
    "important" to "热门",
    "football" to "足球",
    "basketball" to "篮球",
    "other" to "其他",
)

fun ChipGroup.setupCategories(selected: String, onSelect: (String) -> Unit) {
    removeAllViews()
    val primary = MaterialColors.getColor(this, MR.attr.colorPrimary)
    val onPrimary = MaterialColors.getColor(this, MR.attr.colorOnPrimary)
    val surfaceVariant = MaterialColors.getColor(this, MR.attr.colorSurfaceVariant)
    val onSurfaceVariant = MaterialColors.getColor(this, MR.attr.colorOnSurfaceVariant)
    val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
    CATEGORIES.forEach { (key, label) ->
        val chip = Chip(context).apply {
            id = View.generateViewId()
            text = label
            tag = key
            isCheckable = true
            isCheckedIconVisible = false
            chipStrokeWidth = 0f
            chipBackgroundColor = ColorStateList(states, intArrayOf(primary, surfaceVariant))
            setTextColor(ColorStateList(states, intArrayOf(onPrimary, onSurfaceVariant)))
            isChecked = key == selected
        }
        addView(chip)
    }
    setOnCheckedStateChangeListener { group, ids ->
        val key = ids.firstOrNull()?.let { group.findViewById<Chip>(it)?.tag as? String } ?: return@setOnCheckedStateChangeListener
        onSelect(key)
    }
}

/** 遥控器焦点：获得焦点时轻微放大（电视上更容易看清当前选中项） */
fun android.view.View.focusZoom(scale: Float = 1.04f) {
    setOnFocusChangeListener { v, hasFocus ->
        v.animate().scaleX(if (hasFocus) scale else 1f).scaleY(if (hasFocus) scale else 1f)
            .setDuration(150).start()
        v.translationZ = if (hasFocus) 8f else 0f
    }
}

/** 是否运行在电视 / 盒子上 */
fun Context.isTv(): Boolean {
    val ui = getSystemService(Context.UI_MODE_SERVICE) as android.app.UiModeManager
    return ui.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION ||
        !packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN)
}

/** 按 16:9 自动计算高度的封面图 */
class AspectImageView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null,
) : AppCompatImageView(ctx, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, w * 9 / 16)
    }
}

object Times {
    private fun utcParser() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /** 接口时间均为 UTC（如 2026-11-08T16:30:00Z），解析为本地 Date */
    fun parse(iso: String): Date? = try {
        utcParser().parse(iso.take(19))
    } catch (e: Exception) {
        null
    }

    fun format(iso: String, pattern: String): String =
        parse(iso)?.let { SimpleDateFormat(pattern, Locale.getDefault()).format(it) } ?: iso.replace('T', ' ').take(16)

    fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
}

package com.doubao.live

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import com.doubao.live.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding

    /** 启动时静默检查到的新版本，供“我的”页面直接使用 */
    var pendingRelease: Updater.Release? = null
        private set

    private val tabs = mapOf(
        R.id.nav_live to "live",
        R.id.nav_schedule to "schedule",
        R.id.nav_me to "me",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.bottomNav.setOnItemSelectedListener { show(it.itemId); true }
        b.bottomNav.setOnItemReselectedListener { item ->
            (supportFragmentManager.findFragmentByTag(tabs[item.itemId]) as? Reselectable)?.onReselect()
        }
        if (savedInstanceState == null) show(R.id.nav_live)

        // 静默检查更新，有新版时在“我的”上显示红点
        lifecycleScope.launch {
            runCatching { Updater.latest() }.getOrNull()?.takeIf { it.isNewer() }?.let {
                pendingRelease = it
                b.bottomNav.getOrCreateBadge(R.id.nav_me).isVisible = true
                (supportFragmentManager.findFragmentByTag("me") as? MeFragment)?.onReleaseFound(it)
            }
        }
    }

    private fun show(itemId: Int) {
        val tag = tabs.getValue(itemId)
        supportFragmentManager.commit {
            setReorderingAllowed(true)
            supportFragmentManager.fragments.forEach { if (it.tag != tag) hide(it) }
            val existing = supportFragmentManager.findFragmentByTag(tag)
            if (existing != null) show(existing) else add(R.id.container, create(tag), tag)
        }
    }

    private fun create(tag: String): Fragment = when (tag) {
        "live" -> LiveFragment()
        "schedule" -> ScheduleFragment()
        else -> MeFragment()
    }

    fun clearUpdateBadge() {
        b.bottomNav.removeBadge(R.id.nav_me)
    }
}

/** 再次点击底部 tab 时回到顶部并刷新 */
interface Reselectable {
    fun onReselect()
}

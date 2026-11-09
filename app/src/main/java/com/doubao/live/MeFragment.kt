package com.doubao.live

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.doubao.live.databinding.FragmentMeBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

class MeFragment : Fragment() {

    private var _b: FragmentMeBinding? = null
    private val b get() = _b!!

    private var checking: Job? = null
    private var downloading: Job? = null
    private var pendingApk: File? = null

    /** 从“允许安装未知应用”设置页返回后继续安装 */
    private val unknownSources = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val apk = pendingApk ?: return@registerForActivityResult
        if (Updater.canInstall(requireContext())) Updater.install(requireContext(), apk)
        else toast("未授予安装权限，无法自动安装")
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentMeBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        b.version.text = "v${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）"
        b.updateStatus.text = "当前 v${BuildConfig.VERSION_NAME}"
        (activity as? MainActivity)?.pendingRelease?.let { onReleaseFound(it) }

        b.rowUpdate.setOnClickListener { checkUpdate() }
        b.themeValue.text = Prefs.themeLabels[Prefs.theme(requireContext())]
        b.rowTheme.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("深色模式")
                .setSingleChoiceItems(Prefs.themeLabels, Prefs.theme(requireContext())) { d, which ->
                    d.dismiss()
                    Prefs.setTheme(requireContext(), which)
                }
                .show()
        }
        b.rowProject.setOnClickListener { openUrl(Updater.PROJECT_URL) }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }

    /** MainActivity 启动时静默检查到新版本 */
    fun onReleaseFound(rel: Updater.Release) {
        val bb = _b ?: return
        bb.updateDot.visibility = View.VISIBLE
        bb.updateStatus.text = "发现新版本 ${rel.tag}"
    }

    private fun checkUpdate() {
        if (checking?.isActive == true || downloading?.isActive == true) return
        b.updateStatus.text = "正在检查…"
        checking = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val rel = Updater.latest()
                if (rel.isNewer()) {
                    onReleaseFound(rel)
                    askDownload(rel)
                } else {
                    b.updateDot.visibility = View.GONE
                    b.updateStatus.text = "已是最新版本"
                    (activity as? MainActivity)?.clearUpdateBadge()
                    toast("已是最新版本 v${BuildConfig.VERSION_NAME}")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                b.updateStatus.text = "检查失败"
                toast(e.message ?: "检查更新失败")
            }
        }
    }

    private fun askDownload(rel: Updater.Release) {
        val sizeMb = if (rel.size > 0) String.format("%.1f MB", rel.size / 1048576f) else ""
        val msg = buildString {
            append("当前版本：v${BuildConfig.VERSION_NAME}\n最新版本：${rel.tag}")
            if (sizeMb.isNotEmpty()) append("（$sizeMb）")
            if (rel.notes.isNotEmpty()) append("\n\n").append(rel.notes.take(600))
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("发现新版本")
            .setMessage(msg)
            .setPositiveButton("立即更新") { _, _ -> startDownload(rel) }
            .setNegativeButton("稍后", null)
            .show()
    }

    private fun startDownload(rel: Updater.Release) {
        val ctx = requireContext()
        val bar = LinearProgressIndicator(ctx).apply {
            isIndeterminate = true
            max = 100
        }
        val box = android.widget.FrameLayout(ctx).apply {
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(bar)
        }
        val dialog: AlertDialog = MaterialAlertDialogBuilder(ctx)
            .setTitle("正在下载 ${rel.tag}")
            .setView(box)
            .setCancelable(false)
            .setNegativeButton("取消") { _, _ -> downloading?.cancel() }
            .show()

        downloading = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val apk = Updater.download(ctx, rel) { pct ->
                    if (pct >= 0) {
                        bar.isIndeterminate = false
                        bar.setProgressCompat(pct, true)
                        dialog.setTitle("正在下载 ${rel.tag}  $pct%")
                    }
                }
                dialog.dismiss()
                b.updateStatus.text = "下载完成，等待安装"
                install(apk)
            } catch (e: CancellationException) {
                dialog.dismiss()
                b.updateStatus.text = "已取消下载"
                throw e
            } catch (e: Exception) {
                dialog.dismiss()
                b.updateStatus.text = "下载失败"
                MaterialAlertDialogBuilder(ctx)
                    .setTitle("下载失败")
                    .setMessage("${e.message}\n\n可以在浏览器中打开发布页手动下载。")
                    .setPositiveButton("打开发布页") { _, _ -> openUrl(rel.pageUrl) }
                    .setNegativeButton("关闭", null)
                    .show()
            }
        }
    }

    private fun install(apk: File) {
        val ctx = requireContext()
        if (Updater.canInstall(ctx)) {
            Updater.install(ctx, apk)
            return
        }
        pendingApk = apk
        MaterialAlertDialogBuilder(ctx)
            .setTitle("需要安装权限")
            .setMessage("请在接下来的页面中允许「${getString(R.string.app_name)}」安装未知应用，返回后会自动开始安装。")
            .setPositiveButton("去设置") { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try {
                        unknownSources.launch(
                            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                        )
                    } catch (e: ActivityNotFoundException) {
                        // 部分电视盒子没有这个设置页，直接尝试安装
                        Updater.install(ctx, apk)
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            toast("没有可用的浏览器")
        }
    }

    private fun toast(msg: String) = Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
}

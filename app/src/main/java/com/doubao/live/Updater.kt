package com.doubao.live

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 应用内更新：读取 GitHub 最新 Release，比较版本号，下载 APK 并调起系统安装。
 *
 * CI 发布的 tag 形如 v1.0.<run_number>，APK 的 versionCode 就是 <run_number>，
 * 所以取 tag 最后一段数字和 BuildConfig.VERSION_CODE 比较即可。
 */
object Updater {
    const val REPO = "bidabrain/doubaozhibo"
    const val PROJECT_URL = "https://github.com/$REPO"
    private const val LATEST_API = "https://api.github.com/repos/$REPO/releases/latest"

    data class Release(
        val tag: String,
        val versionCode: Int,
        val notes: String,
        val apkUrl: String,
        val apkName: String,
        val size: Long,
        val pageUrl: String,
    ) {
        fun isNewer() = versionCode > BuildConfig.VERSION_CODE
    }

    /** GitHub 用独立客户端，不带直播站的 Referer */
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun latest(): Release = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(LATEST_API)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "DoubaoLive/${BuildConfig.VERSION_NAME}")
            .build()
        http.newCall(req).execute().use { resp ->
            when (resp.code) {
                200 -> Unit
                404 -> throw IOException("找不到发布版本（仓库可能是私有的，或还没有 Release）")
                403 -> throw IOException("GitHub 接口访问过于频繁，请稍后再试")
                else -> throw IOException("检查更新失败（HTTP ${resp.code}）")
            }
            val json = JSONObject(resp.body?.string().orEmpty())
            val tag = json.optString("tag_name")
            val assets = json.optJSONArray("assets")
            var apk: JSONObject? = null
            if (assets != null) for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk", ignoreCase = true)) { apk = a; break }
            }
            apk ?: throw IOException("最新版本 $tag 中没有 APK 文件")
            Release(
                tag = tag,
                versionCode = Regex("(\\d+)\\D*$").find(tag)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
                notes = json.optString("body").trim(),
                apkUrl = apk.optString("browser_download_url"),
                apkName = apk.optString("name"),
                size = apk.optLong("size"),
                pageUrl = json.optString("html_url"),
            )
        }
    }

    /** 下载 APK 到 cache/updates/，onProgress 回调 0..100（未知大小时为 -1） */
    suspend fun download(ctx: Context, rel: Release, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // 清理旧安装包
        val target = File(dir, rel.apkName.ifEmpty { "update.apk" })
        val tmp = File(dir, target.name + ".part")

        val req = Request.Builder().url(rel.apkUrl)
            .header("User-Agent", "DoubaoLive/${BuildConfig.VERSION_NAME}")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("下载失败（HTTP ${resp.code}）")
            val body = resp.body ?: throw IOException("下载失败：空响应")
            val total = body.contentLength().takeIf { it > 0 } ?: rel.size
            body.byteStream().use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastPct = -2
                    while (true) {
                        ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val pct = if (total > 0) (done * 100 / total).toInt() else -1
                        if (pct != lastPct) {
                            lastPct = pct
                            withContext(Dispatchers.Main) { onProgress(pct) }
                        }
                    }
                }
            }
            if (rel.size > 0 && tmp.length() != rel.size) {
                tmp.delete()
                throw IOException("下载不完整，请重试")
            }
        }
        tmp.renameTo(target)
        target
    }

    /** Android 8+ 需要用户允许“安装未知应用” */
    fun canInstall(ctx: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ctx.packageManager.canRequestPackageInstalls()

    fun install(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
    }
}

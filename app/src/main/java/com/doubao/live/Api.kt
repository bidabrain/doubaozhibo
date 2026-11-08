package com.doubao.live

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** 直播间列表项 —— 对应 /api/v1/home/rooms 的 items */
data class Room(
    val id: Int,
    val title: String,
    val anchor: String,
    val league: String,
    val cover: String,
    val popularity: Int,
    val status: Int,
    val playId: String,
)

data class RoomPage(val items: List<Room>, val total: Int, val page: Int, val pageSize: Int)

data class SignalLine(val id: Int, val name: String, val playId: String)

data class Schedule(
    val league: String,
    val home: String,
    val away: String,
    val homeLogo: String,
    val awayLogo: String,
    val homeScore: String,
    val awayScore: String,
    val matchTime: String,
)

/** 信号详情 —— 对应 /api/v1/public/signals/{playId} */
data class SignalDetail(
    val playId: String,
    val title: String,
    val name: String,
    val live: Boolean,
    val playable: Boolean,
    val state: String,
    val schedule: Schedule?,
    val lines: List<SignalLine>,
)

/** 真实直播流 —— 对应 /api/v1/public/signals/{playId}/play */
data class PlayInfo(val playable: Boolean, val url: String, val kind: String, val message: String)

class ApiException(msg: String) : IOException(msg)

object Api {
    const val BASE = "https://www.doubaozhibo.com"
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", "$BASE/")
                    .build()
            )
        }
        .build()

    fun abs(path: String): String = when {
        path.isEmpty() -> ""
        path.startsWith("http") -> path
        path.startsWith("//") -> "https:$path"
        else -> BASE + (if (path.startsWith("/")) path else "/$path")
    }

    private suspend fun getData(path: String): Any? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(BASE + path).header("Accept", "application/json").build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            val json = try {
                JSONObject(body)
            } catch (e: Exception) {
                throw ApiException("服务器返回异常 (HTTP ${resp.code})")
            }
            if (!json.optBoolean("success")) {
                throw ApiException(json.optString("message").ifEmpty { "请求失败 (${json.optInt("code")})" })
            }
            json.opt("data")
        }
    }

    /** category: all / important / football / basketball / other */
    suspend fun rooms(category: String, page: Int, query: String = "", pageSize: Int = 30): RoomPage {
        val q = URLEncoder.encode(query, "UTF-8")
        val data = getData("/api/v1/home/rooms?category=$category&pageSize=$pageSize&page=$page&q=$q") as? JSONObject
            ?: return RoomPage(emptyList(), 0, page, pageSize)
        val arr = data.optJSONArray("items")
        val list = buildList {
            if (arr != null) for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val url = o.optString("url")
                val playId = url.substringAfter("/play/", "").ifEmpty { continue }
                add(
                    Room(
                        id = o.optInt("id"),
                        title = o.optString("title"),
                        anchor = o.optString("anchor"),
                        league = o.optString("league"),
                        cover = abs(o.optString("cover")),
                        popularity = o.optInt("popularity"),
                        status = o.optInt("status"),
                        playId = playId,
                    )
                )
            }
        }
        return RoomPage(list, data.optInt("total"), data.optInt("page", page), data.optInt("pageSize", pageSize))
    }

    suspend fun signal(playId: String): SignalDetail {
        val d = getData("/api/v1/public/signals/$playId") as? JSONObject ?: throw ApiException("信号不存在")
        val s = d.optJSONObject("schedule")
        val schedule = s?.let {
            Schedule(
                league = it.optString("league"),
                home = it.optString("home"),
                away = it.optString("away"),
                homeLogo = abs(it.optString("homeLogo")),
                awayLogo = abs(it.optString("awayLogo")),
                homeScore = it.opt("homeScore")?.takeIf { v -> v != JSONObject.NULL }?.toString() ?: "-",
                awayScore = it.opt("awayScore")?.takeIf { v -> v != JSONObject.NULL }?.toString() ?: "-",
                matchTime = it.optString("matchTime"),
            )
        }
        val arr = d.optJSONArray("signals")
        val lines = buildList {
            if (arr != null) for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(SignalLine(o.optInt("id"), o.optString("name"), o.optString("playId")))
            }
        }
        return SignalDetail(
            playId = d.optString("playId", playId),
            title = d.optString("title"),
            name = d.optString("name"),
            live = d.optBoolean("live"),
            playable = d.optBoolean("playable"),
            state = d.optString("state"),
            schedule = schedule,
            lines = lines,
        )
    }

    /** 提取真实直播流地址 */
    suspend fun play(playId: String): PlayInfo {
        val d = getData("/api/v1/public/signals/$playId/play") as? JSONObject ?: throw ApiException("暂无直播流")
        return PlayInfo(
            playable = d.optBoolean("playable"),
            url = d.optString("url"),
            kind = d.optString("kind"),
            message = d.optString("message"),
        )
    }
}

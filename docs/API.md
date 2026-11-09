# 豆包直播 App · 接口逻辑说明

本文记录 App 使用的所有网络接口、调用顺序和数据处理方式。接口均来自网站 `https://www.doubaozhibo.com` 的前端（Nuxt）所调用的公开 JSON 接口，**无需登录**。对应代码见 [`Api.kt`](../app/src/main/java/com/doubao/live/Api.kt) 和 [`Updater.kt`](../app/src/main/java/com/doubao/live/Updater.kt)。

## 总体流程

```
直播页  ── GET /api/v1/home/rooms ───────────────┐
赛程页  ── GET /api/v1/home/schedules ───────────┤  得到 playId（如 1249-9-2）
                                                  ▼
播放页  ── GET /api/v1/public/signals/{playId}        比赛信息 + 同场所有线路
        ── GET /api/v1/public/signals/{playId}/play   真实直播流地址（m3u8）
                                                  ▼
        ExoPlayer 播放 m3u8（302 跳转到 CDN，自动跟随）

我的页  ── GET api.github.com/repos/bidabrain/doubaozhibo/releases/latest
        ── 下载 Release 中的 APK → 调起系统安装器
```

## 通用约定

| 项目 | 说明 |
| --- | --- |
| 基础地址 | `https://www.doubaozhibo.com` |
| 请求头 | `User-Agent` 使用手机 Chrome UA；`Referer: https://www.doubaozhibo.com/`（目前接口不校验，加上更稳妥） |
| 响应包装 | `{"success": true, "code": 0, "data": {...}}`；失败时 `success=false`，`message` 为原因（如 `接口不存在`） |
| 时间格式 | 所有时间为 **UTC**，如 `2026-11-08T16:30:00Z`，App 内转换为手机本地时区显示（`Times.parse/format`） |
| 图片地址 | 封面等字段可能是相对路径（`/uploads/signals/3565.jpg?...`），需拼接基础地址（`Api.abs()`）；球队 logo 一般是 `https://www.rtmpcdn.com/...` 完整地址 |
| playId | 形如 `{比赛id}-{来源}-{序号}`，例如 `1249-9-2`。来自各接口里的 `url: "/play/1249-9-2"`，去掉 `/play/` 前缀即得 |

## 1. 直播间列表

```
GET /api/v1/home/rooms?category={category}&pageSize=30&page={page}&q={keyword}
```

| 参数 | 说明 |
| --- | --- |
| `category` | `all` 全部 / `important` 热门 / `football` 足球 / `basketball` 篮球 / `other` 其他 |
| `page` | 页码，从 1 开始 |
| `pageSize` | 每页数量，App 用 30 |
| `q` | 搜索关键字（比赛、球队、主播），可为空 |

响应 `data`：

```json
{
  "items": [
    {
      "id": 3565,
      "title": "英超 曼联 vs 阿斯顿维拉",
      "anchor": "咪咕(詹俊解说)",
      "sport": "football",
      "cover": "/uploads/signals/3565.jpg?g=...",
      "matchId": 1249,
      "league": "英超",
      "status": 1,
      "popularity": 6696,
      "url": "/play/1249-9-2"
    }
  ],
  "total": 21, "page": 1, "pageSize": 30, "mode": "live"
}
```

App 处理：
- 每一项是一条“直播信号”，同一场比赛可能有多条（不同解说/清晰度）。
- 分页：滚动到底部且 `已加载数 < total` 时请求 `page + 1`，按 `id` 去重后追加。
- `status = 1` 显示 LIVE 角标。

## 2. 赛程

```
GET /api/v1/home/schedules?date={yyyy-MM-dd}&days=5&category={category}&continuous=true[&cursor={nextCursor}]
```

| 参数 | 说明 |
| --- | --- |
| `date` | 起始日期，App 传手机当天日期 |
| `days` | 向后取几天，网站固定为 5 |
| `category` | 同直播列表 |
| `continuous` | 固定 `true` |
| `cursor` | 翻页游标，首次不传，之后传上一页返回的 `nextCursor` |

响应 `data`：

```json
{
  "items": [
    {
      "match": {
        "id": 1260,
        "league": "NBA",
        "matchTime": "2026-11-09T00:00:00Z",
        "sport": "basketball",
        "home": "森林狼", "away": "爵士",
        "homeLogo": "https://www.rtmpcdn.com/uploads/teamlogo/....png",
        "awayLogo": "https://www.rtmpcdn.com/uploads/teamlogo/....png",
        "homeScore": 85, "awayScore": 76,
        "status": 1,
        "playUrl": "/play/1260-9-1"
      },
      "rooms": [ { "id": 3154, "anchor": "腾讯超清3", "url": "/play/1260-9-1", ... } ]
    }
  ],
  "total": 32, "hasMore": true, "nextCursor": "eyJUaWNrcyI6..."
}
```

| `match.status` | 含义 | App 显示 |
| --- | --- | --- |
| 0 | 未开始 | `VS` / 未开始，按钮“待开播” |
| 1 | 进行中 | 实时比分 / ● 进行中，按钮“观看” |
| 2 | 已完赛 | 最终比分 / 已完赛 |

App 处理：
- `playUrl` 为 `null` 且 `rooms` 为空时表示该比赛没有直播信号（按钮显示“无信号”）。
- `playUrl` 为空时退而使用 `rooms[0].url`。
- 分页：`hasMore=true` 且有 `nextCursor` 时继续加载；按 `match.id` 去重。
- 按本地日期分组，插入“今天 · 11月9日 星期一”这样的日期标题。

## 3. 信号详情（比赛信息 + 线路列表）

```
GET /api/v1/public/signals/{playId}
```

响应 `data`（节选）：

```json
{
  "id": 3607,
  "playId": "1392-1-1",
  "title": "英足总杯 奥尔德姆 vs 谢周三",
  "name": "高清直播1",
  "playable": true,
  "live": true,
  "state": "正在直播",
  "schedule": {
    "league": "英足总杯",
    "matchTime": "2026-11-08T17:15:00Z",
    "home": "奥尔德姆", "away": "谢周三",
    "homeLogo": "...", "awayLogo": "...",
    "homeScore": 0, "awayScore": 0,
    "status": 1
  },
  "signals": [
    { "id": 3607, "name": "高清直播1", "playId": "1392-1-1", "url": "/play/1392-1-1" }
  ]
}
```

App 处理：
- `schedule` 用于播放页的比分栏；为 `null` 时只显示标题。
- `signals` 是同一场比赛的所有线路，渲染成可切换的线路按钮；电视遥控器 ↑↓ / 频道键在其中循环切换，菜单键弹出列表。

## 4. 真实直播流地址（核心）

```
GET /api/v1/public/signals/{playId}/play
```

响应 `data`：

```json
{
  "playable": true,
  "url": "https://www.rtmpcdn.com/live/7e441a81-0d60-4099-aa12-ca6c7a41f26e.m3u8",
  "kind": "hls"
}
```

播放链路：

1. `url` 返回 `302`，跳转到真实 CDN，例如
   `https://play.aarray.cn/rrty/xxx.m3u8?auth_key=...&token=...`
2. CDN 地址带 `auth_key`，**会过期**，所以 App 不缓存地址，每次播放 / 重连都重新调用本接口。
3. ExoPlayer 使用 OkHttp 数据源（自动跟随跳转），根据 `kind` 指定类型：
   - `hls` 或地址含 `.m3u8` → `APPLICATION_M3U8`
   - `dash` 或含 `.mpd` → `APPLICATION_MPD`
   - 其他（flv / mp4）交给 ExoPlayer 自动识别
4. `playable=false` 或 `url` 为空 → 显示“该信号暂未开播”。

错误与重连（`PlayerActivity`）：
- `ERROR_CODE_BEHIND_LIVE_WINDOW`（直播窗口落后）→ 跳回直播点重新 `prepare()`。
- 其他播放错误 → 2 秒后重新请求本接口拿新地址，最多重试 5 次；成功播放后计数清零。

## 5. 应用内更新（GitHub Releases）

```
GET https://api.github.com/repos/bidabrain/doubaozhibo/releases/latest
Accept: application/vnd.github+json
```

使用的字段：

| 字段 | 用途 |
| --- | --- |
| `tag_name` | 形如 `v1.0.12`，取最后一段数字作为远端 versionCode |
| `body` | 更新说明，显示在弹窗中 |
| `assets[].name / browser_download_url / size` | 找到第一个 `.apk` 文件用于下载，`size` 用于校验完整性 |
| `html_url` | 下载失败时让用户在浏览器打开 |

版本比较：CI 中 `versionCode = GitHub run_number`，tag 为 `v1.0.<run_number>`，因此 `tag 末尾数字 > BuildConfig.VERSION_CODE` 即为有新版本。

下载与安装：
1. 下载到 `cache/updates/`（先写 `.part`，校验大小后重命名），弹窗显示百分比进度，可取消。
2. 通过 `FileProvider`（authority：`com.doubao.live.files`）生成 `content://` 地址，`ACTION_VIEW` + `application/vnd.android.package-archive` 调起系统安装器。
3. Android 8+ 需“允许安装未知应用”权限：未授权时跳转系统设置页，返回后自动继续安装；部分电视盒子没有该设置页，则直接尝试安装。
4. 所有 Release 都用同一把签名（本机 `~/.android/debug.keystore`，CI 从 secret `DEBUG_KEYSTORE_BASE64` 还原），因此可以直接覆盖安装。

> ⚠️ 前提：该接口匿名访问，**仓库必须是公开的**（或另建一个公开仓库专门放 Release），否则返回 404，App 会提示“找不到发布版本”。匿名请求有 GitHub 速率限制（每 IP 每小时 60 次），足够正常使用。

另外，App 启动时会静默检查一次，有新版本时在“我的”标签上显示红点。

## 未使用 / 已知的其他接口

网站还有以下接口，App 目前未使用，记录备查：

| 接口 | 说明 |
| --- | --- |
| `GET /api/v1/public/pushes/signal/{signalId}` | 信号推送配置 |
| `GET /api/v1/audience/match/{matchId}/view` | 观看人数统计 |
| `POST /api/v1/hubs/live/negotiate` | SignalR 实时比分推送（WebSocket） |
| `POST /api/v1/chat/bootstrap`、`/api/v1/chat/rooms/-{matchId}/history` | 聊天室 |
| `GET /api/v1/user/me` | 当前登录用户（未登录返回 401） |

## 风险提示

- 以上接口均为网站前端的内部接口，没有公开文档，网站改版时可能变化。若 App 出现“接口不存在”或解析失败，按本文的方法在浏览器开发者工具中重新抓包对照即可。
- 直播画面中的广告（如“推荐买球网址”）是直播源本身压制在画面里的，App 无法去除。

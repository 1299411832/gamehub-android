package space.devmini.gamehub

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import kotlin.random.Random

/**
 * 定期拉 `notify.json`，有新内容就弹一条每日摘要通知。
 *
 * 判据是 payload 的 `latestAt`（最新一条资源的时间戳），存在 SharedPreferences 里做基线：
 *  - 基线不存在 → 只记录，不弹（刚装完不该收到一条「三天前更新过」）；
 *  - 和上次一样 → 什么都不做（同一份文件重复拉取不重复打扰）；
 *  - 变了 → 过两道闸（20 小时节流 + 9~22 点静默时段）再弹。
 *
 * 没有推送服务，所以这里失败就交给 WorkManager 退避重试，不做自己的重试逻辑。
 */
class NotifyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val prefs = context.getSharedPreferences(Notifications.PREFS, Context.MODE_PRIVATE)

        val payload = try {
            fetchNotify()
        } catch (e: Exception) {
            return Result.retry() // 网络/解析失败：交给 WorkManager 退避重试
        }

        if (payload.isNull("latestAt")) return Result.success()
        val latestAt = payload.optString("latestAt")
        if (latestAt.isBlank()) return Result.success()

        val lastSeen = prefs.getString(Notifications.KEY_LAST_SEEN, null)
        if (lastSeen == latestAt) return Result.success()

        // 首次运行：只立基线。别把安装前攒的更新当成"新消息"砸给用户。
        if (lastSeen == null) {
            prefs.edit().putString(Notifications.KEY_LAST_SEEN, latestAt).apply()
            return Result.success()
        }

        // 闸一：每日摘要，两次推送至少隔 20 小时
        val lastNotifyAt = prefs.getLong(Notifications.KEY_LAST_NOTIFY_AT, 0L)
        if (System.currentTimeMillis() - lastNotifyAt < Notifications.MIN_NOTIFY_INTERVAL_MS) {
            return Result.success()
        }

        // 闸二：静默时段。这里刻意不更新基线，白天那次检查会补弹。
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (hour < Notifications.QUIET_HOUR_START || hour >= Notifications.QUIET_HOUR_END) {
            return Result.success()
        }

        // 没通知权限：记下基线，避免用户以后授权时被一条陈年旧闻突袭
        if (!Notifications.isPermitted(context)) {
            prefs.edit().putString(Notifications.KEY_LAST_SEEN, latestAt).apply()
            return Result.success()
        }

        val shown = Notifications.showDigest(
            context,
            buildTitle(payload),
            buildText(payload),
            payload.optString("tapUrl").ifBlank { null }
        )

        if (shown) {
            prefs.edit()
                .putString(Notifications.KEY_LAST_SEEN, latestAt)
                .putLong(Notifications.KEY_LAST_NOTIFY_AT, System.currentTimeMillis())
                .apply()
        }
        return Result.success()
    }

    // ────────────────────────────────────────────────────────── 文案

    /**
     * 标题从服务端的 titles 池里随机挑，占位符在端上填。
     * 文案维护在 scripts/gen-notify.js，改钩子不用发新版 APK。
     */
    private fun buildTitle(payload: JSONObject): String {
        val digest = payload.optJSONObject("digest") ?: JSONObject()
        val count = digest.optInt("count", 0)
        val top = shorten(digest.optString("topTitle"), 18)
        val category = topCategoryName(digest)

        val titles = payload.optJSONArray("titles")
        val template = if (titles != null && titles.length() > 0) {
            titles.getString(Random.nextInt(titles.length()))
        } else {
            DEFAULT_TITLE
        }

        return template
            .replace("{count}", count.toString())
            .replace("{top}", top.ifBlank { "新资源" })
            .replace("{cat}", category)
            .trim()
    }

    private fun buildText(payload: JSONObject): String {
        val digest = payload.optJSONObject("digest") ?: JSONObject()
        val parts = mutableListOf<String>()
        val newCount = digest.optInt("newCount", 0)
        val updateCount = digest.optInt("updateCount", 0)
        if (newCount > 0) parts += "新增 $newCount 个"
        if (updateCount > 0) parts += "更新 $updateCount 个"
        if (parts.isEmpty()) parts += "有资源更新"

        val top = shorten(digest.optString("topTitle"), 20)
        val prefix = digest.optString("dayLabel")
        val tail = if (top.isBlank()) "" else "，先看《$top》"
        return "$prefix${parts.joinToString(" · ")}资源$tail"
    }

    private fun topCategoryName(digest: JSONObject): String {
        val categories = digest.optJSONArray("categories") ?: return ""
        val first = categories.optJSONObject(0) ?: return ""
        return first.optString("name").ifBlank { first.optString("key") }
    }

    private fun shorten(text: String, max: Int): String {
        val t = text.trim()
        return if (t.length > max) t.take(max) + "…" else t
    }

    // ────────────────────────────────────────────────────────── 网络

    /**
     * 拉 `/data/notify.json`。加时间戳查询参数是因为走的是系统 HTTP 栈，
     * 中间层（nginx / 反代）缓存命中旧文件的代价就是"资源更新了但收不到通知"。
     */
    private fun fetchNotify(): JSONObject {
        val url = URL(SiteConfig.HOME_URL.trimEnd('/') + SiteConfig.NOTIFY_PATH + "?_=" + System.currentTimeMillis())
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
            instanceFollowRedirects = true
        }
        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("notify.json HTTP $code")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        /** titles 池意外为空时的兜底（服务端正常会带 10 条）。 */
        const val DEFAULT_TITLE = "有 {count} 个新资源上架了"
    }
}

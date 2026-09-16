package space.devmini.gamehub

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import kotlin.random.Random

/**
 * 在固定时段（见 `Notifications.SLOT_HOURS`）拉 `notify.json`，弹一条资源更新摘要。
 *
 * 调度不是周期性的，而是「一次执行完就把下一个时段排上」的链条：
 * WorkManager 的周期任务只能给最小间隔，给不了「每天 13:00 和 20:00」这种墙钟时间。
 *
 * 去重按**时段**（`2026-09-16T13`），不是按「距上次多久」：
 * 同一次执行被退避重试、或者排程被 Doze 推迟，都不会对同一时段重复弹。
 *
 * 内容每次随机挑一条近期资源 + 随机钩子标题 —— 同一天的两次推送才不会念同一句。
 * 没有推送服务，所以网络失败就交给 WorkManager 退避重试，不做自己的重试逻辑。
 */
class NotifyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        return try {
            run(context)
        } finally {
            // 不管成功、跳过还是退避重试，都要把下一个时段续上 —— 链条断一次就再也不会响。
            // 用 finally 是因为 Result.retry() 也会走到这里。
            Notifications.scheduleNext(context)
        }
    }

    private suspend fun run(context: Context): Result {
        val prefs = context.getSharedPreferences(Notifications.PREFS, Context.MODE_PRIVATE)

        // 时段 key 由排程时写进 inputData；拿不到（异常路径）就退回当前整点。
        val slot = inputData.getString(KEY_SLOT) ?: Notifications.slotKey(System.currentTimeMillis())
        if (prefs.getString(Notifications.KEY_LAST_SLOT, null) == slot) {
            return Result.success() // 这个时段已经推过了（重试或重复排程）
        }

        val payload = try {
            fetchNotify()
        } catch (e: Exception) {
            return Result.retry() // 网络/解析失败：交给 WorkManager 退避重试
        }

        val items = payload.optJSONArray("items")
        if (items == null || items.length() == 0) {
            return Result.success() // 近期没有任何资源，没有可说的内容，别硬发一条空的
        }

        // 兜底静默时段：时段正常落在 13/20 点，这条只为挡住被推迟到深夜的执行。
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (hour < Notifications.QUIET_HOUR_START || hour >= Notifications.QUIET_HOUR_END) {
            return Result.success()
        }

        // 没通知权限：记下时段，避免用户以后授权时被一条陈年旧闻突袭
        if (!Notifications.isPermitted(context)) {
            prefs.edit().putString(Notifications.KEY_LAST_SLOT, slot).apply()
            return Result.success()
        }

        val featured = items.optJSONObject(Random.nextInt(items.length()))
        val shown = Notifications.showDigest(
            context,
            buildTitle(payload, featured),
            buildText(payload, featured),
            payload.optString("tapUrl").ifBlank { null }
        )

        if (shown) {
            prefs.edit().putString(Notifications.KEY_LAST_SLOT, slot).apply()
        }
        return Result.success()
    }

    // ────────────────────────────────────────────────────────── 文案

    /**
     * 标题从服务端的 titles 池里随机挑，占位符在端上填；`{top}` 用本次随机挑中的那条资源。
     * 文案维护在 scripts/gen-notify.js，改钩子不用发新版 APK。
     */
    private fun buildTitle(payload: JSONObject, featured: JSONObject?): String {
        val digest = payload.optJSONObject("digest") ?: JSONObject()
        val count = digest.optInt("count", 0)
        val top = shorten(featured?.optString("title").orEmpty(), 18)
            .ifBlank { shorten(digest.optString("topTitle"), 18) }
        val category = categoryNameOf(payload, featured)

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

    private fun buildText(payload: JSONObject, featured: JSONObject?): String {
        val digest = payload.optJSONObject("digest") ?: JSONObject()
        val parts = mutableListOf<String>()
        val newCount = digest.optInt("newCount", 0)
        val updateCount = digest.optInt("updateCount", 0)
        if (newCount > 0) parts += "新增 $newCount 个"
        if (updateCount > 0) parts += "更新 $updateCount 个"
        if (parts.isEmpty()) parts += "有资源更新"

        val title = shorten(
            featured?.optString("title").orEmpty().ifBlank { digest.optString("topTitle") },
            20
        )
        val prefix = digest.optString("dayLabel")
        val tail = if (title.isBlank()) "" else "，先看《$title》"
        return "$prefix${parts.joinToString(" · ")}资源$tail"
    }

    /** 分类中文名取自 notify.json 的 digest.categories（源头是站点的 categories.json）。 */
    private fun categoryNameOf(payload: JSONObject, featured: JSONObject?): String {
        val categories = payload.optJSONObject("digest")?.optJSONArray("categories")
        val key = featured?.optString("category").orEmpty()
        if (categories != null && key.isNotBlank()) {
            for (i in 0 until categories.length()) {
                val item = categories.optJSONObject(i) ?: continue
                if (item.optString("key") == key) {
                    return item.optString("name").ifBlank { key }
                }
            }
        }
        val first = categories?.optJSONObject(0) ?: return ""
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

    companion object {
        /** 排程时写进来的时段 key（如 `2026-09-16T13`）。 */
        const val KEY_SLOT = "slot"

        /** titles 池意外为空时的兜底（服务端正常会带 10 条）。 */
        private const val DEFAULT_TITLE = "有 {count} 个新资源上架了"
    }
}

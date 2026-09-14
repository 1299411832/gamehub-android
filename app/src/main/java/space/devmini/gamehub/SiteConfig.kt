package space.devmini.gamehub

/**
 * 壳里唯一定义站点地址的地方。
 *
 * 为什么单独抽出来：MainActivity（WebView 首页 / 同站判断）和 NotifyWorker
 * （拉 notify.json）都要用同一个域名，抄成两份迟早会漂移。
 */
object SiteConfig {

    /** 壳加载的首页，也是所有相对路径的解析基准。 */
    const val HOME_URL = "https://mibear.top/"

    /** 只信任这个域及其子域。 */
    const val BASE_HOST = "mibear.top"

    /** 每日摘要通知的数据源（构建期由 scripts/gen-notify.js 生成）。 */
    const val NOTIFY_PATH = "/data/notify.json"

    fun isSameSite(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val h = host.lowercase()
        return h == BASE_HOST || h.endsWith(".$BASE_HOST")
    }

    /**
     * 把 payload 里的相对路径（如 `/changelog.html`）解析成绝对 URL。
     * 站外地址一律拒绝 —— 通知里的 target 不该成为打开任意 URL 的后门。
     */
    fun resolve(pathOrUrl: String?): String? {
        if (pathOrUrl.isNullOrBlank()) return null
        val trimmed = pathOrUrl.trim()
        if (!trimmed.startsWith("/")) {
            val host = runCatching { android.net.Uri.parse(trimmed).host }.getOrNull()
            return if (isSameSite(host)) trimmed else null
        }
        return HOME_URL.trimEnd('/') + trimmed
    }
}

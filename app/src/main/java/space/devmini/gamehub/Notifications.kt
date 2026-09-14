package space.devmini.gamehub

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit


/**
 * 每日摘要通知的渠道 / 调度 / 投递。
 *
 * 方案是「App 端本地通知 + 服务端静态 notify.json」，没有推送服务、没有长连接：
 * WorkManager 定期拉一次 notify.json，有更新才弹。所以这里没有 FCM 依赖，
 * 也不需要保活 —— 代价是延迟（Doze 下几小时），对「资源更新」这种场景可以接受。
 */
object Notifications {

    /** 渠道 id 一旦上线就不要再改：改了等于把用户的通知设置重置一遍。 */
    const val CHANNEL_ID = "gamehub_updates"
    private const val NOTIFICATION_ID = 1001

    /** 通知点击后带的目标地址，MainActivity 读它做深链。 */
    const val EXTRA_TARGET_URL = "space.devmini.gamehub.TARGET_URL"

    /** 通知相关的本地状态：MainActivity（权限询问）和 NotifyWorker（基线/节流）共用。 */
    const val PREFS = "gamehub_notify"
    const val KEY_LAST_SEEN = "last_seen_latest_at"
    const val KEY_LAST_NOTIFY_AT = "last_notify_at"
    const val KEY_PERM_ASKED = "perm_asked"

    private const val WORK_NAME = "gamehub-daily-digest"
    private const val CHECK_INTERVAL_HOURS = 6L

    /** 两次摘要的最小间隔，保证「每日」而不是「每次有新资源」。 */
    const val MIN_NOTIFY_INTERVAL_MS = 20L * 60 * 60 * 1000

    /** 静默时段：这段时间内不弹，等下一次检查（避免凌晨 3 点放毒）。 */
    const val QUIET_HOUR_START = 9
    const val QUIET_HOUR_END = 22

    // ────────────────────────────────────────────────────────── channel

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.channel_updates),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.channel_updates_desc)
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }

    // ────────────────────────────────────────────────────────── scheduling

    /**
     * 幂等调度：每次冷启动都调一次，KEEP 策略保证不会重复排队。
     * WorkManager 自己持久化，重启/杀进程后仍在。周期选 6 小时是因为通知本身有
     * 20 小时节流，检查频率只影响「多久能发现新资源」，不影响打扰次数。
     */
    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<NotifyWorker>(
            CHECK_INTERVAL_HOURS, TimeUnit.HOURS
        ).setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
        ).build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    fun isPermitted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    // ────────────────────────────────────────────────────────── post

    /** 弹一条摘要通知。返回 false 表示用户没给通知权限（静默失败，不抛）。 */
    fun showDigest(context: Context, title: String, text: String, tapUrl: String?): Boolean {
        if (!isPermitted(context)) return false
        ensureChannel(context)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (!tapUrl.isNullOrBlank()) putExtra(EXTRA_TARGET_URL, tapUrl)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        return try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            true
        } catch (e: SecurityException) {
            // 权限被撤销（用户在设置里关闭）：不崩，下次检查再说
            false
        }
    }
}

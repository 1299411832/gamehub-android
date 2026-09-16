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
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit


/**
 * 摘要通知的渠道 / 调度 / 投递。
 *
 * 方案是「App 端本地通知 + 服务端静态 notify.json」，没有推送服务、没有长连接：
 * 每天 13:00 与 20:00 两个时段各拉一次 notify.json 弹一条。所以这里没有 FCM 依赖，
 * 也不需要保活 —— 代价是延迟（Doze 或系统省电下时段会被推迟），
 * 对「资源更新」这种场景可以接受。
 */
object Notifications {

    /** 渠道 id 一旦上线就不要再改：改了等于把用户的通知设置重置一遍。 */
    const val CHANNEL_ID = "gamehub_updates"
    private const val NOTIFICATION_ID = 1001

    /** 通知点击后带的目标地址，MainActivity 读它做深链。 */
    const val EXTRA_TARGET_URL = "space.devmini.gamehub.TARGET_URL"

    /** 通知相关的本地状态：MainActivity（权限询问）和 NotifyWorker（时段去重）共用。 */
    const val PREFS = "gamehub_notify"
    const val KEY_PERM_ASKED = "perm_asked"

    /** 已推送过的时段 key（形如 `2026-09-16T13`），同一时段只推一次。 */
    const val KEY_LAST_SLOT = "last_notify_slot"

    /**
     * 每天两个固定时段，设备本地时间整点。改这里等于改打扰频率。
     * 用本地时间而不是钉死北京时间：用户在哪个时区就按哪里的 13 点，不半夜炸人。
     */
    val SLOT_HOURS = intArrayOf(13, 20)

    /**
     * 一个时段一个唯一 work 名。别用固定名字：下一次排程会跟「正在跑」或
     * 「正在退避重试」的那一次撞名，REPLACE 会把重试逻辑打断。
     */
    private const val WORK_NAME_PREFIX = "gamehub-digest-"

    /** 1.0.4 的周期 work（6 小时检查 + 20 小时节流），升级后必须显式取消，否则它还会按老逻辑触发。 */
    private const val LEGACY_WORK_NAME = "gamehub-daily-digest"

    /**
     * 静默时段：只做兜底 —— 正常时段在 13 点和 20 点，本来就落在窗口内；
     * 这一条是防 Doze/省电把某次执行推迟到凌晨。
     */
    const val QUIET_HOUR_START = 9
    const val QUIET_HOUR_END = 22

    // ────────────────────────────────────────────────────────── channel

    /**
     * 每次都调 `createNotificationChannel`：同 id 重复创建只会刷新名称/描述，
     * 不会重置用户的渠道设置（重要性只能由用户往上调），所以描述文案改了能自然生效。
     */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
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
     * 把下一个时段的唯一 work 排上。冷启动每次都调，KEEP 策略保证同一时段只排一次。
     * 工作链由 NotifyWorker 自己在每次执行末尾续上（见 NotifyWorker.doWork 的 finally）。
     * WorkManager 自己持久化排队状态，重启后仍在，不需要 BOOT_COMPLETED 接收器。
     */
    fun scheduleNext(context: Context) {
        val manager = WorkManager.getInstance(context)
        manager.cancelUniqueWork(LEGACY_WORK_NAME)

        val (slotKey, delayMs) = nextSlot(System.currentTimeMillis())
        val request = OneTimeWorkRequestBuilder<NotifyWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setInputData(workDataOf(NotifyWorker.KEY_SLOT to slotKey))
            .build()

        manager.enqueueUniqueWork(WORK_NAME_PREFIX + slotKey, ExistingWorkPolicy.KEEP, request)
    }

    /** 下一个时段：今天的 13:00 / 20:00；都过了就是明天的第一个。返回 (时段 key, 还有多少毫秒)。 */
    fun nextSlot(now: Long): Pair<String, Long> {
        for (hour in SLOT_HOURS) {
            val at = slotTime(now, hour, 0)
            if (at > now) return slotKey(at) to (at - now)
        }
        val at = slotTime(now, SLOT_HOURS[0], 1)
        return slotKey(at) to (at - now)
    }

    /** now 所在那天（dayOffset 天之后）的 hour:00:00.000 的绝对时间戳。 */
    private fun slotTime(now: Long, hour: Int, dayOffset: Int): Long =
        Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.DAY_OF_YEAR, dayOffset)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    /** 时段 key 精确到小时：一天两个整点时段，拿毫秒做 key 只会让去重失效。 */
    fun slotKey(at: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH", Locale.US).format(Date(at))

    // ────────────────────────────────────────────────────────── permission

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
            // 权限被撤销（用户在设置里关闭）：不崩，下次时段再说
            false
        }
    }
}

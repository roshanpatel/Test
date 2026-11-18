package app.jolt

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The morning "Working from home today?" notification. An alarm posts it at the check-in time
 * each day; its buttons record the answer. Not answering counts as a home day.
 */
class CheckInReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_ASK -> {
                CheckIn.notify(context)
                CheckIn.scheduleNext(context)
            }
            ACTION_HOME -> CheckIn.answer(context, DayPlan.HOME)
            ACTION_OUT -> CheckIn.answer(context, DayPlan.OUT)
        }
    }

    companion object {
        const val ACTION_ASK = "app.jolt.CHECK_IN_ASK"
        const val ACTION_HOME = "app.jolt.CHECK_IN_HOME"
        const val ACTION_OUT = "app.jolt.CHECK_IN_OUT"
    }
}

object CheckIn {
    private const val CHANNEL = "check_in"
    private const val NOTIFICATION_ID = 1

    /** Sets the alarm for the next check-in. Safe to call often: it replaces the last one. */
    fun scheduleNext(context: Context) {
        val schedule = Prefs(context).schedule
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pending = broadcast(context, CheckInReceiver.ACTION_ASK)
        if (!schedule.on) {
            alarms.cancel(pending)
            return
        }
        val now = LocalDateTime.now()
        var at = LocalDate.now().atStartOfDay().plusMinutes(schedule.checkInMin.toLong())
        if (!at.isAfter(now)) at = at.plusDays(1)
        val atMs = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        // Inexact, so no special permission is needed; Android may deliver it a few minutes late.
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pending)
    }

    fun notify(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Morning check-in", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Asks each morning whether you're working from home."
            },
        )
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Working from home today?")
            .setContentText("Yes: watched apps stay off all day. No: your schedule applies. No answer counts as yes.")
            .setStyle(Notification.BigTextStyle())
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(Notification.Action.Builder(null, "Yes, from home", broadcast(context, CheckInReceiver.ACTION_HOME)).build())
            .addAction(Notification.Action.Builder(null, "No, I'm out", broadcast(context, CheckInReceiver.ACTION_OUT)).build())
            .build()
        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // Notifications not allowed: the main screen still lets the user answer.
        }
    }

    /** Records today's answer. Returns false if it isn't allowed (a home day is final). */
    fun answer(context: Context, plan: DayPlan): Boolean {
        val prefs = Prefs(context)
        val updated = prefs.schedule.answer(AndroidClock(context).localMinute(), plan)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIFICATION_ID)
        if (updated == null) {
            Toast.makeText(context, "Today's already a home day. That can't be changed.", Toast.LENGTH_LONG).show()
            return false
        }
        prefs.schedule = updated
        val message = when (plan) {
            DayPlan.HOME -> "Home day: watched apps are off until tomorrow's check-in."
            DayPlan.OUT -> "Have a good day. Your schedule applies."
        }
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        return true
    }

    private fun broadcast(context: Context, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            action.hashCode(),
            Intent(context, CheckInReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}

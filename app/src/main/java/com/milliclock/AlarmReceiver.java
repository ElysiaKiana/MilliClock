package com.milliclock;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 事件提前提醒：到点发高优先级通知（闹钟铃声 + 震动 + 横幅），
 * 点「开始倒计时」直接启动悬浮窗并设为该事件目标。
 */
public class AlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        long id = intent == null ? 0 : intent.getLongExtra("id", 0);
        List<Event> list = EventStore.load(context);
        Event e = EventStore.findById(list, id);
        if (e == null) return;

        if (e.remind && !e.done) notifyEvent(context, e);

        // 滚动到下一次：重复事件注册下一轮提醒，单次事件标记结束
        if (e.mode == 0) {
            e.done = true;
        } else {
            long next = e.computeNext(e.nextTargetMs + 1);
            if (next > 0) e.nextTargetMs = next;
            else e.done = true;
        }
        EventStore.save(context, list);
        EventStore.scheduleAlarm(context, e);
    }

    private void notifyEvent(Context c, Event e) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        NotificationChannel ch = new NotificationChannel("event", "事件提醒", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("事件开始前的提前提醒");
        ch.enableVibration(true);
        try {
            Uri sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (sound == null) sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            ch.setSound(sound, new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
        } catch (Exception ignore) { }
        nm.createNotificationChannel(ch);

        SimpleDateFormat fmt = new SimpleDateFormat("HH:mm", Locale.CHINA);
        PendingIntent content = PendingIntent.getActivity(c, 10,
                new Intent(c, EventListActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent svc = new Intent(c, FloatingService.class)
                .setAction(FloatingService.ACTION_SET_TARGET)
                .putExtra("targetMs", e.nextTargetMs);
        PendingIntent piStart = PendingIntent.getForegroundService(c, 11, svc,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification n = new Notification.Builder(c, "event")
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("⏰ " + e.name)
                .setContentText("将于 " + fmt.format(new Date(e.nextTargetMs))
                        + " 开始" + (e.remindMin > 0 ? " · 还有 " + e.remindMin + " 分钟" : ""))
                .setCategory(Notification.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(content)
                .addAction(new Notification.Action(android.R.drawable.ic_media_play,
                        "开始倒计时", piStart))
                .build();
        nm.notify((int) (e.id & 0x7fffffffL), n);
    }
}

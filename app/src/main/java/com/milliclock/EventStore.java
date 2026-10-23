package com.milliclock;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import org.json.JSONArray;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * 事件持久化（filesDir/events.json，零第三方依赖）+ 精确提醒调度。
 * 用 AlarmManager.setAlarmClock：无需精确闹钟权限，触发等级等同系统闹钟，
 * 状态栏显示闹钟图标，MIUI/Doze 下也能准时唤醒。
 */
public class EventStore {

    private static final String FILE = "events.json";

    public static List<Event> load(Context c) {
        List<Event> out = new ArrayList<>();
        try {
            File f = new File(c.getFilesDir(), FILE);
            if (!f.exists()) return out;
            BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            JSONArray arr = new JSONArray(sb.toString());
            for (int i = 0; i < arr.length(); i++) out.add(Event.fromJson(arr.getJSONObject(i)));
        } catch (Exception ignore) { }
        return out;
    }

    public static void save(Context c, List<Event> list) {
        try {
            JSONArray arr = new JSONArray();
            for (Event e : list) arr.put(e.toJson());
            FileOutputStream fo = new FileOutputStream(new File(c.getFilesDir(), FILE));
            fo.write(arr.toString().getBytes("UTF-8"));
            fo.close();
        } catch (Exception ignore) { }
    }

    public static Event findById(List<Event> list, long id) {
        for (Event e : list) if (e.id == id) return e;
        return null;
    }

    /** 把过期事件滚动到下一次并重新注册提醒；开机、打开事件页时调用 */
    public static void refreshAndSchedule(Context c) {
        List<Event> list = load(c);
        long now = TimeSync.now();
        boolean changed = false;
        for (Event e : list) {
            if (e.nextTargetMs <= now) {
                long next = e.computeNext(now);
                if (next > 0) { e.nextTargetMs = next; e.done = false; }
                else e.done = true;
                changed = true;
            }
        }
        if (changed) save(c, list);
        for (Event e : list) scheduleAlarm(c, e);
    }

    /** 注册/取消某事件的提前提醒闹钟 */
    public static void scheduleAlarm(Context c, Event e) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = alarmPendingIntent(c, e);
        boolean valid = e.remind && !e.done && e.remindMin > 0 && e.nextTargetMs > TimeSync.now();
        if (!valid) {
            am.cancel(pi);
            return;
        }
        long trigger = e.nextTargetMs - e.remindMin * 60000L;
        if (trigger <= TimeSync.now()) trigger = TimeSync.now() + 2000; // 提醒时刻已过但事件未开始：立即提醒
        PendingIntent show = PendingIntent.getActivity(c, 0,
                new Intent(c, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.setAlarmClock(new AlarmManager.AlarmClockInfo(trigger, show), pi);
    }

    public static void cancelAlarm(Context c, Event e) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(alarmPendingIntent(c, e));
    }

    private static PendingIntent alarmPendingIntent(Context c, Event e) {
        Intent i = new Intent(c, AlarmReceiver.class).putExtra("id", e.id);
        return PendingIntent.getBroadcast(c, (int) (e.id & 0x7fffffffL), i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}

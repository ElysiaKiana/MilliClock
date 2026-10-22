package com.milliclock;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.Locale;

/**
 * 事件模型：预设开售/开始时间，到点前 N 分钟提醒，并可一键设为悬浮窗倒计时目标。
 * mode: 0=单次 1=每天 2=每周几
 * days: 每周几位掩码（bit i 对应 Calendar.DAY_OF_WEEK - 1，0=周日）
 */
public class Event {
    public long id;
    public String name;
    public int mode;
    public int hour, minute;      // 每天的触发时刻（含单次）
    public long base;             // 单次事件的绝对时刻（epoch ms）
    public int days;              // 每周几位掩码（mode=2 有效）
    public boolean remind;        // 是否提前提醒
    public int remindMin;         // 提前提醒分钟数（0=不提醒）
    public long nextTargetMs;     // 下一次事件开始时刻
    public boolean done;          // 已结束（单次事件过期后标记）

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("name", name);
        o.put("mode", mode);
        o.put("hour", hour);
        o.put("minute", minute);
        o.put("base", base);
        o.put("days", days);
        o.put("remind", remind);
        o.put("remindMin", remindMin);
        o.put("next", nextTargetMs);
        o.put("done", done);
        return o;
    }

    public static Event fromJson(JSONObject o) {
        Event e = new Event();
        e.id = o.optLong("id");
        e.name = o.optString("name", "未命名事件");
        e.mode = o.optInt("mode", 0);
        e.hour = o.optInt("hour", 0);
        e.minute = o.optInt("minute", 0);
        e.base = o.optLong("base", 0L);
        e.days = o.optInt("days", 0);
        e.remind = o.optBoolean("remind", false);
        e.remindMin = o.optInt("remindMin", 0);
        e.nextTargetMs = o.optLong("next", 0L);
        e.done = o.optBoolean("done", false);
        return e;
    }

    /** 计算自 fromMs 之后（严格大于）的下一次触发时刻；无则返回 -1 */
    public long computeNext(long fromMs) {
        if (mode == 0) return base > fromMs ? base : -1L;
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(fromMs);
        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, minute);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (mode == 1) {
            if (c.getTimeInMillis() > fromMs) return c.getTimeInMillis();
            c.add(Calendar.DATE, 1);
            return c.getTimeInMillis();
        }
        // 每周几：从今天起最多找 8 天
        for (int i = 0; i < 8; i++) {
            int dow = c.get(Calendar.DAY_OF_WEEK) - 1; // 0=周日
            if ((days & (1 << dow)) != 0 && c.getTimeInMillis() > fromMs) return c.getTimeInMillis();
            c.add(Calendar.DATE, 1);
        }
        return -1L;
    }
}

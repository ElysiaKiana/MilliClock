package com.milliclock;

import android.content.Context;
import android.content.SharedPreferences;

/** 轻量配置存取，仅几 KB 的 SharedPreferences，不引入任何第三方库 */
public class Prefs {
    private final SharedPreferences sp;

    public Prefs(Context c) {
        sp = c.getSharedPreferences("cfg", Context.MODE_PRIVATE);
    }

    /** 0=时钟模式 1=倒计时模式 */
    public int mode()             { return sp.getInt("mode", 1); }
    public void mode(int v)       { sp.edit().putInt("mode", v).apply(); }

    /** 目标开售时间 (epoch 毫秒) */
    public long target()          { return sp.getLong("target", 0L); }
    public void target(long v)    { sp.edit().putLong("target", v).apply(); }

    /** 悬浮窗位置 */
    public int x()                { return sp.getInt("x", 40); }
    public void x(int v)          { sp.edit().putInt("x", v).apply(); }
    public int y()                { return sp.getInt("y", 220); }
    public void y(int v)          { sp.edit().putInt("y", v).apply(); }

    /** 时间文字大小 (sp) */
    public int size()             { return sp.getInt("size", 42); }
    public void size(int v)       { sp.edit().putInt("size", v).apply(); }

    /** 刷新间隔 (ms) */
    public int rate()             { return sp.getInt("rate", 20); }
    public void rate(int v)       { sp.edit().putInt("rate", v).apply(); }

    /** 触摸穿透 */
    public boolean pass()         { return sp.getBoolean("pass", false); }
    public void pass(boolean v)   { sp.edit().putBoolean("pass", v).apply(); }

    /** 到点震动 */
    public boolean vibrate()      { return sp.getBoolean("vibrate", true); }
    public void vibrate(boolean v){ sp.edit().putBoolean("vibrate", v).apply(); }

    /** 最近一次校时结果 */
    public long offset()          { return sp.getLong("offset", 0L); }
    public void offset(long v)    { sp.edit().putLong("offset", v).apply(); }
    public long rtt()             { return sp.getLong("rtt", 0L); }
    public void rtt(long v)       { sp.edit().putLong("rtt", v).apply(); }
    public String server()        { return sp.getString("server", ""); }
    public void server(String v)  { sp.edit().putString("server", v).apply(); }
    public boolean rough()        { return sp.getBoolean("rough", false); }
    public void rough(boolean v)  { sp.edit().putBoolean("rough", v).apply(); }
    public long syncWall()        { return sp.getLong("syncWall", 0L); }
    public void syncWall(long v)  { sp.edit().putLong("syncWall", v).apply(); }
    public long syncElapsed()     { return sp.getLong("syncElapsed", 0L); }
    public void syncElapsed(long v){ sp.edit().putLong("syncElapsed", v).apply(); }
}

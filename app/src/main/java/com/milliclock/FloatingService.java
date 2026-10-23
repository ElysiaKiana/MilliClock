package com.milliclock;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 前台服务 + 系统悬浮窗（TYPE_APPLICATION_OVERLAY）。
 * 省资源设计：
 *  - 零第三方库，APK 仅几十 KB
 *  - 非焦点窗口（FLAG_NOT_FOCUSABLE），不抢键盘/返回键，不影响其他 App
 *  - 触摸穿透模式（FLAG_NOT_TOUCHABLE），手指操作完全穿透悬浮窗
 *  - 只更新一个小 TextView，刷新频率可调 10/20/50ms
 */
public class FloatingService extends Service {

    public static final String ACTION_SHOW = "com.milliclock.SHOW";
    public static final String ACTION_HIDE = "com.milliclock.HIDE";
    public static final String ACTION_EXIT = "com.milliclock.EXIT";
    public static final String ACTION_APPLY = "com.milliclock.APPLY";
    public static final String ACTION_TOGGLE_PASS = "com.milliclock.TOGGLE_PASS";
    public static final String ACTION_SET_TARGET = "com.milliclock.SET_TARGET";

    public static volatile boolean running = false;
    public static volatile boolean visible = false;

    private WindowManager wm;
    private LinearLayout root, menuRow;
    private TextView tvTime, tvInfo;
    private WindowManager.LayoutParams lp;
    private Handler h;
    private Prefs p;
    private boolean wasBefore = true;
    private boolean firstTick = true;

    private final SimpleDateFormat fmtClock = new SimpleDateFormat("HH:mm:ss.SSS", Locale.CHINA);
    private final SimpleDateFormat fmtDate = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);
    private final SimpleDateFormat fmtTarget = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.CHINA);

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (!running) return;
            tick();
            h.postDelayed(this, p.rate());
        }
    };

    private final Runnable autoSync = new Runnable() {
        @Override public void run() {
            if (!running) return;
            syncInBackground(true);
            h.postDelayed(this, 10 * 60 * 1000L); // 每 10 分钟自动校一次时
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        p = new Prefs(this);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        h = new Handler(Looper.getMainLooper());
        TimeSync.restoreFrom(p);
        try {
            Notification n = buildNotification();
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(1, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(1, n);
            }
        } catch (Exception e) {
            stopSelf();
            return;
        }
        buildView();
        h.post(ticker);
        h.postDelayed(autoSync, 10 * 60 * 1000L);
        syncInBackground(true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? ACTION_SHOW : intent.getAction();
        if (ACTION_SHOW.equals(a)) show();
        else if (ACTION_HIDE.equals(a)) hide();
        else if (ACTION_EXIT.equals(a)) exit();
        else if (ACTION_TOGGLE_PASS.equals(a)) { p.pass(!p.pass()); applyWindow(); }
        else if (ACTION_SET_TARGET.equals(a)) {
            // 通知「开始倒计时」：设为该事件目标并显示悬浮窗
            long t = intent.getLongExtra("targetMs", 0);
            if (t > TimeSync.now()) {
                p.mode(1);
                p.target(t);
                firstTick = true;
            }
            show();
        }
        else if (ACTION_APPLY.equals(a)) applyWindow();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        running = false;
        h.removeCallbacksAndMessages(null);
        if (visible && root != null) {
            try { wm.removeView(root); } catch (Exception ignore) { }
        }
        visible = false;
        super.onDestroy();
    }

    // ---------- 悬浮窗 ----------

    private void buildView() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xCC0B1220);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), 0x33FFFFFF);
        root.setBackground(bg);
        int padH = dp(12), padV = dp(8);
        root.setPadding(padH, padV, padH, padV);

        tvTime = new TextView(this);
        tvTime.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tvTime.setTextColor(0xFFE2E8F0);
        tvTime.setShadowLayer(dp(3), 0, 0, 0xB4000000);
        tvTime.setTextSize(TypedValue.COMPLEX_UNIT_SP, p.size());
        root.addView(tvTime, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        tvInfo = new TextView(this);
        tvInfo.setTypeface(Typeface.MONOSPACE);
        tvInfo.setTextColor(0xFF94A3B8);
        tvInfo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        root.addView(tvInfo, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        menuRow = new LinearLayout(this);
        menuRow.setVisibility(View.GONE);
        menuRow.setPadding(0, dp(6), 0, 0);
        root.addView(menuRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        addMenuButton("校时", () -> syncInBackground(false));
        addMenuButton("穿透", () -> { p.pass(!p.pass()); applyWindow(); });
        addMenuButton("隐藏", this::hide);
        addMenuButton("退出", this::exit);

        // 拖动 + 点按呼出菜单（绑定在时间/信息两行上，菜单按钮本身可点击）
        View.OnTouchListener drag = new View.OnTouchListener() {
            float downX, downY;
            int startX, startY;
            long tDown;
            boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent ev) {
                switch (ev.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = ev.getRawX();
                        downY = ev.getRawY();
                        startX = lp.x;
                        startY = lp.y;
                        tDown = System.currentTimeMillis();
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (Math.abs(ev.getRawX() - downX) > 8 || Math.abs(ev.getRawY() - downY) > 8) moved = true;
                        lp.x = (int) (startX + (ev.getRawX() - downX));
                        lp.y = (int) (startY + (ev.getRawY() - downY));
                        if (visible) wm.updateViewLayout(root, lp);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (!moved && System.currentTimeMillis() - tDown < 400) {
                            menuRow.setVisibility(
                                    menuRow.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
                        } else if (moved) {
                            p.x(lp.x);
                            p.y(lp.y);
                        }
                        return true;
                }
                return false;
            }
        };
        tvTime.setOnTouchListener(drag);
        tvInfo.setOnTouchListener(drag);

        buildParams();
    }

    private void addMenuButton(String label, Runnable action) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setTextColor(0xFF93C5FD);
        b.setPadding(dp(10), dp(4), dp(10), dp(4));
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x22334155);
        g.setCornerRadius(dp(8));
        b.setBackground(g);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        if (menuRow.getChildCount() > 0) lp2.leftMargin = dp(8);
        b.setOnClickListener(v -> action.run());
        menuRow.addView(b, lp2);
    }

    private void buildParams() {
        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = p.x();
        lp.y = p.y();
        if (p.pass()) lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
    }

    private void show() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "没有悬浮窗权限，请在系统设置中开启", Toast.LENGTH_LONG).show();
            exit();
            return;
        }
        buildParams();
        if (!visible && root != null) {
            try {
                wm.addView(root, lp);
                visible = true;
            } catch (Exception e) {
                exit();
                return;
            }
        }
        updateNotification();
    }

    private void hide() {
        if (visible && root != null) {
            try { wm.removeView(root); } catch (Exception ignore) { }
            visible = false;
        }
        updateNotification();
    }

    private void applyWindow() {
        tvTime.setTextSize(TypedValue.COMPLEX_UNIT_SP, p.size());
        if (visible) {
            try { wm.removeView(root); } catch (Exception ignore) { }
            buildParams();
            try { wm.addView(root, lp); } catch (Exception e) { visible = false; }
        } else {
            buildParams();
        }
    }

    private void exit() {
        stopForeground(true);
        stopSelf();
    }

    // ---------- 计时显示 ----------

    private void tick() {
        long now = TimeSync.now();
        String calib = p.syncElapsed() == 0 ? "未校准"
                : "校准 " + (p.offset() >= 0 ? "+" : "") + p.offset() + "ms" + (p.rough() ? "(粗)" : "");

        if (p.mode() == 0) {
            // 时钟模式
            tvTime.setText(fmtClock.format(new Date(now)));
            tvInfo.setText(fmtDate.format(new Date(now)) + " · " + calib);
            tvTime.setTextColor(0xFFE2E8F0);
        } else {
            // 倒计时模式
            long target = p.target();
            if (target == 0) {
                tvTime.setText("--:--:--.---");
                tvInfo.setText("未设置目标时间，请在 App 内设置");
                tvTime.setTextColor(0xFFE2E8F0);
                firstTick = true;
                return;
            }
            long d = target - now;
            boolean before = d > 0;
            long a = Math.abs(d);
            tvTime.setText(String.format(Locale.US, "%s%02d:%02d:%02d.%03d",
                    before ? "-" : "+", a / 3600000, a / 60000 % 60, a / 1000 % 60, a % 1000));

            int color;
            if (!before) color = 0xFF4ADE80;        // 已开售：绿
            else if (a > 10000) color = 0xFFE2E8F0; // >10s：白
            else if (a > 5000) color = 0xFFFACC15;  // 5~10s：黄
            else if (a > 1000) color = 0xFFFB923C;  // 1~5s：橙
            else color = 0xFFF87171;                // <1s：红
            tvTime.setTextColor(color);

            if (!firstTick && wasBefore && !before && p.vibrate()) vibrateNow();
            wasBefore = before;
            firstTick = false;
            tvInfo.setText("目标 " + fmtTarget.format(new Date(target)) + " · " + calib);
        }
    }

    private void vibrateNow() {
        Vibrator v;
        if (Build.VERSION.SDK_INT >= 31) {
            VibratorManager vm = (VibratorManager) getSystemService(VIBRATOR_MANAGER_SERVICE);
            v = vm.getDefaultVibrator();
        } else {
            v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        }
        if (v != null) {
            v.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE));
        }
    }

    // ---------- 校时 ----------

    private void syncInBackground(final boolean quiet) {
        final Prefs pp = p;
        new Thread(() -> {
            final TimeSync.Result r = TimeSync.sync(pp);
            if (!quiet) {
                h.post(() -> Toast.makeText(FloatingService.this, r.ok
                        ? (r.rough ? "粗略校时 " : "校时 ") + (r.offset >= 0 ? "+" : "") + r.offset
                          + "ms · 延迟" + r.rtt + "ms"
                        : "校时失败: " + r.error, Toast.LENGTH_SHORT).show());
            }
        }, "timesync").start();
    }

    // ---------- 通知 ----------

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel("clock", "悬浮时钟", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);

        PendingIntent content = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent piToggle = PendingIntent.getService(this, 1,
                new Intent(this, FloatingService.class).setAction(ACTION_TOGGLE_PASS),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent piShowHide = PendingIntent.getService(this, 2,
                new Intent(this, FloatingService.class).setAction(visible ? ACTION_HIDE : ACTION_SHOW),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent piExit = PendingIntent.getService(this, 3,
                new Intent(this, FloatingService.class).setAction(ACTION_EXIT),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, "clock")
                .setSmallIcon(android.R.drawable.ic_menu_recent_history)
                .setContentTitle("毫秒悬浮钟运行中")
                .setContentText("点按打开设置")
                .setContentIntent(content)
                .setOngoing(true)
                .addAction(new Notification.Action(android.R.drawable.ic_menu_manage,
                        "穿透", piToggle))
                .addAction(new Notification.Action(android.R.drawable.ic_menu_view,
                        visible ? "显示" : "隐藏", piShowHide))
                .addAction(new Notification.Action(android.R.drawable.ic_menu_close_clear_cancel,
                        "退出", piExit))
                .build();
    }

    private void updateNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(1, buildNotification());
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}

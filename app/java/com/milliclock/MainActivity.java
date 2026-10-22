package com.milliclock;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int[] SIZES = {28, 42, 56, 72};
    private static final int[] RATES = {10, 20, 50};

    private Prefs p;
    private TextView tvStatus, tvTarget;
    private Button btnStart;
    private final ExecutorService es = Executors.newSingleThreadExecutor();
    private final SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.CHINA);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        p = new Prefs(this);
        TimeSync.restoreFrom(p);

        tvStatus = findViewById(R.id.tvStatus);
        tvTarget = findViewById(R.id.tvTarget);
        btnStart = findViewById(R.id.btnStart);
        RadioGroup rgMode = findViewById(R.id.rgMode);
        Spinner spSize = findViewById(R.id.spSize);
        Spinner spRate = findViewById(R.id.spRate);
        CheckBox cbPass = findViewById(R.id.cbPass);
        CheckBox cbVibrate = findViewById(R.id.cbVibrate);

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }

        rgMode.check(p.mode() == 0 ? R.id.rbClock : R.id.rbCount);
        rgMode.setOnCheckedChangeListener((group, id) -> {
            p.mode(id == R.id.rbClock ? 0 : 1);
            pushConfig();
        });

        ArrayAdapter<CharSequence> as = ArrayAdapter.createFromResource(
                this, R.array.sizes, android.R.layout.simple_spinner_dropdown_item);
        spSize.setAdapter(as);
        spSize.setSelection(indexOf(SIZES, p.size()));
        spSize.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View v, int pos, long id) {
                p.size(SIZES[pos]);
                pushConfig();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });

        ArrayAdapter<CharSequence> ar = ArrayAdapter.createFromResource(
                this, R.array.rates, android.R.layout.simple_spinner_dropdown_item);
        spRate.setAdapter(ar);
        spRate.setSelection(indexOf(RATES, p.rate()));
        spRate.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View v, int pos, long id) {
                p.rate(RATES[pos]);
                pushConfig();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });

        cbPass.setChecked(p.pass());
        cbPass.setOnCheckedChangeListener((b, v) -> { p.pass(v); pushConfig(); });
        cbVibrate.setChecked(p.vibrate());
        cbVibrate.setOnCheckedChangeListener((b, v) -> p.vibrate(v));

        findViewById(R.id.btnSync).setOnClickListener(v -> doSync());
        findViewById(R.id.btnPerm).setOnClickListener(v -> openOverlaySettings());
        findViewById(R.id.btnAppInfo).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName()))));
        findViewById(R.id.btnM1).setOnClickListener(v -> quickSet(60_000L));
        findViewById(R.id.btnM5).setOnClickListener(v -> quickSet(5 * 60_000L));
        findViewById(R.id.btnM10).setOnClickListener(v -> quickSet(10 * 60_000L));
        findViewById(R.id.btnHour).setOnClickListener(v -> nextHour());
        findViewById(R.id.btnPick).setOnClickListener(v -> pickTarget());
        findViewById(R.id.btnClear).setOnClickListener(v -> { p.target(0); refresh(); });
        btnStart.setOnClickListener(v -> toggleService());

        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private int indexOf(int[] arr, int v) {
        for (int i = 0; i < arr.length; i++) if (arr[i] == v) return i;
        return 1;
    }

    private void refresh() {
        if (p.syncElapsed() == 0) {
            tvStatus.setText("尚未校时 · 建议先点「立即校时」");
        } else {
            long off = p.offset();
            tvStatus.setText("校准偏移 " + (off >= 0 ? "+" : "") + off + "ms · "
                    + p.server() + (p.rough() ? " (粗略)" : "") + " · 延迟 " + p.rtt() + "ms");
        }
        long t = p.target();
        tvTarget.setText(t == 0 ? "未设置（倒计时模式需先设置）" : fmt.format(new Date(t)));
        btnStart.setText(FloatingService.running ? "停止悬浮窗" : "启动悬浮窗");
    }

    /** 配置变化时通知悬浮窗实时生效 */
    private void pushConfig() {
        if (FloatingService.running) {
            startService(new Intent(this, FloatingService.class)
                    .setAction(FloatingService.ACTION_APPLY));
        }
    }

    private void doSync() {
        Toast.makeText(this, "正在校时…", Toast.LENGTH_SHORT).show();
        es.execute(() -> {
            final TimeSync.Result r = TimeSync.sync(p);
            runOnUiThread(() -> {
                Toast.makeText(this, r.ok
                        ? "校时完成: 偏移 " + (r.offset >= 0 ? "+" : "") + r.offset
                          + "ms · 延迟 " + r.rtt + "ms · " + r.server + (r.rough ? " (粗略)" : "")
                        : "校时失败: " + r.error,
                        Toast.LENGTH_LONG).show();
                refresh();
            });
        });
    }

    private void toggleService() {
        if (FloatingService.running) {
            startService(new Intent(this, FloatingService.class)
                    .setAction(FloatingService.ACTION_EXIT));
            btnStart.setText("启动悬浮窗");
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先开启「悬浮窗权限」，小米手机在 应用信息→其他权限 里", Toast.LENGTH_LONG).show();
            openOverlaySettings();
            return;
        }
        startForegroundService(new Intent(this, FloatingService.class)
                .setAction(FloatingService.ACTION_SHOW));
        btnStart.setText("停止悬浮窗");
    }

    private void openOverlaySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        }
    }

    /** 快捷设置：当前校准时间 + N 分钟，秒和毫秒归零 */
    private void quickSet(long delta) {
        long t = (TimeSync.now() + delta) / 60_000L * 60_000L;
        p.target(t);
        refresh();
    }

    private void nextHour() {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.HOUR_OF_DAY, 1);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        p.target(c.getTimeInMillis());
        refresh();
    }

    /** 自定义目标时间：日期 → 时间(时分) → 毫秒，三步选择 */
    private void pickTarget() {
        Calendar c = Calendar.getInstance();
        new DatePickerDialog(this, (dp, y, m, d) -> {
            c.set(Calendar.YEAR, y);
            c.set(Calendar.MONTH, m);
            c.set(Calendar.DAY_OF_MONTH, d);
            new TimePickerDialog(this, (tp, hh, mm) -> {
                c.set(Calendar.HOUR_OF_DAY, hh);
                c.set(Calendar.MINUTE, mm);
                c.set(Calendar.SECOND, 0);
                askMs(c);
            }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true).show();
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void askMs(final Calendar c) {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setHint("0 - 999");
        new AlertDialog.Builder(this)
                .setTitle("毫秒数")
                .setView(et)
                .setPositiveButton("确定", (d, w) -> {
                    int ms = 0;
                    try { ms = Integer.parseInt(et.getText().toString()); } catch (Exception ignore) { }
                    c.set(Calendar.MILLISECOND, Math.max(0, Math.min(999, ms)));
                    p.target(c.getTimeInMillis());
                    refresh();
                })
                .setNegativeButton("取消", null)
                .show();
    }
}

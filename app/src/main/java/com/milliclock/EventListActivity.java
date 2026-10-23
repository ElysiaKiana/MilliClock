package com.milliclock;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 事件列表：预设开售时间，到点前 N 分钟系统级提醒。
 * 点击事件 = 设为悬浮窗倒计时目标；长按 = 删除。
 */
public class EventListActivity extends Activity {

    private static final String[] WEEK = {"日", "一", "二", "三", "四", "五", "六"};

    private List<Event> list = new ArrayList<>();
    private BaseAdapter adapter;
    private final SimpleDateFormat fmtFull = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.event_list);

        ListView lv = findViewById(R.id.lvEvents);
        adapter = new BaseAdapter() {
            @Override public int getCount() { return list.size(); }
            @Override public Object getItem(int position) { return list.get(position); }
            @Override public long getItemId(int position) { return list.get(position).id; }
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                if (convertView == null) {
                    convertView = getLayoutInflater().inflate(R.layout.item_event, parent, false);
                }
                bind(convertView, list.get(position));
                return convertView;
            }
        };
        lv.setAdapter(adapter);
        lv.setEmptyView(findViewById(R.id.tvEmpty));
        lv.setOnItemClickListener((p, v, pos, id) -> setAsTarget(list.get(pos)));
        lv.setOnItemLongClickListener((p, v, pos, id) -> {
            confirmDelete(list.get(pos));
            return true;
        });
        findViewById(R.id.btnAdd).setOnClickListener(v -> addFlow());
    }

    @Override
    protected void onResume() {
        super.onResume();
        EventStore.refreshAndSchedule(this);
        reload();
    }

    private void reload() {
        list = EventStore.load(this);
        adapter.notifyDataSetChanged();
    }

    private void bind(View v, final Event e) {
        TextView tvName = v.findViewById(R.id.tvName);
        TextView tvDesc = v.findViewById(R.id.tvDesc);
        TextView tvRemain = v.findViewById(R.id.tvRemain);
        CheckBox cb = v.findViewById(R.id.cbRemind);

        tvName.setText(e.name);
        tvDesc.setText(desc(e));

        if (e.done) {
            tvRemain.setText("已结束");
            tvRemain.setTextColor(0xFF475569);
        } else {
            long d = Math.max(0, e.nextTargetMs - TimeSync.now());
            tvRemain.setText(d >= 86400000L
                    ? String.format(Locale.US, "%dd %02d:%02d:%02d",
                            d / 86400000L, d / 3600000L % 24, d / 60000L % 60, d / 1000L % 60)
                    : String.format(Locale.US, "%02d:%02d:%02d",
                            d / 3600000L, d / 60000L % 60, d / 1000L % 60));
            tvRemain.setTextColor(0xFF4ADE80);
        }

        cb.setOnCheckedChangeListener(null);
        cb.setChecked(e.remind);
        cb.setEnabled(!e.done);
        cb.setOnCheckedChangeListener((btn, on) -> {
            e.remind = on;
            EventStore.save(this, list);
            EventStore.scheduleAlarm(this, e);
        });
    }

    private String desc(Event e) {
        StringBuilder sb = new StringBuilder();
        if (e.done) sb.append("已结束 · ");
        if (e.mode == 0) {
            sb.append(fmtFull.format(new Date(e.base))).append(" · 单次");
        } else {
            sb.append(String.format(Locale.US, "%02d:%02d", e.hour, e.minute)).append(" · ");
            if (e.mode == 1) {
                sb.append("每天");
            } else {
                StringBuilder w = new StringBuilder();
                for (int i = 0; i < 7; i++) {
                    if ((e.days & (1 << i)) != 0) {
                        if (w.length() > 0) w.append("·");
                        w.append("周").append(WEEK[i]);
                    }
                }
                sb.append(w.length() == 0 ? "每周几" : w.toString());
            }
        }
        if (e.remind && e.remindMin > 0) sb.append(" · 提前").append(e.remindMin).append("分钟提醒");
        return sb.toString();
    }

    private void setAsTarget(Event e) {
        if (e.done || e.nextTargetMs <= TimeSync.now()) {
            Toast.makeText(this, "该事件已结束", Toast.LENGTH_SHORT).show();
            return;
        }
        Prefs p = new Prefs(this);
        p.mode(1);
        p.target(e.nextTargetMs);
        Toast.makeText(this, "已设为倒计时目标：\n" + e.name + " " + fmtFull.format(new Date(e.nextTargetMs)),
                Toast.LENGTH_LONG).show();
    }

    private void confirmDelete(final Event e) {
        new AlertDialog.Builder(this)
                .setTitle("删除事件")
                .setMessage("删除「" + e.name + "」？")
                .setPositiveButton("删除", (d, w) -> {
                    list.remove(e);
                    EventStore.save(this, list);
                    EventStore.cancelAlarm(this, e);
                    reload();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------- 添加事件：名称 → 日期 → 时间 → 提前量 → 重复规则 ----------

    private void addFlow() {
        final EditText et = new EditText(this);
        et.setHint("如：京东 20:00 秒杀");
        new AlertDialog.Builder(this)
                .setTitle("事件名称")
                .setView(et)
                .setPositiveButton("下一步", (d, w) -> {
                    String name = et.getText().toString().trim();
                    pickDate(TextUtils.isEmpty(name) ? "未命名事件" : name);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void pickDate(final String name) {
        final Calendar c = Calendar.getInstance();
        new DatePickerDialog(this, (dp, y, m, day) -> {
            c.set(Calendar.YEAR, y);
            c.set(Calendar.MONTH, m);
            c.set(Calendar.DAY_OF_MONTH, day);
            pickTime(name, c);
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void pickTime(final String name, final Calendar c) {
        new TimePickerDialog(this, (tp, hh, mm) -> {
            c.set(Calendar.HOUR_OF_DAY, hh);
            c.set(Calendar.MINUTE, mm);
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            pickRemind(name, c);
        }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true).show();
    }

    private void pickRemind(final String name, final Calendar c) {
        new AlertDialog.Builder(this)
                .setTitle("提前提醒（事件开始前）")
                .setItems(new String[]{"不提醒", "提前 1 分钟", "提前 3 分钟", "提前 5 分钟", "自定义…"},
                        (d, w) -> {
                            if (w == 0) pickRepeat(name, c, 0);
                            else if (w == 1) pickRepeat(name, c, 1);
                            else if (w == 2) pickRepeat(name, c, 3);
                            else if (w == 3) pickRepeat(name, c, 5);
                            else askCustomRemind(name, c);
                        })
                .show();
    }

    /** 自定义提前提醒分钟数（1 - 1440，即最多提前一天） */
    private void askCustomRemind(final String name, final Calendar c) {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setHint("1 - 1440 分钟");
        new AlertDialog.Builder(this)
                .setTitle("自定义提前提醒")
                .setView(et)
                .setPositiveButton("下一步", (d, w) -> {
                    int min = 0;
                    try { min = Integer.parseInt(et.getText().toString()); } catch (Exception ignore) { }
                    if (min < 1 || min > 1440) {
                        Toast.makeText(this, "请输入 1 - 1440 之间的分钟数", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    pickRepeat(name, c, min);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void pickRepeat(final String name, final Calendar c, final int remindMin) {
        new AlertDialog.Builder(this)
                .setTitle("重复规则")
                .setItems(new String[]{"单次", "每天", "每周几…"}, (d, w) -> {
                    if (w == 0) finishCreate(name, c, remindMin, 0, 0);
                    else if (w == 1) finishCreate(name, c, remindMin, 1, 0);
                    else pickWeekdays(name, c, remindMin);
                })
                .show();
    }

    private void pickWeekdays(final String name, final Calendar c, final int remindMin) {
        final boolean[] chk = new boolean[7];
        new AlertDialog.Builder(this)
                .setTitle("选择重复的星期（可多选）")
                .setMultiChoiceItems(new String[]{"周日", "周一", "周二", "周三", "周四", "周五", "周六"},
                        chk, (d, w, v) -> chk[w] = v)
                .setPositiveButton("确定", (d, w) -> {
                    int mask = 0;
                    for (int i = 0; i < 7; i++) if (chk[i]) mask |= (1 << i);
                    if (mask == 0) {
                        Toast.makeText(this, "未选择星期，已取消", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    finishCreate(name, c, remindMin, 2, mask);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void finishCreate(String name, Calendar c, int remindMin, int mode, int days) {
        Event e = new Event();
        e.id = System.currentTimeMillis();
        e.name = name;
        e.mode = mode;
        e.hour = c.get(Calendar.HOUR_OF_DAY);
        e.minute = c.get(Calendar.MINUTE);
        e.base = c.getTimeInMillis();
        e.days = days;
        e.remind = remindMin > 0;
        e.remindMin = remindMin;
        long next = e.computeNext(TimeSync.now());
        if (next <= 0) {
            Toast.makeText(this, "所选时间已过去，请重新添加", Toast.LENGTH_LONG).show();
            return;
        }
        e.nextTargetMs = next;
        list.add(e);
        EventStore.save(this, list);
        EventStore.scheduleAlarm(this, e);
        reload();
        Toast.makeText(this, "已添加 · " + fmtFull.format(new Date(next)), Toast.LENGTH_LONG).show();
    }
}

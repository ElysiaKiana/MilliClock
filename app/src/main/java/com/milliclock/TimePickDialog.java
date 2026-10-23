package com.milliclock;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TimePicker;

import java.util.Locale;

/**
 * 自定义选时间对话框：默认 时/分 数字输入（数字键盘），可一键切换为时钟转盘。
 * 两种方式来回切换时数值互相带过去，确定时按当前可见方式取值，越界自动收敛（时 0-23，分 0-59）。
 */
public class TimePickDialog {

    public interface Callback { void onTime(int hour, int minute); }

    public static void show(Activity act, int initHour, int initMin, Callback cb) {
        View root = LayoutInflater.from(act).inflate(R.layout.dialog_time_pick, null);
        final EditText etH = root.findViewById(R.id.etHour);
        final EditText etM = root.findViewById(R.id.etMinute);
        final TimePicker tp = root.findViewById(R.id.tpClock);
        final View inputRow = root.findViewById(R.id.inputRow);
        final Button btnSwitch = root.findViewById(R.id.btnSwitch);

        etH.setText(String.format(Locale.CHINA, "%02d", clamp(initHour, 23)));
        etM.setText(String.format(Locale.CHINA, "%02d", clamp(initMin, 59)));
        tp.setIs24HourView(true);
        tp.setHour(clamp(initHour, 23));
        tp.setMinute(clamp(initMin, 59));

        final boolean[] clockMode = {false};
        btnSwitch.setOnClickListener(v -> {
            if (!clockMode[0]) {
                // 数字输入 → 时钟：把输入框的值带到时钟上
                tp.setHour(parse(etH, 23));
                tp.setMinute(parse(etM, 59));
                inputRow.setVisibility(View.GONE);
                tp.setVisibility(View.VISIBLE);
                btnSwitch.setText("改用数字输入");
            } else {
                // 时钟 → 数字输入：把时钟的值带回输入框
                etH.setText(String.format(Locale.CHINA, "%02d", tp.getHour()));
                etM.setText(String.format(Locale.CHINA, "%02d", tp.getMinute()));
                tp.setVisibility(View.GONE);
                inputRow.setVisibility(View.VISIBLE);
                btnSwitch.setText("改用时钟选择");
            }
            clockMode[0] = !clockMode[0];
        });

        new AlertDialog.Builder(act)
                .setTitle("选择时间（24 小时制）")
                .setView(root)
                .setPositiveButton("确定", (d, w) -> {
                    int h, m;
                    if (clockMode[0]) {
                        h = tp.getHour();
                        m = tp.getMinute();
                    } else {
                        h = parse(etH, 23);
                        m = parse(etM, 59);
                    }
                    cb.onTime(h, m);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static int parse(EditText et, int max) {
        try {
            int v = Integer.parseInt(et.getText().toString().trim());
            return clamp(v, max);
        } catch (Exception e) {
            return 0;
        }
    }

    private static int clamp(int v, int max) {
        return Math.max(0, Math.min(max, v));
    }
}

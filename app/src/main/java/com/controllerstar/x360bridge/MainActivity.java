package com.controllerstar.x360bridge;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final int COLOR_PRIMARY = Color.rgb(24, 95, 165);
    private static final int COLOR_DANGER = Color.rgb(163, 45, 45);
    private static final int COLOR_OK = Color.rgb(29, 158, 117);
    private static final int COLOR_WARN = Color.rgb(186, 117, 23);
    private static final int COLOR_IDLE = Color.rgb(170, 178, 188);
    private static final int COLOR_TEXT = Color.rgb(30, 41, 53);
    private static final int COLOR_MUTED = Color.rgb(110, 122, 136);

    private BridgeManager manager;
    private final Handler handler = new Handler();
    private final List<View> deviceRows = new ArrayList<>();
    private boolean refreshing;
    private boolean logExpanded;
    private BridgeManager.Status status = new BridgeManager.Status();

    private View lampShizuku, lampBridge, lampPhysical, lampVirtual;
    private TextView valueShizuku, valueBridge, valuePhysical, valueVirtual;
    private TextView summary, logView, logTitle;
    private Button mainButton;
    private LinearLayout deviceContainer;

    private final Runnable refreshTask = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 3000);
        }
    };

    private static final class StatusRow {
        View lamp;
        TextView value;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        manager = BridgeManager.get(this);
        setContentView(buildUi());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1002);
        }
        manager.connect();
        refreshDevices();
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(refreshTask);
        handler.post(refreshTask);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refreshTask);
        super.onPause();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(244, 246, 249));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text("手柄桥", 24, COLOR_TEXT, true);
        root.addView(title);
        TextView subtitle = text("把任意手柄映射为星铁可识别的 Xbox 360 手柄", 13, COLOR_MUTED, false);
        subtitle.setPadding(0, dp(4), 0, dp(16));
        root.addView(subtitle);

        LinearLayout statusCard = card("运行状态");
        StatusRow shizuku = addStatusRow(statusCard, "Shizuku");
        lampShizuku = shizuku.lamp;
        valueShizuku = shizuku.value;
        StatusRow bridge = addStatusRow(statusCard, "桥接进程");
        lampBridge = bridge.lamp;
        valueBridge = bridge.value;
        StatusRow physical = addStatusRow(statusCard, "物理手柄");
        lampPhysical = physical.lamp;
        valuePhysical = physical.value;
        StatusRow virtual = addStatusRow(statusCard, "虚拟 Xbox 360");
        lampVirtual = virtual.lamp;
        valueVirtual = virtual.value;
        summary = text("尚未启动", 12, COLOR_MUTED, false);
        summary.setPadding(0, dp(8), 0, 0);
        statusCard.addView(summary);
        root.addView(statusCard);

        mainButton = new Button(this);
        mainButton.setText("启动手柄桥");
        mainButton.setTextSize(17);
        mainButton.setTextColor(Color.WHITE);
        mainButton.setAllCaps(false);
        mainButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        paintButton(mainButton, COLOR_PRIMARY);
        mainButton.setOnClickListener(v -> toggle());
        LinearLayout.LayoutParams mainLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(58));
        mainLp.bottomMargin = dp(12);
        root.addView(mainButton, mainLp);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.addView(smallButton("刷新状态", v -> refresh()), weighted());
        actions.addView(smallButton("设备列表", v -> refreshDevices()), weighted());
        actions.addView(smallButton("关闭浮层", v -> closeOverlay()), weighted());
        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionsLp.bottomMargin = dp(12);
        root.addView(actions, actionsLp);

        LinearLayout deviceCard = card("手柄设备");
        deviceContainer = new LinearLayout(this);
        deviceContainer.setOrientation(LinearLayout.VERTICAL);
        deviceCard.addView(deviceContainer);
        deviceCard.addView(smallButton("刷新设备列表", v -> refreshDevices()));
        root.addView(deviceCard);

        LinearLayout settingsCard = card("设置");
        settingsCard.addView(text("映射方案（重启桥后生效）", 13, COLOR_MUTED, false));
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, BridgeManager.PROFILES);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        int selected = 0;
        for (int i = 0; i < BridgeManager.PROFILES.length; ++i) {
            if (BridgeManager.PROFILES[i].equals(manager.getProfile())) selected = i;
        }
        spinner.setSelection(selected);
        spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                manager.setProfile(BridgeManager.PROFILES[position]);
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
        settingsCard.addView(spinner, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        settingsCard.addView(settingSwitch("开机后自动恢复（需 Shizuku 可用）",
                manager.isAutostart(), (button, checked) -> manager.setAutostart(checked)));
        settingsCard.addView(settingSwitch("独占物理手柄（避免双重输入）",
                manager.isGrab(), (button, checked) -> manager.setGrab(checked)));
        settingsCard.addView(settingSwitch("启动时关闭游戏中心浮层",
                manager.isCloseOverlay(), (button, checked) -> manager.setCloseOverlay(checked)));
        root.addView(settingsCard);

        LinearLayout logCard = card(null);
        logTitle = text("▸ 运行日志", 14, COLOR_TEXT, true);
        logTitle.setOnClickListener(v -> {
            logExpanded = !logExpanded;
            logView.setVisibility(logExpanded ? View.VISIBLE : View.GONE);
            logTitle.setText(logExpanded ? "▾ 运行日志" : "▸ 运行日志");
        });
        logCard.addView(logTitle);
        logView = text("", 12, COLOR_TEXT, false);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setPadding(0, dp(8), 0, 0);
        logView.setVisibility(View.GONE);
        logCard.addView(logView);
        root.addView(logCard);

        TextView help = text("首次使用：启动 Shizuku 并授权本应用 → 连接手柄 → 在设备列表选中 → 点「启动手柄桥」→ 进入星铁设置查看「切换控制模式」。桥启动后独立常驻，可退出本应用；仅在设备重启后才可能需要重新启动 Shizuku。",
                12, COLOR_MUTED, false);
        help.setPadding(dp(4), dp(4), dp(4), dp(24));
        root.addView(help);
        return scroll;
    }

    private LinearLayout card(CharSequence title) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        box.setPadding(pad, dp(14), pad, dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(14));
        bg.setStroke(1, Color.rgb(226, 231, 237));
        box.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        box.setLayoutParams(lp);
        if (title != null) {
            TextView heading = text(title.toString(), 15, COLOR_TEXT, true);
            heading.setPadding(0, 0, 0, dp(6));
            box.addView(heading);
        }
        return box;
    }

    private StatusRow addStatusRow(LinearLayout box, String label) {
        StatusRow row = new StatusRow();
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(0, dp(7), 0, dp(7));
        View lamp = new View(this);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(COLOR_IDLE);
        lamp.setBackground(dot);
        line.addView(lamp, new LinearLayout.LayoutParams(dp(10), dp(10)));
        TextView name = text(label, 14, COLOR_TEXT, false);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nameLp.leftMargin = dp(12);
        line.addView(name, nameLp);
        row.value = text("—", 13, COLOR_MUTED, false);
        row.value.setGravity(Gravity.END);
        line.addView(row.value, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.lamp = lamp;
        box.addView(line);
        return row;
    }

    private Switch settingSwitch(String label, boolean checked,
                                 android.widget.CompoundButton.OnCheckedChangeListener listener) {
        Switch sw = new Switch(this);
        sw.setText(label);
        sw.setTextSize(14);
        sw.setTextColor(COLOR_TEXT);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener(listener);
        sw.setPadding(0, dp(10), 0, dp(4));
        return sw;
    }

    private Button smallButton(String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setTextColor(COLOR_PRIMARY);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(10));
        bg.setStroke(1, Color.rgb(210, 220, 232));
        button.setBackground(bg);
        button.setOnClickListener(listener);
        return button;
    }

    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        lp.rightMargin = dp(6);
        return lp;
    }

    private void paintButton(Button button, int color) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(12));
        button.setBackground(bg);
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private void setLamp(View lamp, int color) {
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(color);
        lamp.setBackground(dot);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void toggle() {
        if (!manager.isShizukuReady()) {
            toast("请先启动 Shizuku 并授权本应用");
            manager.connect();
            refresh();
            return;
        }
        String command;
        try {
            if (status.running()) {
                command = manager.buildStopCommand();
            } else {
                command = manager.buildStartCommand();
                if (command != null && manager.isCloseOverlay()) {
                    command += "; " + manager.buildOverlayCommand();
                }
            }
        } catch (IOException e) {
            toast("准备二进制失败：" + e.getMessage());
            return;
        }
        if (command == null) {
            toast("找不到当前 ABI 的手柄桥程序");
            return;
        }
        appendLog("$ " + command);
        manager.execAsync(command, result -> {
            appendLog(result);
            refresh();
        });
    }

    private void closeOverlay() {
        manager.execAsync(manager.buildOverlayCommand(), this::appendLog);
    }

    private void refresh() {
        if (refreshing) return;
        refreshing = true;
        manager.execAsync(manager.buildStatusCommand(), result -> {
            refreshing = false;
            if (result != null && result.startsWith("ERROR:")) {
                appendLog(result);
            }
            status = manager.parseStatus(result);
            updateStatusUi();
        });
    }

    private void updateStatusUi() {
        boolean binder = manager.isShizukuRunning();
        boolean ready = manager.isShizukuReady() && manager.isServiceConnected();
        setLamp(lampShizuku, ready ? COLOR_OK : (binder ? COLOR_WARN : COLOR_IDLE));
        valueShizuku.setText(ready ? "已连接" : (binder ? "待授权" : "未运行"));

        boolean running = status.running();
        setLamp(lampBridge, running ? COLOR_OK : COLOR_IDLE);
        valueBridge.setText(running ? status.state : "未运行");
        setLamp(lampVirtual, running ? COLOR_OK : COLOR_IDLE);
        valueVirtual.setText(running ? "045e:028e" : "未创建");

        boolean hasPhysical = status.physical != null && !status.physical.isEmpty();
        setLamp(lampPhysical, hasPhysical ? COLOR_OK : (running ? COLOR_WARN : COLOR_IDLE));
        valuePhysical.setText(hasPhysical ? status.physical : (running ? "等待连接" : "—"));

        summary.setText(running
                ? "已转发 " + status.events + " 个事件 · 运行 " + status.uptime + " 秒 · 方案 " + status.profile
                : "启动后桥会独立常驻，直到设备重启前无需再操作");

        mainButton.setText(running ? "停止手柄桥" : "启动手柄桥");
        paintButton(mainButton, running ? COLOR_DANGER : COLOR_PRIMARY);
        if (status.log != null && !status.log.isEmpty()) logView.setText(status.log);
    }

    private void appendLog(String message) {
        if (message == null || message.isEmpty()) return;
        String merged = logView.getText().toString() + "\n" + message;
        if (merged.length() > 12000) merged = merged.substring(merged.length() - 12000);
        logView.setText(merged);
    }

    private void refreshDevices() {
        String command;
        try {
            command = manager.buildListCommand();
        } catch (IOException e) {
            appendLog("准备二进制失败：" + e);
            return;
        }
        if (command == null) {
            appendLog("找不到当前 ABI 的手柄桥程序");
            return;
        }
        manager.execAsync(command, this::renderDevices);
    }

    private void renderDevices(String result) {
        deviceListCache = result == null ? "" : result;
        deviceContainer.removeAllViews();
        deviceRows.clear();
        if (result != null && result.startsWith("ERROR:")) {
            deviceContainer.addView(text(result, 12, COLOR_DANGER, false));
            return;
        }
        addDeviceRow("auto", "自动选择第一个手柄", "");
        if (result != null) {
            String[] lines = result.split("\\r?\\n");
            for (String line : lines) {
                if (line.isEmpty() || line.startsWith("#") || !line.contains("\t")) continue;
                String[] fields = line.split("\\t");
                if (fields.length < 3) continue;
                addDeviceRow(fields[0], fields[1], fields[2]);
            }
        }
        if (deviceRows.size() <= 1) {
            deviceContainer.addView(text("未发现手柄：请确认已连接且处于 PC/Xbox 模式，然后点「刷新设备列表」",
                    12, COLOR_MUTED, false));
        }
    }

    private void addDeviceRow(String path, String name, String vidpid) {
        boolean selected = path.equals(manager.getDevice());
        String label = (selected ? "✓ " : "   ") + name
                + (vidpid.isEmpty() ? "" : "  ·  " + vidpid);
        TextView row = text(label, 13, selected ? COLOR_PRIMARY : COLOR_TEXT, selected);
        row.setPadding(dp(8), dp(10), dp(8), dp(10));
        if (selected) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.rgb(230, 241, 251));
            bg.setCornerRadius(dp(8));
            row.setBackground(bg);
        }
        row.setOnClickListener(v -> {
            manager.setDevice(path);
            toast(selectedDeviceName(path, name) + " 已选为物理手柄，下次启动桥生效");
            renderDevicesFromCache();
        });
        deviceContainer.addView(row);
        deviceRows.add(row);
    }

    private String selectedDeviceName(String path, String name) {
        return "auto".equals(path) ? "自动选择" : name;
    }

    private String deviceListCache = "";

    private void renderDevicesFromCache() {
        renderDevices(deviceListCache);
    }
}

package com.controllerstar.x360bridge;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
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
    private boolean shizukuPromptShown;
    private long lastDeviceListAttempt = 0L;
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
        manager.connect();
        refresh();
        handler.post(this::showShizukuPromptIfNeeded);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(refreshTask);
        handler.post(refreshTask);
        if (manager.isShizukuReady() && deviceRows.size() <= 1) refreshDevices();
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
        TextView subtitle = text("按顺序完成三步，让星铁识别你的手柄", 13, COLOR_MUTED, false);
        subtitle.setPadding(0, dp(4), 0, dp(4));
        root.addView(subtitle);
        TextView flow = text("① 连接 Shizuku  →  ② 检测手柄  →  ③ 启动桥", 14,
                COLOR_PRIMARY, true);
        flow.setPadding(0, dp(4), 0, dp(16));
        root.addView(flow);

        LinearLayout statusCard = card("使用进度");
        StatusRow shizuku = addStatusRow(statusCard, "第 1 步 · Shizuku");
        lampShizuku = shizuku.lamp;
        valueShizuku = shizuku.value;
        StatusRow physical = addStatusRow(statusCard, "第 2 步 · 物理手柄");
        lampPhysical = physical.lamp;
        valuePhysical = physical.value;
        StatusRow bridge = addStatusRow(statusCard, "第 3 步 · 手柄桥");
        lampBridge = bridge.lamp;
        valueBridge = bridge.value;
        StatusRow virtual = addStatusRow(statusCard, "虚拟手柄");
        lampVirtual = virtual.lamp;
        valueVirtual = virtual.value;
        summary = text("尚未启动", 12, COLOR_MUTED, false);
        summary.setPadding(0, dp(8), 0, 0);
        statusCard.addView(summary);
        TextView shizukuHelp = text("Shizuku 未就绪？点这里检查、安装、启动或授权", 12,
                COLOR_PRIMARY, false);
        shizukuHelp.setPadding(0, dp(10), 0, 0);
        shizukuHelp.setOnClickListener(v -> showShizukuPrompt());
        statusCard.addView(shizukuHelp);
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
        actions.addView(smallButton("刷新状态", v -> {
            toast("正在刷新状态…");
            refresh(true);
        }), weighted());
        actions.addView(smallButton("设备列表", v -> {
            toast("正在扫描手柄…");
            refreshDevices(true);
        }), weighted());
        actions.addView(smallButton("关闭浮层", v -> closeOverlay()), weighted());
        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionsLp.bottomMargin = dp(12);
        root.addView(actions, actionsLp);

        LinearLayout deviceCard = card("第 2 步 · 连接并选择手柄");
        deviceContainer = new LinearLayout(this);
        deviceContainer.setOrientation(LinearLayout.VERTICAL);
        deviceContainer.addView(text("完成 Shizuku 连接后会自动检测；也可先连接手柄。",
                12, COLOR_MUTED, false));
        deviceCard.addView(deviceContainer);
        deviceCard.addView(smallButton("重新检测手柄", v -> {
            toast("正在扫描手柄…");
            refreshDevices(true);
        }));
        root.addView(deviceCard);

        LinearLayout settingsCard = card("高级设置（通常保持默认即可）");
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

        settingsCard.addView(text("虚拟设备身份（重启桥后生效；星铁用默认，原神 / 鸣潮可试蓝牙型号）",
                13, COLOR_MUTED, false));
        Spinner identitySpinner = new Spinner(this);
        ArrayAdapter<String> identityAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, BridgeManager.IDENTITY_LABELS);
        identityAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        identitySpinner.setAdapter(identityAdapter);
        int identitySelected = 0;
        for (int i = 0; i < BridgeManager.IDENTITIES.length; ++i) {
            if (BridgeManager.IDENTITIES[i].equals(manager.getIdentity())) identitySelected = i;
        }
        identitySpinner.setSelection(identitySelected);
        identitySpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                manager.setIdentity(BridgeManager.IDENTITIES[position]);
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
        settingsCard.addView(identitySpinner, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        settingsCard.addView(text("扳机处理（LT / RT 错位或跳变时调整）", 13, COLOR_MUTED, false));
        Spinner triggerSpinner = new Spinner(this);
        ArrayAdapter<String> triggerAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, BridgeManager.TRIGGER_MODE_LABELS);
        triggerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        triggerSpinner.setAdapter(triggerAdapter);
        int triggerSelected = 0;
        for (int i = 0; i < BridgeManager.TRIGGER_MODES.length; ++i) {
            if (BridgeManager.TRIGGER_MODES[i].equals(manager.getTriggerMode())) triggerSelected = i;
        }
        triggerSpinner.setSelection(triggerSelected);
        triggerSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                manager.setTriggerMode(BridgeManager.TRIGGER_MODES[position]);
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
        settingsCard.addView(triggerSpinner, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        settingsCard.addView(settingSwitch("交换 LT / RT（左右按反时开启）",
                manager.isTriggerSwap(), (button, checked) -> manager.setTriggerSwap(checked)));
        settingsCard.addView(settingSwitch("反转扳机极性（松开却满行程时开启）",
                manager.isTriggerInvert(), (button, checked) -> manager.setTriggerInvert(checked)));
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

        TextView help = text("首次使用：启动 Shizuku 并授权本应用 → 连接手柄 → 在设备列表选中 → 点「启动手柄桥」→ 完全退出游戏后重进。默认虚拟身份适用于星穹铁道；若 LT / RT 按反，先开启「交换 LT / RT」，仍不对再试「反转扳机极性」。切换身份或扳机设置后需重启桥才生效，运行日志里会打印 bind LT / bind RT 对应的物理轴，便于排查。桥启动后独立常驻，可退出本应用；仅在设备重启后才可能需要重新启动 Shizuku。",
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

    private Toast currentToast;

    private void toast(String message) {
        /* Cancel the previous toast and create a fresh one: a reused instance
         * keeps stale text on some OEM ROMs, while plain makeText() queues up
         * toasts so rapid taps show the previous action's message. */
        if (currentToast != null) currentToast.cancel();
        currentToast = Toast.makeText(this, message, Toast.LENGTH_SHORT);
        currentToast.show();
    }

    private void showShizukuPromptIfNeeded() {
        if (shizukuPromptShown || manager.isShizukuReady()) return;
        shizukuPromptShown = true;
        showShizukuPrompt();
    }

    private void showShizukuPrompt() {
        if (isFinishing()) return;
        if (manager.isShizukuReady()) {
            manager.connect();
            refresh();
            toast("Shizuku 已启动并授权");
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        if (!manager.isShizukuInstalled()) {
            builder.setTitle("需要安装 Shizuku")
                    .setMessage("手柄桥必须借助 Shizuku 获得创建虚拟手柄所需的 shell 权限。"
                            + "本应用自身不能替代它，也不会收集数据。\n\n"
                            + "请从官方 Releases 下载安装，安装后返回本页重新检查。")
                    .setPositiveButton("前往官方下载", (dialog, which) -> openShizukuDownload());
        } else if (!manager.isShizukuRunning()) {
            builder.setTitle("需要启动 Shizuku")
                    .setMessage("已检测到 Shizuku，但它的服务尚未运行。请打开 Shizuku，"
                            + "按其界面指引完成「启动」。未 root 设备通常需要无线调试；"
                            + "启动成功后返回本应用。\n\n仅安装 Shizuku 而不启动服务，手柄桥仍无法工作。")
                    .setPositiveButton("打开 Shizuku", (dialog, which) -> launchShizuku());
        } else {
            builder.setTitle("需要授权 Shizuku")
                    .setMessage("Shizuku 已运行，但手柄桥尚未获得授权。请在接下来的系统弹窗中允许，"
                            + "授权一次后会长期记住。")
                    .setPositiveButton("申请授权", (dialog, which) -> {
                        manager.connect();
                        toast("请在 Shizuku 弹窗中允许授权");
                    });
        }
        builder.setNegativeButton("稍后", null)
                .setNeutralButton("重新检查", (dialog, which) -> handler.postDelayed(() -> {
                    refresh();
                    showShizukuPrompt();
                }, 500))
                .show();
    }

    private void launchShizuku() {
        Intent intent = getPackageManager().getLaunchIntentForPackage(BridgeManager.SHIZUKU_PACKAGE);
        if (intent == null) {
            toast("无法打开 Shizuku，请手动从桌面启动");
            return;
        }
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            toast("无法打开 Shizuku，请手动从桌面启动");
        }
    }

    private void openShizukuDownload() {
        Intent intent = new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://github.com/RikkaApps/Shizuku/releases"));
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            toast("未找到浏览器，请访问 github.com/RikkaApps/Shizuku/releases");
        }
    }

    private void showControllerHelp() {
        new AlertDialog.Builder(this)
                .setTitle("第 2 步：先连接手柄")
                .setMessage("请先让手柄进入 Android 原生 HID 模式，再点「重新检测手柄」。\n\n"
                        + "• 蓝牙手柄：在系统设置中完成配对。\n"
                        + "• Type-C / USB 手柄：插好接口，部分设备需开启 OTG。\n"
                        + "• 盖世小鸡 X5 / X5 Lite：使用深蓝灯的原生直连模式，"
                        + "不要使用触屏映射模式；休眠后按 A/B/X/Y/Home 唤醒。\n\n"
                        + "检测到后点选手柄，映射方案保持 auto，再启动手柄桥。")
                .setPositiveButton("重新检测", (dialog, which) -> refreshDevices(true))
                .setNegativeButton("取消", null)
                .show();
    }

    private void toggle() {
        if (!manager.isShizukuReady()) {
            showShizukuPrompt();
            refresh();
            return;
        }
        if (!status.running() && deviceRows.size() <= 1) {
            refreshDevices(true);
            showControllerHelp();
            return;
        }
        if (!status.running() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1002);
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
        toast("正在关闭游戏中心浮层…");
        manager.execAsync(manager.buildOverlayCommand(), result -> {
            appendLog(result);
            String first = result == null ? "" : result.trim().split("\\r?\\n")[0];
            toast(first.isEmpty() ? "已发送关闭浮层命令" : first);
        });
    }

    private void refresh() {
        refresh(false);
    }

    /** A manual tap must never be swallowed by the 3s auto-refresh in flight. */
    private void refresh(boolean manual) {
        if (!manual && refreshing) return;
        refreshing = true;
        manager.execAsync(manager.buildStatusCommand(), result -> {
            refreshing = false;
            if (result != null && result.startsWith("ERROR:")) {
                appendLog(result);
                if (manual) toast("刷新失败：" + result);
            }
            status = manager.parseStatus(result);
            updateStatusUi();
            if (manual) toast("状态已刷新");
        });
    }

    private void updateStatusUi() {
        boolean binder = manager.isShizukuRunning();
        boolean authorized = manager.isShizukuReady();
        boolean ready = authorized && manager.isServiceConnected();
        setLamp(lampShizuku, ready ? COLOR_OK : (binder ? COLOR_WARN : COLOR_IDLE));
        valueShizuku.setText(ready ? "已连接" : binder
                ? (authorized ? "连接中" : "待授权")
                : (manager.isShizukuInstalled() ? "已安装未启动" : "未安装"));

        boolean running = status.running();
        int detected = Math.max(0, deviceRows.size() - 1);
        boolean bridgePhysical = running && status.physical != null && !status.physical.isEmpty();
        boolean hasPhysical = bridgePhysical || detected > 0;
        setLamp(lampPhysical, hasPhysical ? COLOR_OK : (authorized ? COLOR_WARN : COLOR_IDLE));
        valuePhysical.setText(bridgePhysical ? status.physical : detected > 0
                ? "已检测到 " + detected + " 个" : (authorized ? "未检测到" : "等待第 1 步"));

        setLamp(lampBridge, running ? COLOR_OK : COLOR_IDLE);
        valueBridge.setText(running ? (bridgePhysical ? "运行中" : "等待手柄") : "未启动");
        setLamp(lampVirtual, running ? COLOR_OK : COLOR_IDLE);
        valueVirtual.setText(running ? status.virtualId : "未创建");

        if (!running && ("running".equals(status.state) || "waiting".equals(status.state)
                || "starting".equals(status.state))) {
            summary.setText("桥进程已退出，请重新启动");
        } else if (running) {
            String manualHint = "auto".equals(manager.getProfile())
                    ? "" : " · 当前为手动映射，异常时请改回 auto";
            summary.setText((bridgePhysical ? "桥已就绪：完全退出星铁后重新进入即可。"
                    : "桥已启动，正在等待手柄连接。")
                    + "\n已转发 " + status.events + " 个事件 · 运行 " + status.uptime
                    + " 秒 · 方案 " + status.profile + manualHint);
        } else if (!authorized) {
            summary.setText("先完成第 1 步：安装、启动并授权 Shizuku");
        } else if (detected == 0) {
            summary.setText("Shizuku 已就绪。下一步连接手柄，然后重新检测");
        } else {
            summary.setText("手柄已就绪。保持映射为 auto，点击「③ 启动手柄桥」");
        }

        mainButton.setText(running ? "停止手柄桥" : !authorized
                ? "① 检查并连接 Shizuku" : detected == 0 ? "② 检测手柄" : "③ 启动手柄桥");
        paintButton(mainButton, running ? COLOR_DANGER : COLOR_PRIMARY);
        if (status.log != null && !status.log.isEmpty()) logView.setText(status.log);

        if (authorized && System.currentTimeMillis() - lastDeviceListAttempt > 10000L) {
            lastDeviceListAttempt = System.currentTimeMillis();
            handler.post(() -> refreshDevices(false));
        }
    }

    private void appendLog(String message) {
        if (message == null || message.isEmpty()) return;
        String merged = logView.getText().toString() + "\n" + message;
        if (merged.length() > 12000) merged = merged.substring(merged.length() - 12000);
        logView.setText(merged);
    }

    private void refreshDevices() {
        refreshDevices(false);
    }

    private void refreshDevices(boolean showToast) {
        lastDeviceListAttempt = System.currentTimeMillis();
        if (!manager.isShizukuReady()) {
            if (showToast) showShizukuPrompt();
            updateStatusUi();
            return;
        }
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
        manager.execAsync(command, result -> renderDevices(result, showToast));
    }

    private void renderDevices(String result, boolean showToast) {
        deviceListCache = result == null ? "" : result;
        deviceContainer.removeAllViews();
        deviceRows.clear();
        if (result != null && result.startsWith("ERROR:")) {
            deviceContainer.addView(text(result, 12, COLOR_DANGER, false));
            if (showToast) toast("扫描失败：" + result);
            return;
        }
        addDeviceRow(null);
        if (result != null) {
            String[] lines = result.split("\\r?\\n");
            for (String line : lines) {
                if (line.isEmpty() || line.startsWith("#") || !line.contains("\t")) continue;
                String[] fields = line.split("\\t");
                if (fields.length >= 3) addDeviceRow(fields);
            }
        }
        if (deviceRows.size() <= 1) {
            deviceContainer.addView(text("未发现手柄：请确认已连接并处于标准 HID 手柄模式（有线手柄需开启 OTG），然后点「刷新设备列表」",
                    12, COLOR_MUTED, false));
        }
        if (showToast) {
            int found = deviceRows.size() - 1;
            toast(found > 0 ? "发现 " + found + " 个手柄，点选后启动桥" : "未发现手柄，请确认连接与模式");
        }
        updateStatusUi();
    }

    private void addDeviceRow(String[] fields) {
        boolean automatic = fields == null;
        String path = automatic ? "auto" : fields[0];
        String name = automatic ? "自动选择第一个手柄" : fields[1];
        String vidpid = automatic ? "" : fields[2];
        String selection = automatic || !vidpid.matches("[0-9a-fA-F]{4}:[0-9a-fA-F]{4}")
                || "0000:0000".equals(vidpid) ? path : vidpid;
        String saved = manager.getDevice();
        boolean selected = selection.equals(saved) || path.equals(saved);
        if (selected && !automatic && path.equals(saved)) manager.setDevice(selection);

        String label = (selected ? "✓ " : "   ") + name;
        if (!automatic) {
            String bus = deviceField(fields, "bus=");
            String connection = "5".equals(bus) ? "蓝牙" : "3".equals(bus) ? "USB 有线" : "总线 " + bus;
            label += "\n" + connection + " · " + vidpid + " · " + path;
            label += "\n建议 " + deviceField(fields, "profile=") + " · 扳机 "
                    + deviceField(fields, "triggers=");
        }
        TextView row = text(label, 13, selected ? COLOR_PRIMARY : COLOR_TEXT, selected);
        row.setPadding(dp(8), dp(10), dp(8), dp(10));
        if (selected) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.rgb(230, 241, 251));
            bg.setCornerRadius(dp(8));
            row.setBackground(bg);
        }
        row.setOnClickListener(v -> {
            manager.setDevice(selection);
            toast((automatic ? "自动选择" : name) + " 已选为物理手柄，下次启动桥生效");
            renderDevicesFromCache();
        });
        deviceContainer.addView(row);
        deviceRows.add(row);
    }

    private String deviceField(String[] fields, String prefix) {
        for (String field : fields) {
            if (field.startsWith(prefix)) return field.substring(prefix.length());
        }
        return "-";
    }

    private String deviceListCache = "";

    private void renderDevicesFromCache() {
        renderDevices(deviceListCache, false);
    }
}

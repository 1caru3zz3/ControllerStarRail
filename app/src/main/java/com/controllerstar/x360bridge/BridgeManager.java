package com.controllerstar.x360bridge;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.IBinder;
import android.os.RemoteException;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

/**
 * Owns the Shizuku connection and builds every shell command the app needs.
 * The native bridge itself runs as a detached process, so this class is only
 * needed while a command is being issued.
 */
public final class BridgeManager {
    public static final String BINARY = "/data/local/tmp/x360bridge";
    public static final String PREFIX = BINARY;
    public static final String STATUS_FILE = PREFIX + ".status";
    public static final String LOG_FILE = PREFIX + ".log";
    public static final String MARKER = "---LOG---";

    private static final String PREFS = "bridge_settings";
    private static final String KEY_PROFILE = "profile";
    private static final String KEY_DEVICE = "device";
    private static final String KEY_GRAB = "grab";
    private static final String KEY_AUTOSTART = "autostart";
    private static final String KEY_CLOSE_OVERLAY = "close_overlay";

    public static final String[] PROFILES = {"auto", "redmi", "xbox", "ps", "android", "switch", "generic"};

    public static final class Status {
        public String state = "unknown";
        public String profile = "-";
        public String physical = "";
        public String physicalPath = "";
        public long events = 0;
        public long uptime = 0;
        public String log = "";
        public boolean running() {
            return "running".equals(state) || "waiting".equals(state) || "starting".equals(state);
        }
    }

    public interface Callback {
        void onResult(String result);
    }

    private static BridgeManager instance;

    private final Context app;
    private final SharedPreferences prefs;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private IX360Service service;
    private Shizuku.UserServiceArgs serviceArgs;
    private boolean binding;
    private boolean permissionListenerAdded;

    private final Shizuku.OnBinderReceivedListener binderListener = this::connect;
    private final Shizuku.OnBinderDeadListener binderDeadListener = () -> service = null;
    private final Shizuku.OnRequestPermissionResultListener permissionListener =
            (requestCode, grantResult) -> {
                if (grantResult == PackageManager.PERMISSION_GRANTED) connect();
            };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = IX360Service.Stub.asInterface(binder);
            binding = false;
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            binding = false;
        }
    };

    private BridgeManager(Context context) {
        app = context.getApplicationContext();
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!permissionListenerAdded) {
            Shizuku.addRequestPermissionResultListener(permissionListener);
            Shizuku.addBinderReceivedListenerSticky(binderListener);
            Shizuku.addBinderDeadListener(binderDeadListener);
            permissionListenerAdded = true;
        }
        connect();
    }

    public static synchronized BridgeManager get(Context context) {
        if (instance == null) instance = new BridgeManager(context);
        return instance;
    }

    public boolean isShizukuReady() {
        return Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
    }

    public boolean isShizukuRunning() {
        return Shizuku.pingBinder();
    }

    public void connect() {
        if (!Shizuku.pingBinder()) { service = null; return; }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(1001);
            return;
        }
        if (service != null || binding) return;
        if (serviceArgs == null) {
            serviceArgs = new Shizuku.UserServiceArgs(
                    new ComponentName(app, X360Service.class))
                    .daemon(false)
                    .processNameSuffix("x360")
                    .debuggable(false)
                    .version(1)
                    .tag("x360bridge");
        }
        binding = true;
        Shizuku.bindUserService(serviceArgs, connection);
    }

    public boolean isServiceConnected() {
        return service != null;
    }

    public void execAsync(final String command, final Callback callback) {
        executor.execute(() -> {
            String result = execSync(command);
            if (callback != null) {
                android.os.Handler handler = new android.os.Handler(app.getMainLooper());
                handler.post(() -> callback.onResult(result));
            }
        });
    }

    public String execSync(String command) {
        IX360Service current = service;
        if (current == null) {
            connect();
            return "ERROR: Shizuku 服务未就绪，请确认 Shizuku 已启动并已授权本应用";
        }
        try {
            return current.exec(command);
        } catch (RemoteException e) {
            service = null;
            return "ERROR: " + e;
        }
    }

    /** Copies the ABI-matched binary out of assets so the shell can reach it. */
    public String binaryAssetName() {
        String[] abis = Build.SUPPORTED_ABIS;
        for (String abi : abis) {
            String candidate = "bin/" + abi + "/uinput_x360";
            if (assetExists(candidate)) return candidate;
        }
        return assetExists("uinput_x360") ? "uinput_x360" : null;
    }

    private boolean assetExists(String name) {
        try (InputStream in = app.getAssets().open(name)) {
            return in.available() >= 0;
        } catch (IOException e) {
            return false;
        }
    }

    private String extractBinary() throws IOException {
        String asset = binaryAssetName();
        if (asset == null) return null;
        File dir = app.getExternalFilesDir(null);
        if (dir == null) return null;
        File target = new File(dir, "uinput_x360");
        try (InputStream in = app.getAssets().open(asset);
             FileOutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[16384];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
        }
        return target.getAbsolutePath();
    }

    public String installCommand() throws IOException {
        String source = extractBinary();
        if (source == null) return null;
        return "cp '" + source + "' " + BINARY + " && chmod 755 " + BINARY;
    }

    public String buildStartCommand() throws IOException {
        String install = installCommand();
        if (install == null) return null;
        StringBuilder command = new StringBuilder(install);
        command.append(" && ").append(BINARY)
                .append(" --detach --prefix ").append(PREFIX)
                .append(" --profile ").append(getProfile());
        String device = getDevice();
        if (device != null && device.length() > 0 && !"auto".equals(device)) {
            command.append(" --device ").append(device);
        }
        if (!isGrab()) command.append(" --no-grab");
        return command.toString();
    }

    public String buildStopCommand() {
        return "if [ -x " + BINARY + " ]; then " + BINARY + " --stop --prefix " + PREFIX
                + "; else echo 'bridge binary missing, nothing to stop'; fi";
    }

    public String buildListCommand() throws IOException {
        String install = installCommand();
        if (install == null) return null;
        return install + " && " + BINARY + " --list";
    }

    public String buildStatusCommand() {
        return "cat " + STATUS_FILE + " 2>/dev/null; echo " + MARKER
                + "; tail -n 40 " + LOG_FILE + " 2>/dev/null";
    }

    public String buildOverlayCommand() {
        return "am force-stop com.xiaomi.gamecenter; echo '已关闭游戏中心浮层'";
    }

    public Status parseStatus(String raw) {
        Status status = new Status();
        if (raw == null) return status;
        int marker = raw.indexOf(MARKER);
        String json = marker >= 0 ? raw.substring(0, marker) : raw;
        status.log = marker >= 0 ? raw.substring(marker + MARKER.length()).trim() : "";
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start < 0 || end <= start) return status;
        try {
            JSONObject object = new JSONObject(json.substring(start, end + 1));
            status.state = object.optString("state", "unknown");
            status.profile = object.optString("profile", "-");
            status.physical = object.optString("physical", "");
            status.physicalPath = object.optString("physical_path", "");
            status.events = object.optLong("events", 0);
            status.uptime = object.optLong("uptime", 0);
        } catch (Exception ignored) {
            status.state = "unknown";
        }
        return status;
    }

    public String getProfile() { return prefs.getString(KEY_PROFILE, "auto"); }
    public void setProfile(String value) { prefs.edit().putString(KEY_PROFILE, value).apply(); }
    public String getDevice() { return prefs.getString(KEY_DEVICE, "auto"); }
    public void setDevice(String value) { prefs.edit().putString(KEY_DEVICE, value).apply(); }
    public boolean isGrab() { return prefs.getBoolean(KEY_GRAB, true); }
    public void setGrab(boolean value) { prefs.edit().putBoolean(KEY_GRAB, value).apply(); }
    public boolean isAutostart() { return prefs.getBoolean(KEY_AUTOSTART, true); }
    public void setAutostart(boolean value) { prefs.edit().putBoolean(KEY_AUTOSTART, value).apply(); }
    public boolean isCloseOverlay() { return prefs.getBoolean(KEY_CLOSE_OVERLAY, false); }
    public void setCloseOverlay(boolean value) { prefs.edit().putBoolean(KEY_CLOSE_OVERLAY, value).apply(); }
}

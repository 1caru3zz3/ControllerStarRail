/*
 * uinput_x360 - generic gamepad -> virtual console gamepad bridge
 *
 * Reads a physical /dev/input/event* gamepad and re-emits its events through
 * /dev/uinput. The virtual identity is selectable: by default it is the
 * "Microsoft X-Box 360 pad" (045e:028e) that Unity/InControl based games such
 * as Honkai: Star Rail look for, but the same bridge can also present itself
 * as an Xbox One / Xbox Wireless / DualShock 4 / DualSense controller, which
 * is what controller-whitelisted titles such as Genshin Impact and Wuthering
 * Waves check for.
 *
 * v0.3.0 changes:
 *   - Identity presets instead of hardcoded VID/PID/name/bus.
 *   - Axis bindings are inferred from the physical device's actual EV_ABS /
 *     EV_KEY capabilities at connect time, so one virtual axis can only ever
 *     be fed by one physical source. This removes the classic LT/RT mix-up
 *     where Z/RZ were simultaneously interpreted as right stick and triggers.
 *   - Trigger normalisation: rest-value detection, adaptive range, automatic
 *     digital (two-state) trigger detection, polarity/left-right switches and
 *     button-only trigger fallback (BTN_TL2/BTN_TR2 -> ABS_Z/ABS_RZ).
 *   - Button-style D-pads are converted to ABS_HAT0X/Y for every profile.
 *   - UI_SET_ABSBIT is issued before UI_ABS_SETUP (matches AOSP/SonicDX12).
 *
 * Axis semantics follow the Linux gamepad spec and AOSP
 *   data/keyboards/Vendor_045e_Product_028e.kl:
 *   ABS_X/Y = left stick, ABS_RX/RY = right stick,
 *   ABS_Z = LTRIGGER, ABS_RZ = RTRIGGER,
 *   ABS_BRAKE = left trigger, ABS_GAS = right trigger (raw device side).
 *
 * Build (NDK r26d):
 *   clang --target=aarch64-linux-android34 -O2 -static -o uinput_x360 uinput_x360.c
 *
 * This file is part of ControllerStarRail and is licensed under the MIT license.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/file.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <sys/types.h>
#include <time.h>
#include <unistd.h>

#include <linux/input.h>
#include <linux/uinput.h>

#define DEFAULT_PREFIX "/data/local/tmp/x360bridge"
#define MAX_EVENT_DEVICES 128
#define BIT_WORDS(bits) ((bits + (sizeof(unsigned long) * 8) - 1) / (sizeof(unsigned long) * 8))
#define KEY_BITS BIT_WORDS(KEY_MAX)
#define ABS_BITS BIT_WORDS(ABS_MAX)
#define PROP_BITS 1

#define STICK_MIN 0
#define STICK_MAX 65535
#define STICK_FUZZ 255
#define STICK_FLAT 4095
#define TRIGGER_MIN 0
#define TRIGGER_MAX 1023
#define TRIGGER_FUZZ 3
#define TRIGGER_FLAT 63
#define HAT_MIN (-1)
#define HAT_MAX 1

/* Fallback range when a device does not expose EVIOCGABS for an axis. */
#define FALLBACK_ABS_MIN 0
#define FALLBACK_ABS_MAX 255

static const int virtual_keys[] = {
    BTN_SOUTH, BTN_EAST, BTN_NORTH, BTN_WEST,
    BTN_TL, BTN_TR, BTN_TL2, BTN_TR2,
    BTN_SELECT, BTN_START, BTN_MODE, BTN_THUMBL, BTN_THUMBR
};

static const int virtual_axes[] = {
    ABS_X, ABS_Y, ABS_RX, ABS_RY, ABS_Z, ABS_RZ, ABS_HAT0X, ABS_HAT0Y
};

/* ------------------------------------------------------------------------- */
/* Identity presets                                                          */
/* VID/PID/name values mirror SonicDX12/SteamController-Android's PROFILES    */
/* table; the Bluetooth variants are the models listed in HoYoverse's 5.5     */
/* Android controller announcement and Kuro Games' Wuthering Waves notices.   */
/* ------------------------------------------------------------------------- */
typedef struct {
    const char *key;
    const char *name;
    unsigned short bus;
    unsigned short vendor;
    unsigned short product;
    unsigned short version;
} identity_t;

static const identity_t IDENTITIES[] = {
    { "xbox360",   "Microsoft X-Box 360 pad",                                        BUS_USB,        0x045e, 0x028e, 0x0110 },
    { "xboxone",   "Microsoft Xbox One Controller",                                 BUS_USB,        0x045e, 0x02ea, 0x0114 },
    { "xboxbt",    "Xbox Wireless Controller",                                      BUS_BLUETOOTH, 0x045e, 0x0b13, 0x0114 },
    { "ds4",       "Sony Interactive Entertainment Wireless Controller",            BUS_BLUETOOTH, 0x054c, 0x05c4, 0x0100 },
    { "dualsense", "Sony Interactive Entertainment DualSense Wireless Controller",  BUS_BLUETOOTH, 0x054c, 0x0ce6, 0x0100 },
};

static const identity_t *find_identity(const char *key) {
    size_t i;
    for (i = 0; i < sizeof(IDENTITIES) / sizeof(IDENTITIES[0]); ++i) {
        if (strcmp(IDENTITIES[i].key, key) == 0) return &IDENTITIES[i];
    }
    return NULL;
}

/* ------------------------------------------------------------------------- */
/* Axis roles and runtime bindings                                           */
/* ------------------------------------------------------------------------- */
typedef enum {
    ROLE_LX, ROLE_LY, ROLE_RX, ROLE_RY,
    ROLE_LT, ROLE_RT, ROLE_HX, ROLE_HY,
    ROLE_COUNT
} axis_role_t;

static const int role_to_virtual[ROLE_COUNT] = {
    ABS_X, ABS_Y, ABS_RX, ABS_RY, ABS_Z, ABS_RZ, ABS_HAT0X, ABS_HAT0Y
};

static const char *const role_names[ROLE_COUNT] = {
    "LX", "LY", "RX", "RY", "LT", "RT", "HX", "HY"
};

typedef struct {
    int src;              /* physical axis code, -1 when unbound */
    int dst;              /* virtual axis code */
    int valid;
    int lo, hi;           /* usable physical range */
    int digital;          /* two-state trigger */
    int observed_max;     /* adaptive upper bound */
    int seen[8];          /* distinct values observed, for auto digital detect */
    int seen_count;
    int seen_lo, seen_hi;
} axis_bind_t;

typedef struct {
    int from;
    int to;
} code_map_t;

typedef struct {
    const char *name;
    const code_map_t *axes;
    int axis_count;
    const code_map_t *keys;
    int key_count;
    int dpad_to_hat;
    int passthrough;
} profile_t;

static const code_map_t redmi_axes[] = {
    {ABS_X, ABS_X}, {ABS_Y, ABS_Y},
    {ABS_Z, ABS_RX}, {ABS_RZ, ABS_RY},
    {ABS_GAS, ABS_Z}, {ABS_BRAKE, ABS_RZ},
    {ABS_HAT0X, ABS_HAT0X}, {ABS_HAT0Y, ABS_HAT0Y}
};

static const code_map_t android_axes[] = {
    {ABS_X, ABS_X}, {ABS_Y, ABS_Y},
    {ABS_Z, ABS_RX}, {ABS_RZ, ABS_RY},
    {ABS_GAS, ABS_Z}, {ABS_BRAKE, ABS_RZ},
    {ABS_RX, ABS_RX}, {ABS_RY, ABS_RY},
    {ABS_HAT0X, ABS_HAT0X}, {ABS_HAT0Y, ABS_HAT0Y}
};

static const code_map_t switch_keys[] = {
    {BTN_A, BTN_B}, {BTN_B, BTN_A},
    {BTN_X, BTN_Y}, {BTN_Y, BTN_X}
};

static profile_t builtin_profiles[] = {
    {"generic", NULL, 0, NULL, 0, 0, 1},
    {"xbox", NULL, 0, NULL, 0, 0, 1},
    {"ps", NULL, 0, NULL, 0, 0, 1},
    {"redmi", redmi_axes, (int)(sizeof(redmi_axes) / sizeof(redmi_axes[0])), NULL, 0, 0, 0},
    {"android", android_axes, (int)(sizeof(android_axes) / sizeof(android_axes[0])), NULL, 0, 0, 0},
    {"switch", NULL, 0, switch_keys, (int)(sizeof(switch_keys) / sizeof(switch_keys[0])), 1, 1}
};

typedef struct {
    char path[64];
    char name[128];
    unsigned short bus, vendor, product, version;
    unsigned long key_bits[KEY_BITS];
    unsigned long abs_bits[ABS_BITS];
    unsigned long prop_bits[PROP_BITS];
    int has_hat;
} device_info_t;

typedef struct {
    const char *prefix;
    const char *device_path;
    const char *profile_name;
    const char *config_path;
    const char *identity_key;
    const char *trigger_mode;
    int grab;
    int detach;
    int list_only;
    int stop_only;
    int help_only;
    int trigger_swap;
    int trigger_invert;
    int legacy_mapping;
} options_t;

typedef struct {
    int virtual_fd;
    int lock_fd;
    int physical_fd;
    FILE *log;
    char pid_path[256];
    char log_path[256];
    char status_path[256];
    options_t opt;
    const identity_t *identity;
    profile_t profile;
    code_map_t custom_axes[64];
    int custom_axis_count;
    code_map_t custom_keys[64];
    int custom_key_count;
    struct input_absinfo phys_abs[ABS_MAX + 1];
    unsigned char abs_valid[ABS_MAX + 1];
    device_info_t physical;
    axis_bind_t bind[ROLE_COUNT];
    int key_trigger_lt;
    int key_trigger_rt;
    int hat_x, hat_y;
    unsigned long long events;
    time_t started;
    volatile sig_atomic_t stopping;
} bridge_t;

static bridge_t bridge;

static void log_line(const char *fmt, ...) {
    va_list ap;
    time_t now = time(NULL);
    struct tm tm_buf;
    char stamp[32] = "";
    FILE *out = bridge.log ? bridge.log : stderr;
    if (localtime_r(&now, &tm_buf)) {
        strftime(stamp, sizeof(stamp), "%m-%d %H:%M:%S", &tm_buf);
    }
    fprintf(out, "[%s] ", stamp);
    va_start(ap, fmt);
    vfprintf(out, fmt, ap);
    va_end(ap);
    fputc('\n', out);
    fflush(out);
}

static int has_bit(const unsigned long *bits, size_t words, int code) {
    size_t index;
    if (code < 0 || code > KEY_MAX) return 0;
    index = (size_t)code / (sizeof(unsigned long) * 8);
    if (index >= words) return 0;
    return (int)((bits[index] >> (code % (sizeof(unsigned long) * 8))) & 1UL);
}

static int virtual_key_supported(int code) {
    size_t i;
    for (i = 0; i < sizeof(virtual_keys) / sizeof(virtual_keys[0]); ++i) {
        if (virtual_keys[i] == code) return 1;
    }
    return 0;
}

static int virtual_axis_range(int code, int *min, int *max) {
    switch (code) {
        case ABS_X: case ABS_Y: case ABS_RX: case ABS_RY:
            *min = STICK_MIN; *max = STICK_MAX; return 1;
        case ABS_Z: case ABS_RZ:
            *min = TRIGGER_MIN; *max = TRIGGER_MAX; return 1;
        case ABS_HAT0X: case ABS_HAT0Y:
            *min = HAT_MIN; *max = HAT_MAX; return 1;
        default:
            return 0;
    }
}

static const char *axis_label(int code) {
    switch (code) {
        case ABS_X: return "X";
        case ABS_Y: return "Y";
        case ABS_RX: return "RX";
        case ABS_RY: return "RY";
        case ABS_Z: return "Z";
        case ABS_RZ: return "RZ";
        case ABS_GAS: return "GAS";
        case ABS_BRAKE: return "BRAKE";
        case ABS_HAT0X: return "HX";
        case ABS_HAT0Y: return "HY";
        default: return "?";
    }
}

static const profile_t *find_profile(const char *name) {
    size_t i;
    for (i = 0; i < sizeof(builtin_profiles) / sizeof(builtin_profiles[0]); ++i) {
        if (strcmp(builtin_profiles[i].name, name) == 0) return &builtin_profiles[i];
    }
    return NULL;
}

static const char *profile_by_identity(const device_info_t *dev) {
    struct { unsigned short vendor, product; const char *profile; } ids[] = {
        {0x045e, 0x0b13, "redmi"},
        {0x045e, 0x028e, "xbox"}, {0x045e, 0x02e0, "xbox"},
        {0x045e, 0x02fd, "xbox"}, {0x045e, 0x0b12, "xbox"},
        {0x054c, 0x09cc, "ps"}, {0x054c, 0x0ce6, "ps"}, {0x054c, 0x05c4, "ps"},
        {0x057e, 0x2009, "switch"}, {0x057e, 0x2017, "switch"}
    };
    struct { const char *needle, *profile; } names[] = {
        {"REDMI", "redmi"}, {"Redmi", "redmi"},
        {"Xbox", "xbox"}, {"X-Box", "xbox"}, {"THUNDEROBOT", "xbox"},
        {"DualSense", "ps"}, {"DualShock", "ps"}, {"Wireless Controller", "ps"},
        {"Switch Pro", "switch"}, {"Pro Controller", "switch"},
    };
    size_t i;
    for (i = 0; i < sizeof(ids) / sizeof(ids[0]); ++i) {
        if (dev->vendor == ids[i].vendor && dev->product == ids[i].product) return ids[i].profile;
    }
    for (i = 0; i < sizeof(names) / sizeof(names[0]); ++i) {
        if (strstr(dev->name, names[i].needle)) return names[i].profile;
    }
    return NULL;
}

static const char *profile_by_layout(const device_info_t *dev) {
    int gas = has_bit(dev->abs_bits, ABS_BITS, ABS_GAS);
    int brake = has_bit(dev->abs_bits, ABS_BITS, ABS_BRAKE);
    int rx = has_bit(dev->abs_bits, ABS_BITS, ABS_RX);
    int ry = has_bit(dev->abs_bits, ABS_BITS, ABS_RY);
    int z = has_bit(dev->abs_bits, ABS_BITS, ABS_Z);
    int rz = has_bit(dev->abs_bits, ABS_BITS, ABS_RZ);
    if (gas || brake) return "android";
    if (rx && ry) return "xbox";
    if (z && rz) return "android";
    return "generic";
}

static int read_device_info(const char *path, device_info_t *out) {
    int fd;
    memset(out, 0, sizeof(*out));
    fd = open(path, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) return -1;
    snprintf(out->path, sizeof(out->path), "%s", path);
    if (ioctl(fd, EVIOCGNAME(sizeof(out->name) - 1), out->name) < 0) {
        snprintf(out->name, sizeof(out->name), "unknown");
    }
    {
        struct input_id id;
        if (ioctl(fd, EVIOCGID, &id) >= 0) {
            out->bus = id.bustype;
            out->vendor = id.vendor;
            out->product = id.product;
            out->version = id.version;
        }
    }
    ioctl(fd, EVIOCGBIT(EV_KEY, sizeof(out->key_bits)), out->key_bits);
    ioctl(fd, EVIOCGBIT(EV_ABS, sizeof(out->abs_bits)), out->abs_bits);
    ioctl(fd, EVIOCGPROP(sizeof(out->prop_bits)), out->prop_bits);
    out->has_hat = has_bit(out->abs_bits, ABS_BITS, ABS_HAT0X) ||
                   has_bit(out->abs_bits, ABS_BITS, ABS_HAT0Y);
    close(fd);
    return 0;
}

static int is_virtual_input(const device_info_t *dev) {
    char sysfs_path[64], resolved[256];
    const char *event_name = strrchr(dev->path, '/');
    if (!event_name) return 0;
    snprintf(sysfs_path, sizeof(sysfs_path), "/sys/class/input/%s/device", event_name + 1);
    if (!realpath(sysfs_path, resolved)) return 0;
    return strstr(resolved, "/devices/virtual/") != NULL;
}

static int looks_like_gamepad(const device_info_t *dev) {
    static const char *blacklist[] = {
        "touch", "Touch", "sensor", "Sensor", "accel", "gyro", "mouse", "Mouse",
        "keyboard", "Keyboard", "headset", "power", "Power", "input_mt", "uinput",
        "compass", "light", "proximity", "hall", "charger",
        "Pen", "pen", "Stylus", "stylus", "Capacitive", "capacitive", "Digitizer"
    };
    const identity_t *self = bridge.identity ? bridge.identity : &IDENTITIES[0];
    size_t i;
    int has_key = has_bit(dev->key_bits, KEY_BITS, BTN_GAMEPAD) ||
                  has_bit(dev->key_bits, KEY_BITS, BTN_JOYSTICK) ||
                  has_bit(dev->key_bits, KEY_BITS, BTN_TL) ||
                  has_bit(dev->key_bits, KEY_BITS, BTN_TR) ||
                  has_bit(dev->key_bits, KEY_BITS, BTN_SELECT) ||
                  has_bit(dev->key_bits, KEY_BITS, BTN_START) ||
                  has_bit(dev->key_bits, KEY_BITS, BTN_MODE) ||
                  has_bit(dev->key_bits, KEY_BITS, BTN_THUMBL) ||
                  has_bit(dev->key_bits, KEY_BITS, BTN_THUMBR);
    int has_abs = has_bit(dev->abs_bits, ABS_BITS, ABS_X) ||
                  has_bit(dev->abs_bits, ABS_BITS, ABS_Y) ||
                  has_bit(dev->abs_bits, ABS_BITS, ABS_RX) ||
                  has_bit(dev->abs_bits, ABS_BITS, ABS_RY) ||
                  has_bit(dev->abs_bits, ABS_BITS, ABS_Z) ||
                  has_bit(dev->abs_bits, ABS_BITS, ABS_RZ) ||
                  has_bit(dev->abs_bits, ABS_BITS, ABS_GAS) ||
                  has_bit(dev->abs_bits, ABS_BITS, ABS_BRAKE) ||
                  has_bit(dev->abs_bits, ABS_BITS, ABS_HAT0X);
    /* Only exclude our own uinput node. A physical Xbox controller can expose
     * the same name and VID:PID and must remain selectable. */
    if (is_virtual_input(dev) &&
        (strcmp(dev->name, self->name) == 0 ||
         (dev->vendor == self->vendor && dev->product == self->product &&
          dev->bus == self->bus))) return 0;
    for (i = 0; i < sizeof(blacklist) / sizeof(blacklist[0]); ++i) {
        if (strstr(dev->name, blacklist[i])) return 0;
    }
    if (has_bit(dev->prop_bits, PROP_BITS, INPUT_PROP_DIRECT)) return 0;
    /* Some digital-only controllers have buttons and a D-pad but no EV_ABS. */
    return has_key && (has_abs || has_bit(dev->key_bits, KEY_BITS, BTN_GAMEPAD) ||
                       has_bit(dev->key_bits, KEY_BITS, BTN_JOYSTICK));
}

static int scan_devices(device_info_t *list, int capacity) {
    int count = 0, index;
    for (index = 0; index < MAX_EVENT_DEVICES && count < capacity; ++index) {
        char path[64];
        device_info_t info;
        snprintf(path, sizeof(path), "/dev/input/event%d", index);
        if (access(path, R_OK) != 0) continue;
        if (read_device_info(path, &info) != 0) continue;
        if (!looks_like_gamepad(&info)) continue;
        list[count++] = info;
    }
    return count;
}

static void describe_axes(const device_info_t *dev, char *out, size_t size) {
    struct { int code; const char *label; } axes[] = {
        {ABS_X, "X"}, {ABS_Y, "Y"}, {ABS_RX, "RX"}, {ABS_RY, "RY"},
        {ABS_Z, "Z"}, {ABS_RZ, "RZ"}, {ABS_GAS, "GAS"}, {ABS_BRAKE, "BRAKE"},
        {ABS_HAT0X, "HX"}, {ABS_HAT0Y, "HY"}
    };
    size_t i, used = 0;
    if (size == 0) return;
    out[0] = '\0';
    for (i = 0; i < sizeof(axes) / sizeof(axes[0]); ++i) {
        int written;
        if (!has_bit(dev->abs_bits, ABS_BITS, axes[i].code)) continue;
        written = snprintf(out + used, size - used, "%s%s",
                           used ? "," : "", axes[i].label);
        if (written < 0 || (size_t)written >= size - used) break;
        used += (size_t)written;
    }
}

/* Pure capability-based inference, used by --list. One virtual axis = one
 * physical source, so Z/RZ can never be both right stick and triggers. */
static void infer_layout(const device_info_t *dev, char *out, size_t size) {
    int rx = has_bit(dev->abs_bits, ABS_BITS, ABS_RX);
    int ry = has_bit(dev->abs_bits, ABS_BITS, ABS_RY);
    int z = has_bit(dev->abs_bits, ABS_BITS, ABS_Z);
    int rz = has_bit(dev->abs_bits, ABS_BITS, ABS_RZ);
    int gas = has_bit(dev->abs_bits, ABS_BITS, ABS_GAS);
    int brake = has_bit(dev->abs_bits, ABS_BITS, ABS_BRAKE);
    int key_triggers = has_bit(dev->key_bits, KEY_BITS, BTN_TL2) &&
                       has_bit(dev->key_bits, KEY_BITS, BTN_TR2);
    int z_is_stick = 0;
    const char *rs = "none", *lt = "none", *rt = "none";
    if (rx && ry) rs = "RX/RY";
    else if (z && rz && (gas || brake || key_triggers)) {
        rs = "Z/RZ";
        z_is_stick = 1;
    }
    if (brake) lt = "BRAKE";
    else if (gas) lt = "GAS";
    else if (z && !z_is_stick) lt = "Z";
    if (gas) rt = "GAS";
    else if (brake) rt = "BRAKE";
    else if (rz && !z_is_stick) rt = "RZ";
    if (lt[0] == 'n' && has_bit(dev->key_bits, KEY_BITS, BTN_TL2)) lt = "BTN_TL2";
    if (rt[0] == 'n' && has_bit(dev->key_bits, KEY_BITS, BTN_TR2)) rt = "BTN_TR2";
    snprintf(out, size, "lt=%s,rt=%s,rs=%s", lt, rt, rs);
}

static int has_analog_trigger(const device_info_t *dev) {
    int z = has_bit(dev->abs_bits, ABS_BITS, ABS_Z);
    int rz = has_bit(dev->abs_bits, ABS_BITS, ABS_RZ);
    if (has_bit(dev->abs_bits, ABS_BITS, ABS_GAS) ||
        has_bit(dev->abs_bits, ABS_BITS, ABS_BRAKE)) return 1;
    if (z && rz && has_bit(dev->key_bits, KEY_BITS, BTN_TL2) &&
        has_bit(dev->key_bits, KEY_BITS, BTN_TR2)) return 0;
    return z || rz;
}

static void print_devices(void) {
    device_info_t list[MAX_EVENT_DEVICES];
    int count = scan_devices(list, MAX_EVENT_DEVICES), i;
    for (i = 0; i < count; ++i) {
        char axes[160], layout[160];
        const char *identity = profile_by_identity(&list[i]);
        const char *picked = identity ? identity : profile_by_layout(&list[i]);
        describe_axes(&list[i], axes, sizeof(axes));
        infer_layout(&list[i], layout, sizeof(layout));
        printf("%s\t%s\t%04x:%04x\tbus=%u\that=%d\tprofile=%s\ttriggers=%s\taxes=%s\tlayout=%s\n",
               list[i].path, list[i].name, list[i].vendor, list[i].product,
               list[i].bus, list[i].has_hat, picked,
               has_analog_trigger(&list[i]) ? "analog" : "digital",
               axes[0] ? axes : "none", layout);
    }
    if (count == 0) printf("# no gamepad found\n");
}

/* ------------------------------------------------------------------------- */
/* Runtime axis binding                                                      */
/* ------------------------------------------------------------------------- */
static void bind_axis(axis_role_t role, int src) {
    axis_bind_t *b = &bridge.bind[role];
    memset(b, 0, sizeof(*b));
    b->src = src;
    b->dst = role_to_virtual[role];
    b->valid = (src >= 0);
    b->lo = FALLBACK_ABS_MIN;
    b->hi = FALLBACK_ABS_MAX;
    b->seen_lo = 0x7fffffff;
    b->seen_hi = -0x7fffffff;
    if (!b->valid) return;
    if (bridge.abs_valid[src] &&
        bridge.phys_abs[src].maximum > bridge.phys_abs[src].minimum) {
        b->lo = bridge.phys_abs[src].minimum;
        b->hi = bridge.phys_abs[src].maximum;
    } else {
        /* No EVIOCGABS data: keep the 0..255 fallback and extend it at
         * runtime from observed values. This is what made thin-film triggers
         * on USB-C pads top out at a quarter of their virtual range. */
        log_line("axis %s has no absinfo, using adaptive 0..255", axis_label(src));
    }
    /* Signed axes rest at 0, not at minimum. Treating minimum as the rest
     * point makes a trigger report ~half pressed while untouched. */
    if (b->lo < 0 && b->hi > 0) b->lo = 0;
    b->observed_max = b->hi;
    if (b->hi - b->lo <= 1) b->digital = 1;
}

static int role_for_virtual_axis(int code) {
    int r;
    for (r = 0; r < ROLE_COUNT; ++r) {
        if (role_to_virtual[r] == code) return r;
    }
    return -1;
}

static void build_axis_bindings(const device_info_t *dev) {
    int rx = has_bit(dev->abs_bits, ABS_BITS, ABS_RX);
    int ry = has_bit(dev->abs_bits, ABS_BITS, ABS_RY);
    int z = has_bit(dev->abs_bits, ABS_BITS, ABS_Z);
    int rz = has_bit(dev->abs_bits, ABS_BITS, ABS_RZ);
    int gas = has_bit(dev->abs_bits, ABS_BITS, ABS_GAS);
    int brake = has_bit(dev->abs_bits, ABS_BITS, ABS_BRAKE);
    int key_triggers = has_bit(dev->key_bits, KEY_BITS, BTN_TL2) &&
                       has_bit(dev->key_bits, KEY_BITS, BTN_TR2);
    int z_is_stick = 0;
    int lt_src = -1, rt_src = -1, i;

    bind_axis(ROLE_LX, has_bit(dev->abs_bits, ABS_BITS, ABS_X) ? ABS_X : -1);
    bind_axis(ROLE_LY, has_bit(dev->abs_bits, ABS_BITS, ABS_Y) ? ABS_Y : -1);
    bind_axis(ROLE_HX, has_bit(dev->abs_bits, ABS_BITS, ABS_HAT0X) ? ABS_HAT0X : -1);
    bind_axis(ROLE_HY, has_bit(dev->abs_bits, ABS_BITS, ABS_HAT0Y) ? ABS_HAT0Y : -1);

    if (rx && ry) {
        bind_axis(ROLE_RX, ABS_RX);
        bind_axis(ROLE_RY, ABS_RY);
    } else if (z && rz && (gas || brake || key_triggers)) {
        /* Android pads use Z/RZ for the right stick with either analog
         * GAS/BRAKE triggers (X5 Lite / REDMI) or BTN_TL2/TR2 key triggers. */
        bind_axis(ROLE_RX, ABS_Z);
        bind_axis(ROLE_RY, ABS_RZ);
        z_is_stick = 1;
    } else {
        bind_axis(ROLE_RX, -1);
        bind_axis(ROLE_RY, -1);
    }

    /* Linux gamepad spec + AOSP keylayouts: BRAKE = left trigger (L2),
     * GAS = right trigger (R2). The old hardcoded profiles had these two
     * reversed, which is the most common cause of "LT/RT swapped". */
    if (brake) lt_src = ABS_BRAKE;
    else if (gas) lt_src = ABS_GAS;
    else if (z && !z_is_stick) lt_src = ABS_Z;

    if (gas) rt_src = ABS_GAS;
    else if (brake) rt_src = ABS_BRAKE;
    else if (rz && !z_is_stick) rt_src = ABS_RZ;

    if (lt_src >= 0 && lt_src == rt_src) {
        if (z && !z_is_stick && rt_src != ABS_Z) lt_src = ABS_Z;
        else rt_src = -1;
    }
    if (z_is_stick) {
        if (lt_src == ABS_Z || lt_src == ABS_RZ) lt_src = -1;
        if (rt_src == ABS_Z || rt_src == ABS_RZ) rt_src = -1;
    }
    if (bridge.opt.trigger_swap) {
        int tmp = lt_src;
        lt_src = rt_src;
        rt_src = tmp;
    }
    bind_axis(ROLE_LT, lt_src);
    bind_axis(ROLE_RT, rt_src);

    bridge.key_trigger_lt = (lt_src < 0) && has_bit(dev->key_bits, KEY_BITS, BTN_TL2);
    bridge.key_trigger_rt = (rt_src < 0) && has_bit(dev->key_bits, KEY_BITS, BTN_TR2);

    /* --config "axis FROM TO" always wins over auto inference. */
    for (i = 0; i < bridge.custom_axis_count; ++i) {
        int from = bridge.custom_axes[i].from;
        int role = role_for_virtual_axis(bridge.custom_axes[i].to);
        if (role >= 0 && from >= 0 && from <= ABS_MAX) {
            bind_axis((axis_role_t)role, from);
        } else {
            log_line("ignoring invalid axis mapping %d -> %d", from,
                     bridge.custom_axes[i].to);
        }
    }
}

static void describe_bindings(void) {
    int r;
    log_line("identity=%s %04x:%04x bus=%u",
             bridge.identity->name, bridge.identity->vendor,
             bridge.identity->product, bridge.identity->bus);
    for (r = 0; r < ROLE_COUNT; ++r) {
        axis_bind_t *b = &bridge.bind[r];
        if (!b->valid) {
            log_line("bind %s <- none", role_names[r]);
            continue;
        }
        log_line("bind %s <- %s [%d..%d] -> %s%s",
                 role_names[r], axis_label(b->src), b->lo, b->hi,
                 axis_label(b->dst), b->digital ? " (digital)" : "");
    }
    if (bridge.key_trigger_lt) log_line("bind LT <- BTN_TL2 (button -> axis)");
    if (bridge.key_trigger_rt) log_line("bind RT <- BTN_TR2 (button -> axis)");
    if (bridge.opt.trigger_swap) log_line("trigger left/right swapped");
    if (bridge.opt.trigger_invert) log_line("trigger polarity inverted");
}

/* ------------------------------------------------------------------------- */
/* Virtual device creation                                                   */
/* ------------------------------------------------------------------------- */
static int set_axis(int fd, int code, int min, int max, int fuzz, int flat) {
    struct uinput_abs_setup setup;
    memset(&setup, 0, sizeof(setup));
    /* Some kernels require the capability bit before UI_ABS_SETUP. */
    if (ioctl(fd, UI_SET_ABSBIT, code) < 0) return -1;
    setup.code = code;
    setup.absinfo.minimum = min;
    setup.absinfo.maximum = max;
    setup.absinfo.fuzz = fuzz;
    setup.absinfo.flat = flat;
    return ioctl(fd, UI_ABS_SETUP, &setup);
}

static int create_virtual_device(void) {
    struct uinput_setup setup;
    size_t i;
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) {
        log_line("open /dev/uinput failed: %s", strerror(errno));
        return -1;
    }
    if (ioctl(fd, UI_SET_EVBIT, EV_KEY) < 0 ||
        ioctl(fd, UI_SET_EVBIT, EV_ABS) < 0 ||
        ioctl(fd, UI_SET_EVBIT, EV_MSC) < 0 ||
        ioctl(fd, UI_SET_MSCBIT, MSC_SCAN) < 0) {
        log_line("configure event types failed: %s", strerror(errno));
        close(fd);
        return -1;
    }
    for (i = 0; i < sizeof(virtual_keys) / sizeof(virtual_keys[0]); ++i) {
        if (ioctl(fd, UI_SET_KEYBIT, virtual_keys[i]) < 0) {
            log_line("configure key %d failed: %s", virtual_keys[i], strerror(errno));
            close(fd);
            return -1;
        }
    }
    for (i = 0; i < sizeof(virtual_axes) / sizeof(virtual_axes[0]); ++i) {
        int code = virtual_axes[i], min, max, fuzz, flat;
        if (!virtual_axis_range(code, &min, &max)) continue;
        if (code == ABS_Z || code == ABS_RZ) {
            fuzz = TRIGGER_FUZZ; flat = TRIGGER_FLAT;
        } else if (code == ABS_HAT0X || code == ABS_HAT0Y) {
            fuzz = 0; flat = 0;
        } else {
            fuzz = STICK_FUZZ; flat = STICK_FLAT;
        }
        if (set_axis(fd, code, min, max, fuzz, flat) < 0) {
            log_line("configure axis %d failed: %s", code, strerror(errno));
            close(fd);
            return -1;
        }
    }
    memset(&setup, 0, sizeof(setup));
    snprintf(setup.name, sizeof(setup.name), "%s", bridge.identity->name);
    setup.id.bustype = bridge.identity->bus;
    setup.id.vendor = bridge.identity->vendor;
    setup.id.product = bridge.identity->product;
    setup.id.version = bridge.identity->version;
    if (ioctl(fd, UI_DEV_SETUP, &setup) < 0 || ioctl(fd, UI_DEV_CREATE) < 0) {
        log_line("create virtual device failed: %s", strerror(errno));
        close(fd);
        return -1;
    }
    return fd;
}

static int emit_event(int fd, unsigned short type, unsigned short code, int value) {
    struct input_event event;
    memset(&event, 0, sizeof(event));
    gettimeofday(&event.time, NULL);
    event.type = type;
    event.code = code;
    event.value = value;
    if (write(fd, &event, sizeof(event)) != (ssize_t)sizeof(event)) return -1;
    return 0;
}

static void initialize_virtual_axes(int fd) {
    emit_event(fd, EV_ABS, ABS_X, (STICK_MIN + STICK_MAX) / 2);
    emit_event(fd, EV_ABS, ABS_Y, (STICK_MIN + STICK_MAX) / 2);
    emit_event(fd, EV_ABS, ABS_RX, (STICK_MIN + STICK_MAX) / 2);
    emit_event(fd, EV_ABS, ABS_RY, (STICK_MIN + STICK_MAX) / 2);
    emit_event(fd, EV_ABS, ABS_Z, TRIGGER_MIN);
    emit_event(fd, EV_ABS, ABS_RZ, TRIGGER_MIN);
    emit_event(fd, EV_ABS, ABS_HAT0X, 0);
    emit_event(fd, EV_ABS, ABS_HAT0Y, 0);
    emit_event(fd, EV_SYN, SYN_REPORT, 0);
}

static void load_physical_absinfo(int fd) {
    int code;
    memset(bridge.abs_valid, 0, sizeof(bridge.abs_valid));
    for (code = 0; code <= ABS_MAX; ++code) {
        if (ioctl(fd, EVIOCGABS(code), &bridge.phys_abs[code]) == 0) {
            bridge.abs_valid[code] = 1;
        }
    }
}

static int scale_range(int value, int lo, int hi, int vmin, int vmax) {
    long long scaled;
    if (hi <= lo) return vmin;
    scaled = ((long long)value - lo) * (vmax - vmin) / (hi - lo) + vmin;
    if (scaled < vmin) scaled = vmin;
    if (scaled > vmax) scaled = vmax;
    return (int)scaled;
}

static int scale_value(int from, int to, int value) {
    int vmin, vmax;
    if (!virtual_axis_range(to, &vmin, &vmax)) return value;
    if (from < 0 || from > ABS_MAX || !bridge.abs_valid[from]) return value;
    return scale_range(value, bridge.phys_abs[from].minimum,
                       bridge.phys_abs[from].maximum, vmin, vmax);
}

static int map_axis(int code) {
    int i;
    const profile_t *p = &bridge.profile;
    for (i = 0; i < bridge.custom_axis_count; ++i) {
        if (bridge.custom_axes[i].from == code) return bridge.custom_axes[i].to;
    }
    for (i = 0; i < p->axis_count; ++i) {
        if (p->axes[i].from == code) return p->axes[i].to;
    }
    if (p->passthrough) {
        int min, max;
        if (virtual_axis_range(code, &min, &max)) return code;
    }
    return -1;
}

static int map_key(int code) {
    int i;
    const profile_t *p = &bridge.profile;
    for (i = 0; i < bridge.custom_key_count; ++i) {
        if (bridge.custom_keys[i].from == code) return bridge.custom_keys[i].to;
    }
    for (i = 0; i < p->key_count; ++i) {
        if (p->keys[i].from == code) return p->keys[i].to;
    }
    return code;
}

static int role_for_src(int code) {
    int r;
    for (r = 0; r < ROLE_COUNT; ++r) {
        if (bridge.bind[r].valid && bridge.bind[r].src == code) return r;
    }
    return -1;
}

static int trigger_digital_forced(void) {
    return strcmp(bridge.opt.trigger_mode, "digital") == 0;
}

static int trigger_value(axis_bind_t *b, int raw) {
    int lo = b->lo, hi = b->hi;
    int vmin = TRIGGER_MIN, vmax = TRIGGER_MAX;
    int scaled, i, found = 0;

    if (raw > b->observed_max) b->observed_max = raw;
    if (b->observed_max > hi) hi = b->observed_max;

    /* Detect two-state (thin-film / membrane) triggers at runtime. Once more
     * than three distinct values appear we go back to analogue handling, so a
     * real analogue trigger is never stuck in digital mode. */
    if (strcmp(bridge.opt.trigger_mode, "analog") != 0) {
        for (i = 0; i < b->seen_count; ++i) {
            if (b->seen[i] == raw) { found = 1; break; }
        }
        if (!found && b->seen_count < 8) b->seen[b->seen_count++] = raw;
        if (raw < b->seen_lo) b->seen_lo = raw;
        if (raw > b->seen_hi) b->seen_hi = raw;
        if (hi > lo) {
            if (b->seen_count <= 3 && (b->seen_hi - b->seen_lo) > (hi - lo) / 2) {
                b->digital = 1;
            } else if (b->seen_count > 3) {
                b->digital = 0;
            }
        }
    }
    if (strcmp(bridge.opt.trigger_mode, "analog") == 0) b->digital = 0;

    if (bridge.opt.trigger_invert) raw = lo + hi - raw;
    scaled = scale_range(raw, lo, hi, vmin, vmax);
    if (b->digital || trigger_digital_forced()) {
        return scaled > (vmin + vmax) / 2 ? vmax : vmin;
    }
    return scaled;
}

static void emit_axis_value(axis_role_t role, int raw) {
    axis_bind_t *b = &bridge.bind[role];
    int vmin, vmax, value;
    if (!b->valid) return;
    if (!virtual_axis_range(b->dst, &vmin, &vmax)) return;
    if (role == ROLE_LT || role == ROLE_RT) {
        value = trigger_value(b, raw);
    } else if (role == ROLE_HX || role == ROLE_HY) {
        value = raw < vmin ? vmin : (raw > vmax ? vmax : raw);
    } else {
        int lo = b->lo, hi = b->hi;
        if (raw > b->observed_max) b->observed_max = raw;
        if (b->observed_max > hi) hi = b->observed_max;
        value = scale_range(raw, lo, hi, vmin, vmax);
    }
    emit_event(bridge.virtual_fd, EV_ABS, b->dst, value);
}

static void emit_dpad(int code, int value) {
    int changed = 0;
    int x = bridge.hat_x, y = bridge.hat_y;
    switch (code) {
        case BTN_DPAD_LEFT:  x = -value; break;
        case BTN_DPAD_RIGHT: x = value; break;
        case BTN_DPAD_UP:    y = -value; break;
        case BTN_DPAD_DOWN:  y = value; break;
        default: return;
    }
    if (x != bridge.hat_x) { bridge.hat_x = x; changed = 1; }
    if (y != bridge.hat_y) { bridge.hat_y = y; changed = 1; }
    if (!changed) return;
    emit_event(bridge.virtual_fd, EV_ABS, ABS_HAT0X, bridge.hat_x);
    emit_event(bridge.virtual_fd, EV_ABS, ABS_HAT0Y, bridge.hat_y);
    emit_event(bridge.virtual_fd, EV_SYN, SYN_REPORT, 0);
}

static void emit_trigger_key(axis_role_t role, int down) {
    int vmin, vmax;
    if (!virtual_axis_range(bridge.bind[role].dst, &vmin, &vmax)) return;
    emit_event(bridge.virtual_fd, EV_ABS, bridge.bind[role].dst,
               down ? vmax : vmin);
}

static void forward_event(const struct input_event *event) {
    struct input_event mapped = *event;
    if (event->type == EV_ABS) {
        if (!bridge.opt.legacy_mapping) {
            int role = role_for_src(event->code);
            /* Unbound axes are dropped on purpose: feeding an unmapped source
             * through passthrough is what used to make Z/RZ trigger both the
             * right stick and the triggers. */
            if (role < 0) return;
            emit_axis_value((axis_role_t)role, event->value);
        } else {
            int target = map_axis(event->code);
            int vmin, vmax;
            if (target < 0 || !virtual_axis_range(target, &vmin, &vmax)) return;
            if (bridge.opt.trigger_swap) {
                if (target == ABS_Z) target = ABS_RZ;
                else if (target == ABS_RZ) target = ABS_Z;
            }
            mapped.code = (unsigned short)target;
            mapped.value = scale_value(event->code, target, event->value);
            if (target == ABS_Z || target == ABS_RZ) {
                int role = (target == ABS_Z) ? ROLE_LT : ROLE_RT;
                axis_bind_t *b = &bridge.bind[role];
                if (b->valid && b->src == event->code) {
                    mapped.value = trigger_value(b, event->value);
                } else {
                    if (bridge.opt.trigger_invert)
                        mapped.value = vmin + vmax - mapped.value;
                    if (trigger_digital_forced())
                        mapped.value = mapped.value > (vmin + vmax) / 2 ? vmax : vmin;
                }
            }
            if (write(bridge.virtual_fd, &mapped, sizeof(mapped)) != (ssize_t)sizeof(mapped)) {
                log_line("write virtual event failed: %s", strerror(errno));
                return;
            }
            bridge.events++;
        }
        return;
    }
    if (event->type == EV_KEY) {
        int target;
        if (event->code == BTN_TL2 && bridge.key_trigger_lt) {
            emit_trigger_key(bridge.opt.trigger_swap && bridge.key_trigger_rt
                             ? ROLE_RT : ROLE_LT, event->value);
            return;
        }
        if (event->code == BTN_TR2 && bridge.key_trigger_rt) {
            emit_trigger_key(bridge.opt.trigger_swap && bridge.key_trigger_lt
                             ? ROLE_LT : ROLE_RT, event->value);
            return;
        }
        /* Button-style D-pads are converted for every profile, not just the
         * Switch one. Virtual keys never include BTN_DPAD_*, so without this
         * they were silently dropped. */
        if (!bridge.physical.has_hat &&
            event->code >= BTN_DPAD_UP && event->code <= BTN_DPAD_RIGHT) {
            emit_dpad(event->code, event->value);
            return;
        }
        target = map_key(event->code);
        if (!virtual_key_supported(target)) return;
        mapped.code = (unsigned short)target;
    } else if (event->type == EV_MSC) {
        if (event->code != MSC_SCAN) return;
    } else if (event->type != EV_SYN) {
        return;
    }
    if (write(bridge.virtual_fd, &mapped, sizeof(mapped)) != (ssize_t)sizeof(mapped)) {
        log_line("write virtual event failed: %s", strerror(errno));
        return;
    }
    bridge.events++;
}

static void write_status(const char *state) {
    char path[256];
    char name[160];
    FILE *fp;
    snprintf(path, sizeof(path), "%s.tmp", bridge.status_path);
    fp = fopen(path, "w");
    if (!fp) return;
    snprintf(name, sizeof(name), "%s", bridge.physical.name);
    fprintf(fp,
            "{\"state\":\"%s\",\"pid\":%ld,\"profile\":\"%s\",\"physical\":\"%s\","
            "\"physical_path\":\"%s\",\"virtual\":\"%s\",\"identity\":\"%s\","
            "\"vid\":\"%04x\",\"pid_hex\":\"%04x\",\"bus\":%u,"
            "\"events\":%llu,\"uptime\":%ld}\n",
            state, (long)getpid(), bridge.profile.name, name,
            bridge.physical.path, bridge.identity->name, bridge.identity->key,
            bridge.identity->vendor, bridge.identity->product,
            (unsigned)bridge.identity->bus, bridge.events,
            (long)(time(NULL) - bridge.started));
    fclose(fp);
    rename(path, bridge.status_path);
}

static void on_signal(int signal_number) {
    (void)signal_number;
    bridge.stopping = 1;
}

static void cleanup(void) {
    if (bridge.physical_fd >= 0) {
        ioctl(bridge.physical_fd, EVIOCGRAB, 0);
        close(bridge.physical_fd);
        bridge.physical_fd = -1;
    }
    if (bridge.virtual_fd >= 0) {
        ioctl(bridge.virtual_fd, UI_DEV_DESTROY);
        close(bridge.virtual_fd);
        bridge.virtual_fd = -1;
    }
    unlink(bridge.pid_path);
    memset(&bridge.physical, 0, sizeof(bridge.physical));
    bridge.events = 0;
    write_status("stopped");
    log_line("bridge stopped");
    if (bridge.lock_fd >= 0) close(bridge.lock_fd);
}

static int acquire_singleton(void) {
    bridge.lock_fd = open(bridge.pid_path, O_CREAT | O_RDWR | O_CLOEXEC, 0666);
    if (bridge.lock_fd < 0) {
        log_line("open pid file failed: %s", strerror(errno));
        return -1;
    }
    if (flock(bridge.lock_fd, LOCK_EX | LOCK_NB) < 0) {
        log_line("another bridge instance is already running");
        close(bridge.lock_fd);
        bridge.lock_fd = -1;
        return -1;
    }
    if (ftruncate(bridge.lock_fd, 0) < 0 || lseek(bridge.lock_fd, 0, SEEK_SET) < 0) {
        log_line("reset pid file failed: %s", strerror(errno));
        return -1;
    }
    dprintf(bridge.lock_fd, "%ld\n", (long)getpid());
    fsync(bridge.lock_fd);
    return 0;
}

static int stop_running(void) {
    char buf[64] = {0};
    long pid = -1;
    int fd = open(bridge.pid_path, O_RDONLY);
    if (fd < 0) { printf("bridge is not running\n"); return 0; }
    if (read(fd, buf, sizeof(buf) - 1) > 0) pid = strtol(buf, NULL, 10);
    close(fd);
    if (pid <= 1) { printf("invalid pid file\n"); return 1; }
    if (kill((pid_t)pid, SIGTERM) < 0) {
        printf("kill %ld failed: %s\n", pid, strerror(errno));
        return 1;
    }
    printf("sent SIGTERM to %ld\n", pid);
    return 0;
}

static int parse_config(const char *path) {
    char line[256];
    FILE *fp = fopen(path, "r");
    if (!fp) { log_line("open config failed: %s", strerror(errno)); return -1; }
    while (fgets(line, sizeof(line), fp)) {
        char kind[32] = {0};
        int from = 0, to = 0;
        if (line[0] == '#' || line[0] == '\n') continue;
        if (sscanf(line, "%31s %i %i", kind, &from, &to) != 3) continue;
        if (!strcmp(kind, "axis") && bridge.custom_axis_count < 64) {
            bridge.custom_axes[bridge.custom_axis_count].from = from;
            bridge.custom_axes[bridge.custom_axis_count].to = to;
            bridge.custom_axis_count++;
        } else if (!strcmp(kind, "key") && bridge.custom_key_count < 64) {
            bridge.custom_keys[bridge.custom_key_count].from = from;
            bridge.custom_keys[bridge.custom_key_count].to = to;
            bridge.custom_key_count++;
        }
    }
    fclose(fp);
    return 0;
}

static int device_matches_selection(const device_info_t *dev, const char *selection) {
    unsigned vendor, product;
    char extra;
    if (selection[0] == '/') return strcmp(dev->path, selection) == 0;
    return sscanf(selection, "%x:%x%c", &vendor, &product, &extra) == 2 &&
           dev->vendor == vendor && dev->product == product;
}

static int open_physical(void) {
    device_info_t list[MAX_EVENT_DEVICES];
    int count, i, chosen = -1;
    count = scan_devices(list, MAX_EVENT_DEVICES);
    if (bridge.opt.device_path) {
        for (i = 0; i < count; ++i) {
            if (device_matches_selection(&list[i], bridge.opt.device_path)) {
                chosen = i;
                break;
            }
        }
    } else if (count > 0) {
        chosen = 0;
    }
    if (chosen < 0) return -1;
    bridge.physical = list[chosen];
    bridge.physical_fd = open(bridge.physical.path, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
    if (bridge.physical_fd < 0) {
        log_line("open %s failed: %s", bridge.physical.path, strerror(errno));
        return -1;
    }
    if (bridge.opt.grab) {
        if (ioctl(bridge.physical_fd, EVIOCGRAB, 1) < 0) {
            log_line("WARNING: EVIOCGRAB failed; duplicate input is possible: %s",
                     strerror(errno));
        } else {
            log_line("exclusive access enabled for %s", bridge.physical.path);
        }
    }
    load_physical_absinfo(bridge.physical_fd);
    if (!strcmp(bridge.opt.profile_name, "auto")) {
        const char *by_identity = profile_by_identity(&bridge.physical);
        const char *picked = by_identity ? by_identity : profile_by_layout(&bridge.physical);
        const profile_t *p = find_profile(picked);
        if (p) bridge.profile = *p;
    }
    build_axis_bindings(&bridge.physical);
    describe_bindings();
    bridge.hat_x = 0;
    bridge.hat_y = 0;
    log_line("connected %s (%s, %04x:%04x) profile=%s",
             bridge.physical.name, bridge.physical.path,
             bridge.physical.vendor, bridge.physical.product, bridge.profile.name);
    write_status("running");
    return 0;
}

static void close_physical(void) {
    size_t i;
    if (bridge.physical_fd >= 0) {
        ioctl(bridge.physical_fd, EVIOCGRAB, 0);
        close(bridge.physical_fd);
        bridge.physical_fd = -1;
    }
    if (bridge.virtual_fd >= 0) {
        for (i = 0; i < sizeof(virtual_keys) / sizeof(virtual_keys[0]); ++i) {
            emit_event(bridge.virtual_fd, EV_KEY, virtual_keys[i], 0);
        }
        initialize_virtual_axes(bridge.virtual_fd);
    }
    memset(&bridge.physical, 0, sizeof(bridge.physical));
    bridge.events = 0;
    write_status("waiting");
}

static int run_bridge(void) {
    struct pollfd pfd;
    time_t last_status = 0;
    bridge.started = time(NULL);
    bridge.virtual_fd = create_virtual_device();
    if (bridge.virtual_fd < 0) {
        write_status("error");
        return 1;
    }
    sleep(1);
    initialize_virtual_axes(bridge.virtual_fd);
    log_line("virtual %s created (%04x:%04x, bus=%u)",
             bridge.identity->name, bridge.identity->vendor,
             bridge.identity->product, bridge.identity->bus);
    write_status("waiting");

    while (!bridge.stopping) {
        if (bridge.physical_fd < 0) {
            if (open_physical() < 0) {
                sleep(1);
                if (time(NULL) - last_status >= 5) {
                    write_status("waiting");
                    last_status = time(NULL);
                }
                continue;
            }
        }
        pfd.fd = bridge.physical_fd;
        pfd.events = POLLIN;
        pfd.revents = 0;
        {
            int ready = poll(&pfd, 1, 1000);
            if (ready < 0) {
                if (errno == EINTR) continue;
                log_line("poll failed: %s", strerror(errno));
                close_physical();
                continue;
            }
            if (ready == 0) {
                if (time(NULL) - last_status >= 2) {
                    write_status("running");
                    last_status = time(NULL);
                }
                continue;
            }
            if (pfd.revents & (POLLERR | POLLHUP | POLLNVAL)) {
                log_line("physical controller disconnected");
                close_physical();
                continue;
            }
            if (pfd.revents & POLLIN) {
                struct input_event event;
                for (;;) {
                    ssize_t count = read(bridge.physical_fd, &event, sizeof(event));
                    if (count == (ssize_t)sizeof(event)) {
                        forward_event(&event);
                    } else if (count < 0 && (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR)) {
                        break;
                    } else {
                        log_line("physical read ended (count=%zd errno=%s)", count, strerror(errno));
                        close_physical();
                        break;
                    }
                }
            }
        }
    }
    cleanup();
    return 0;
}

static void print_help(void) {
    printf("uinput_x360 - generic gamepad -> virtual console gamepad bridge\n\n");
    printf("usage: uinput_x360 [options]\n\n");
    printf("  --list                list detected gamepads and exit\n");
    printf("  --device PATH|VID:PID use an event path or a stable device ID\n");
    printf("  --profile NAME        auto|generic|xbox|ps|redmi|android|switch (default auto)\n");
    printf("  --identity KEY        xbox360|xboxone|xboxbt|ds4|dualsense (default xbox360)\n");
    printf("  --trigger-mode MODE   auto|analog|digital (default auto)\n");
    printf("  --trigger-swap        swap LT and RT\n");
    printf("  --trigger-invert      invert trigger polarity\n");
    printf("  --legacy-mapping      use the old hardcoded profile axis tables\n");
    printf("  --config PATH         load custom 'axis FROM TO' / 'key FROM TO' rules\n");
    printf("  --prefix PATH         prefix for .pid/.log/.status (default " DEFAULT_PREFIX ")\n");
    printf("  --no-grab             do not EVIOCGRAB the physical device\n");
    printf("  --detach              fork, setsid and run in background\n");
    printf("  --stop                stop the running instance and exit\n");
    printf("  --help                show this help\n\n");
    printf("Axis roles are inferred from the physical device at connect time.\n");
    printf("Check the log for the 'bind LX/LY/RX/RY/LT/RT' table when debugging.\n");
    printf("The virtual device stays alive when the physical gamepad is unplugged.\n");
}

static void parse_options(int argc, char **argv, options_t *opt) {
    int i;
    memset(opt, 0, sizeof(*opt));
    opt->prefix = DEFAULT_PREFIX;
    opt->profile_name = "auto";
    opt->identity_key = "xbox360";
    opt->trigger_mode = "auto";
    opt->grab = 1;
    for (i = 1; i < argc; ++i) {
        if (!strcmp(argv[i], "--list")) opt->list_only = 1;
        else if (!strcmp(argv[i], "--detach")) opt->detach = 1;
        else if (!strcmp(argv[i], "--no-grab")) opt->grab = 0;
        else if (!strcmp(argv[i], "--stop")) opt->stop_only = 1;
        else if (!strcmp(argv[i], "--trigger-swap")) opt->trigger_swap = 1;
        else if (!strcmp(argv[i], "--trigger-invert")) opt->trigger_invert = 1;
        else if (!strcmp(argv[i], "--legacy-mapping")) opt->legacy_mapping = 1;
        else if (!strcmp(argv[i], "--help") || !strcmp(argv[i], "-h")) opt->help_only = 1;
        else if (!strcmp(argv[i], "--device") && i + 1 < argc) opt->device_path = argv[++i];
        else if (!strcmp(argv[i], "--profile") && i + 1 < argc) opt->profile_name = argv[++i];
        else if (!strcmp(argv[i], "--identity") && i + 1 < argc) opt->identity_key = argv[++i];
        else if (!strcmp(argv[i], "--trigger-mode") && i + 1 < argc) opt->trigger_mode = argv[++i];
        else if (!strcmp(argv[i], "--config") && i + 1 < argc) opt->config_path = argv[++i];
        else if (!strcmp(argv[i], "--prefix") && i + 1 < argc) opt->prefix = argv[++i];
    }
}

int main(int argc, char **argv) {
    const profile_t *p;
    memset(&bridge, 0, sizeof(bridge));
    bridge.virtual_fd = -1;
    bridge.physical_fd = -1;
    bridge.lock_fd = -1;
    parse_options(argc, argv, &bridge.opt);

    bridge.identity = find_identity(bridge.opt.identity_key);
    if (!bridge.identity) {
        fprintf(stderr, "unknown identity: %s\n", bridge.opt.identity_key);
        return 1;
    }
    if (strcmp(bridge.opt.trigger_mode, "auto") &&
        strcmp(bridge.opt.trigger_mode, "analog") &&
        strcmp(bridge.opt.trigger_mode, "digital")) {
        fprintf(stderr, "unknown trigger mode: %s\n", bridge.opt.trigger_mode);
        return 1;
    }

    if (bridge.opt.help_only) { print_help(); return 0; }
    if (bridge.opt.list_only) { print_devices(); return 0; }

    snprintf(bridge.pid_path, sizeof(bridge.pid_path), "%s.pid", bridge.opt.prefix);
    snprintf(bridge.log_path, sizeof(bridge.log_path), "%s.log", bridge.opt.prefix);
    snprintf(bridge.status_path, sizeof(bridge.status_path), "%s.status", bridge.opt.prefix);

    if (bridge.opt.stop_only) return stop_running();

    p = find_profile(bridge.opt.profile_name);
    if (!p && strcmp(bridge.opt.profile_name, "auto")) {
        fprintf(stderr, "unknown profile: %s\n", bridge.opt.profile_name);
        return 1;
    }
    bridge.profile = p ? *p : *find_profile("generic");
    bridge.profile.name = bridge.opt.profile_name;

    if (bridge.opt.detach) {
        pid_t child = fork();
        int null_fd;
        if (child < 0) { fprintf(stderr, "fork failed: %s\n", strerror(errno)); return 1; }
        if (child > 0) { printf("bridge starting in background (pid %ld)\n", (long)child); return 0; }
        if (setsid() < 0) _exit(1);
        umask(0);
        signal(SIGHUP, SIG_IGN);
        null_fd = open("/dev/null", O_RDWR);
        bridge.log = fopen(bridge.log_path, "a");
        if (null_fd >= 0) {
            int log_fd = bridge.log ? fileno(bridge.log) : null_fd;
            dup2(null_fd, STDIN_FILENO);
            dup2(log_fd, STDOUT_FILENO);
            dup2(log_fd, STDERR_FILENO);
            if (null_fd > STDERR_FILENO) close(null_fd);
        }
        if (chdir("/") < 0) { /* non fatal */ }
    } else {
        bridge.log = fopen(bridge.log_path, "a");
    }

    signal(SIGTERM, on_signal);
    signal(SIGINT, on_signal);
    signal(SIGPIPE, SIG_IGN);

    if (acquire_singleton() < 0) return 1;
    if (bridge.opt.config_path) parse_config(bridge.opt.config_path);
    log_line("starting (identity=%s, profile=%s, triggers=%s%s%s, device=%s, grab=%d, pid=%ld)",
             bridge.opt.identity_key, bridge.opt.profile_name, bridge.opt.trigger_mode,
             bridge.opt.trigger_swap ? ",swap" : "",
             bridge.opt.trigger_invert ? ",invert" : "",
             bridge.opt.device_path ? bridge.opt.device_path : "auto",
             bridge.opt.grab, (long)getpid());
    write_status("starting");
    return run_bridge();
}

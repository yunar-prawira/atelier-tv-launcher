package com.yunar.atelier;

import android.app.Activity;
import android.app.WallpaperManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.text.Collator;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Atelier — a quiet, ad-free home screen for a TCL / Google TV set.
 * UI copy is English by request; the chat belongs to the owner, the screen does not.
 */
public class MainActivity extends Activity {

    private static final int INK = 0xFFF6F3EE;
    private static final int GOLD = 0xFFC8A96A;
    private static final int LABEL = 0xFFC6C1B9;
    private static final int MAX_ROW = 10;

    private static final String QUICK_PANEL = "com.tcl.suspension";
    private static final String QUICK_PANEL_ACTIVITY = "com.tcl.suspension.ui.main.InputActivity";
    private static final String QUICK_PANEL_ACTION = "com.tcl.suspension.VIEW_INPUTS";
    private static final String TV_SETTINGS = "com.android.tv.settings";
    private static final String TV_SETTINGS_ACTIVITY = "com.android.tv.settings.MainSettings";
    private static final String TCL_SETTINGS = "com.tcl.settings";
    private static final String TCL_PICTURE = "com.tcl.settings.PictureActivity";

    private TextView clock;
    private TextView dateline;
    private TextView greeting;
    private android.widget.HorizontalScrollView scroller;
    private View firstTile;
    private FrameLayout root;
    private List<String> order = new ArrayList<String>();
    private FrameLayout menu;
    private boolean editMode = false;
    private boolean moveMode = false;
    private int moveIndex = -1;
    /** Package being moved; the index is re-derived every key press so it can never go stale. */
    private String movePkg = null;
    /** A full-screen page (Applications / menu) shown instead of the home view. */
    private View page = null;
    private String menuPkg = null;
    private final List<View> tileViews = new ArrayList<View>();
    private final List<TextView> menuItems = new ArrayList<TextView>();
    private final List<Runnable> menuActions = new ArrayList<Runnable>();
    private int menuIndex = 0;
    private long menuOpenedAt = 0L;
    private long pageOpenedAt = 0L;
    private boolean lastInteractive = true;
    private boolean resumedNow = false;
    private String launchingPkg = null;
    private final List<Intent> pendingLaunches = new ArrayList<Intent>();
    private boolean arcPending = false;
    private int homeFailures = 0;

    private final Handler tick = new Handler();
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", Locale.US);
    private final SimpleDateFormat dateFmt =
            new SimpleDateFormat("EEEE, d MMMM yyyy", new Locale("id", "ID"));

    /** Mockup numbers are px on a 1920x1080 canvas; this maps them to the real panel. */
    private int px(float v) { return Math.round(v * getResources().getDisplayMetrics().widthPixels / 1920f); }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildHome();
        root.post(new Runnable() {
            @Override public void run() { maybeKickVpn(); }
        });
        // Control channel for the operator: rides the tailnet, so the set stays reachable even
        // when Android kills adbd during sleep. It lives in a foreground service because TCL's
        // TGuard sweeper kills this process regularly to make room for Play Store.
        startControlService();
        // The ARC re-arm is kicked from onStart (see below): it must run when HOME is shown, which
        // is the same moment the bar is usually asleep, and it must not depend on window focus.
    }

    /** Minimal screen that can never fail: clock plus a way into Applications. */
    private void safeHome() {
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF07080A);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(96), dp(96), dp(64), dp(48));
        final TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 104);
        t.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        t.setTextColor(INK);
        t.setText(timeFmt.format(new Date()));
        col.addView(t);
        TextView apps = new TextView(this);
        apps.setText("APPLICATIONS");
        apps.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        apps.setTextColor(GOLD);
        apps.setPadding(dp(20), dp(12), dp(20), dp(12));
        apps.setFocusable(true);
        apps.setFocusableInTouchMode(true);
        apps.setBackground(chipFrame());
        apps.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showAllApps(); }
        });
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-2, -2);
        alp.topMargin = dp(28);
        col.addView(apps, alp);
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        apps.requestFocus();
    }

    /**
     * Start Tailscale when it is not actually connected. The old version kicked it once per boot,
     * which meant a set that slept for hours came back with no tunnel and nothing to restore it.
     * The check is a live VPN-transport test, not a blind launch: if the tunnel is already up this
     * does nothing at all, so no window flashes on screen.
     */
    private void maybeKickVpn() {
        try {
            if (vpnUp()) { return; }
            Intent ts = getPackageManager().getLaunchIntentForPackage("com.tailscale.ipn");
            if (ts == null) { return; }
            ts.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(ts);
            alog("tailscale was down, kick sent");
            new Handler().postDelayed(new Runnable() {
                @Override public void run() {
                    if (resumedNow) { return; }   // still on our own home screen, nothing to undo
                    Intent home = new Intent(Intent.ACTION_MAIN);
                    home.addCategory(Intent.CATEGORY_HOME);
                    home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try { startActivity(home); } catch (Exception ignored) { }
                }
            }, 2600);
        } catch (Exception ignored) { }
    }

    /** True when any active network carries a VPN transport — i.e. the Tailscale tunnel is up. */
    private boolean vpnUp() {
        try {
            android.net.ConnectivityManager cm =
                    (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm == null) { return false; }
            for (android.net.Network n : cm.getAllNetworks()) {
                android.net.NetworkCapabilities c = cm.getNetworkCapabilities(n);
                if (c != null && c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) {
                    return true;
                }
            }
        } catch (Exception ignored) { }
        return false;
    }

    /** Idempotent: START_STICKY plus a plain startService() is enough to resurrect the channel. */
    private void startControlService() {
        try {
            startService(new Intent(this, ControlService.class));
        } catch (Exception e) {
            alog("control service start failed: " + e);
        }
    }

    /** Append to the app's own arc.log so evidence survives the logcat ring buffer rotating. */
    private void alog(String msg) {
        android.util.Log.i(TAG_AUDIO, msg);
        try {
            File dir = getExternalFilesDir(null);
            if (dir == null) { return; }
            java.io.FileWriter w = new java.io.FileWriter(new File(dir, "arc.log"), true);
            w.write(stamp() + " " + msg + "\n");
            w.flush();
            w.close();
        } catch (Exception ignored) { }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (menu != null) {
            android.util.Log.i("ATELIER_MENU", "menu survived onNewIntent");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Coming back from an app or the uninstall dialog: never leave a stale page on screen.
        if (menu != null) { closeMenu(); }
        else if (page != null) { closePage(); }
        watchWake();
        resumedNow = true;
        if (rowLooksStale()) { buildHome(); }
    }

    @Override
    protected void onPause() {
        resumedNow = false;
        super.onPause();
    }

    /** Rebuild only when an entry vanished or a new one appeared; nothing is written here. */
    private boolean rowLooksStale() {
        if (order == null || order.isEmpty()) { return false; }
        for (String p : order) {
            try { getPackageManager().getApplicationInfo(p, 0); }
            catch (Exception gone) { return true; }
        }
        return false;
    }

    // ------------------------------------------------------------- HDMI ARC audio
    //
    // Verified on this set (TCL 43A30 + Sony HT-S100F): when the bar sits in CEC standby the TV
    // keeps the route pinned at `hmdi_arc` with mArcEstablished=false, so there is no fallback to
    // the TV speakers and the room just goes quiet. TCL never re-negotiates by itself, and it even
    // answers the bar's <Initiate ARC> with <Feature Abort: refused>.
    //
    // Two hard-won rules:
    //  1. Only intervene when the link is actually down. A blind toggle tears down a working
    //     handshake and leaves the room silent — that is the bug v1.3 shipped.
    //  2. The off->on gap must be ~15 s, and it must cycle `hdmi_control_enabled` (the whole CEC
    //     controller), not `hdmi_system_audio_control_enabled`. Measured on the device: 2.5 s and
    //     6 s gaps never re-established ARC; 15 s did, on the first try.

    private static final String TAG_AUDIO = "ATELIER_AUDIO";
    private static final long ARC_GAP_MS = 15000L;
    private static final long ARC_COOLDOWN_MS = 5 * 60 * 1000L;
    private long arcLastAttempt = 0L;

    private static String stamp() {
        return new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(new Date());
    }

    /** persist.tcl.isARCConnect reads 1 when the vendor HAL has the ARC channel up. */
    private String arcProp() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Object v = sp.getMethod("get", String.class).invoke(null, "persist.tcl.isARCConnect");
            return v == null ? "" : v.toString();
        } catch (Throwable t) { return ""; }
    }

    private boolean arcHealthy() { return "1".equals(arcProp()); }

    private void audioRehandshake(String why) { audioRehandshake(why, 0); }

    private void audioRehandshake(final String why, final int attempt) {
        if (arcPending) { return; }
        if (attempt == 0 && System.currentTimeMillis() - arcLastAttempt < ARC_COOLDOWN_MS) { return; }
        if (arcHealthy()) {
            alog(why + ": ARC already up, nothing to do");
            return;
        }
        arcPending = true;
        arcLastAttempt = System.currentTimeMillis();
        try {
            android.provider.Settings.Global.putInt(
                    getContentResolver(), "hdmi_control_enabled", 0);
            alog(why + ": CEC off (attempt " + attempt + ")");
        } catch (Exception denied) {
            arcPending = false;
            alog(why + ": skipped, no WRITE_SECURE_SETTINGS");
            return;
        }
        new Handler().postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    android.provider.Settings.Global.putInt(
                            getContentResolver(), "hdmi_control_enabled", 1);
                    alog(why + ": CEC on");
                } catch (Exception ignored) { }
                arcPending = false;
                new Handler().postDelayed(new Runnable() {
                    @Override public void run() {
                        String p = arcProp();
                        alog(why + ": arc=" + p);
                        if (!"1".equals(p) && attempt < 1) {
                            alog("retrying once");
                            audioRehandshake("retry", attempt + 1);
                        }
                    }
                }, 12000);
            }
        }, ARC_GAP_MS);
    }

    /** If a previous toggle was interrupted, the global can be left at 0 — put it back. */
    private void ensureArcEnabled() {
        try {
            int cec = android.provider.Settings.Global.getInt(
                    getContentResolver(), "hdmi_control_enabled", 1);
            if (cec == 0 && !arcPending) {
                android.provider.Settings.Global.putInt(
                        getContentResolver(), "hdmi_control_enabled", 1);
                alog("restored hdmi_control_enabled=1");
            }
            int v = android.provider.Settings.Global.getInt(
                    getContentResolver(), "hdmi_system_audio_control_enabled", 1);
            if (v == 0 && !arcPending) {
                android.provider.Settings.Global.putInt(
                        getContentResolver(), "hdmi_system_audio_control_enabled", 1);
                alog("restored hdmi_system_audio_control_enabled=1");
            }
        } catch (Exception ignored) { }
    }

    /**
     * Fire the handshake when the screen comes back on, which is when the soundbar is asleep.
     * No window-focus gate: on this set the owner power-ons straight into Netflix, so a check
     * that only runs while HOME has focus never sees the wake edge at all (that is why v1.3's
     * ATELIER_AUDIO log was always empty).
     */
    private void watchWake() {
        boolean interactive;
        try {
            interactive = ((android.os.PowerManager) getSystemService(POWER_SERVICE)).isInteractive();
        } catch (Exception e) { return; }
        boolean edge = interactive && !lastInteractive;
        lastInteractive = interactive;
        if (edge) { audioRehandshake("wake"); }
    }

    // ------------------------------------------------------------- full-screen pages

    /**
     * Show a page as the activity's content instead of stacking an overlay on top of the
     * home view. On this firmware a sibling overlay never really holds D-pad focus, so OK
     * leaks to the tile behind it; with the tiles out of the view tree that cannot happen.
     */
    private void openPage(View v) {
        page = v;
        pageOpenedAt = System.currentTimeMillis();
        setContentView(v);
        v.post(new Runnable() {
            @Override public void run() { focusFirstIn(getWindow().getDecorView()); }
        });
    }

    /** True while a page was just opened: swallows the key-up that opened it. */
    private boolean pageJustOpened() {
        return System.currentTimeMillis() - pageOpenedAt < 600L;
    }

    private void closePage() {
        page = null;
        if (root == null) { return; }
        setContentView(root);
        root.post(new Runnable() { @Override public void run() { restoreFocus(); } });
    }

    private void restoreFocus() {
        if (moveMode) {
            int mi = movePkg == null ? -1 : order.indexOf(movePkg);
            if (mi >= 0 && mi < tileViews.size()) { tileViews.get(mi).requestFocus(); return; }
        }
        if (firstTile != null) { firstTile.requestFocus(); }
    }

    private void focusFirstIn(View v) {
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                if (c.getVisibility() == View.VISIBLE && c.requestFocus()) { return; }
                focusFirstIn(c);
                if (getCurrentFocus() != null) { return; }
            }
        }
    }

    // ------------------------------------------------------------- home screen

    /** Never let a render failure kill HOME: always fall back to the minimal screen. */
    private void buildHome() {
        try {
            buildHomeInternal();
            homeFailures = 0;
        } catch (Throwable broken) {
            homeFailures++;
            android.util.Log.e("ATELIER_HOME", "buildHome failed (" + homeFailures + ")", broken);
            try { safeHome(); }
            catch (Throwable fatal) { android.util.Log.e("ATELIER_HOME", "safeHome failed", fatal); }
        }
    }

    private void buildHomeInternal() {
        page = null;
        menu = null;
        pageOpenedAt = System.currentTimeMillis();   // swallow the key-up that triggered this rebuild
        order = reconcile(readOrder());
        tileViews.clear();

        root = new FrameLayout(this);
        root.setClipChildren(false);
        root.addView(new Stage(), new FrameLayout.LayoutParams(-1, -1));

        // ---- header (mockup D): greeting / clock / hairline / date, right aligned ----
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setGravity(Gravity.END);
        header.setClipChildren(false);

        greeting = new TextView(this);
        greeting.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(16));
        greeting.setTextColor(GOLD);
        greeting.setLetterSpacing(0.30f);
        greeting.setIncludeFontPadding(false);
        header.addView(greeting);

        clock = new TextView(this);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(196));
        clock.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        clock.setTextColor(INK);
        clock.setIncludeFontPadding(false);
        clock.setLetterSpacing(-0.01f);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
        clp.topMargin = px(18);
        header.addView(clock, clp);

        View rule = new View(this);
        rule.setBackgroundColor(0x80C8A96A);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(px(96), Math.max(1, px(1)));
        rlp.topMargin = px(26);
        rlp.gravity = Gravity.END;
        header.addView(rule, rlp);

        dateline = new TextView(this);
        dateline.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(19));
        dateline.setTextColor(INK);
        dateline.setAlpha(0.9f);
        dateline.setLetterSpacing(0.30f);
        dateline.setIncludeFontPadding(false);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-2, -2);
        dlp.topMargin = px(16);
        header.addView(dateline, dlp);

        FrameLayout.LayoutParams headerLp = new FrameLayout.LayoutParams(-2, -2);
        headerLp.gravity = Gravity.TOP | Gravity.END;
        headerLp.setMargins(0, px(66), px(120), 0);
        root.addView(header, headerLp);

        // ---- app row (mockup D): glass cards, big, ALL APPS on the far right ----
        // Six tiles visible; the rest (including ALL APPS) appear as the D-pad scrolls.
        final android.widget.HorizontalScrollView scroller = new android.widget.HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setClipChildren(false);
        scroller.setFocusable(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.BOTTOM);
        row.setClipChildren(false);
        row.setPadding(px(128), 0, px(120), 0);
        scroller.addView(row, new android.widget.FrameLayout.LayoutParams(-2, -1));
        FrameLayout.LayoutParams rowLp = new FrameLayout.LayoutParams(-1, px(170));
        rowLp.gravity = Gravity.BOTTOM;
        rowLp.bottomMargin = px(150);
        root.addView(scroller, rowLp);
        this.scroller = scroller;

        PackageManager pm = getPackageManager();
        // Cards adapt to however many apps the row actually holds, so ALL APPS can never be
        // pushed off-screen: 9 apps + ALL APPS = 10 slots inside a 1672 px track.
        int track = getResources().getDisplayMetrics().widthPixels - px(128) - px(120);
        int gap = px(22);
        // exactly six tiles fill the viewport; the row scrolls for the remainder
        int cardW = (track - gap * 5) / 6;
        boolean showLabels = true;
        firstTile = null;
        int shown = 0;
        for (int i = 0; i < order.size() && shown < MAX_ROW; i++) {
            final String pkg = order.get(i);
            final int index = i;
            Drawable icon;
            String label;
            try {
                icon = pm.getApplicationIcon(pkg);
                label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString().toUpperCase(Locale.US);
            } catch (Exception missing) { continue; }
            View card = appCard(label, icon, showLabels, new Runnable() {
                @Override public void run() {
                    if (editMode) { showMenu(pkg, index, false); } else { launchPackage(pkg); }
                }
            }, new Runnable() {
                @Override public void run() { showMenu(pkg, index, false); }
            });
            row.addView(card, cardLp(cardW));
            tileViews.add(card);
            if (firstTile == null) { firstTile = card; }
            shown++;
        }

        if (shown == 0) {
            TextView empty = new TextView(this);
            empty.setText("No apps yet — ALL APPS, then hold OK on an app");
            empty.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(17));
            empty.setTextColor(0xFFE2DDD5);
            empty.setPadding(px(24), px(20), px(24), px(20));
            empty.setFocusable(true);
            empty.setFocusableInTouchMode(true);
            empty.setBackground(cardFrame());
            empty.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { showAllApps(); }
            });
            LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(px(420), px(150));
            elp.rightMargin = px(22);
            row.addView(empty, elp);
            firstTile = empty;
            tileViews.add(empty);
        }

        View allApps = allAppsCard(showLabels);
        row.addView(allApps, cardLp(cardW));

        TextView mark = new TextView(this);
        mark.setText(readTitle());
        mark.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(15));
        mark.setTextColor(0xB3F6F3EE);
        mark.setLetterSpacing(0.62f);
        FrameLayout.LayoutParams mlp = new FrameLayout.LayoutParams(-2, -2);
        mlp.gravity = Gravity.TOP | Gravity.START;
        mlp.setMargins(px(120), px(96), 0, 0);
        root.addView(mark, mlp);

        // ---- dock (mockup D): one translucent pill, centred ----
        LinearLayout dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setGravity(Gravity.CENTER_VERTICAL);
        dock.setBackground(null);
        dock.setPadding(0, 0, 0, 0);
        dock.addView(dockItem("INPUT", new Runnable() {
            @Override public void run() { openQuickPanel(); }
        }));
        dock.addView(dockItem("APPLICATIONS", new Runnable() {
            @Override public void run() { showAllApps(); }
        }));
        dock.addView(dockItem("SETTINGS", new Runnable() {
            @Override public void run() { openComponent(TV_SETTINGS, TV_SETTINGS_ACTIVITY); }
        }));
        dock.addView(dockItem("SETUP", new Runnable() {
            @Override public void run() { showSetup(); }
        }));
        dock.addView(dockItem(editMode ? "DONE" : "EDIT", new Runnable() {
            @Override public void run() { editMode = !editMode; buildHome(); }
        }));

        FrameLayout.LayoutParams dockLp = new FrameLayout.LayoutParams(-2, px(58));
        dockLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        dockLp.bottomMargin = px(48);
        if (moveMode) {
            dock.setDescendantFocusability(android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            dock.setAlpha(0.45f);
        }
        root.addView(dock, dockLp);

        if (moveMode) {
            TextView hint = new TextView(this);
            hint.setText("Left / Right to move     OK to drop     BACK to cancel");
            hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            hint.setTextColor(GOLD);
            hint.setLetterSpacing(0.18f);
            FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(-2, -2);
            hlp.gravity = Gravity.BOTTOM | Gravity.START;
            hlp.setMargins(dp(96), 0, 0, dp(52));
            root.addView(hint, hlp);
        }

        setContentView(root);
        paintTime();

        scheduleTick();

        root.post(new Runnable() {
            @Override public void run() {
                if (moveMode) {
                    int mi = movePkg == null ? -1 : order.indexOf(movePkg);
                    if (mi >= 0 && mi < tileViews.size()) { tileViews.get(mi).requestFocus(); return; }
                }
                if (firstTile != null) { firstTile.requestFocus(); }
            }
        });
    }

    /** Clock + wake watch run only while the home screen is actually visible. */
    private void scheduleTick() {
        tick.removeCallbacksAndMessages(null);
        if (clock == null) { return; }
        tick.postDelayed(new Runnable() {
            @Override public void run() {
                paintTime();
                watchWake();
                // Cheap and idempotent: brings the channel back if the service was ever killed.
                startControlService();
                tick.postDelayed(this, 20000);
            }
        }, 20000);
    }

    @Override protected void onStart() {
        super.onStart();
        scheduleTick();
        ensureArcEnabled();
        // Coming home is the moment the bar is usually asleep. Give the CEC stack a few seconds
        // to settle first, and let the cooldown in audioRehandshake() stop this from firing on
        // every back-navigation.
        new Handler().postDelayed(new Runnable() {
            @Override public void run() { audioRehandshake("home"); }
        }, 8000);
    }

    @Override protected void onStop() {
        tick.removeCallbacksAndMessages(null);
        super.onStop();
    }

    private void paintTime() {
        Date now = new Date();
        clock.setText(timeFmt.format(now));                     // HH:mm — 24 hour, always
        if (greeting != null) {
            int h = Integer.parseInt(new java.text.SimpleDateFormat("H", Locale.US).format(now));
            greeting.setText(h < 5 ? "GOOD NIGHT"
                    : h < 11 ? "GOOD MORNING"
                    : h < 17 ? "GOOD AFTERNOON"
                    : h < 21 ? "GOOD EVENING"
                    : "GOOD NIGHT");
        }
        dateline.setText(dateFmt.format(now).toUpperCase(Locale.US));
    }

    // ------------------------------------------------------------- app list io

    private File homeDir() { return getExternalFilesDir(null); }

    private List<String> readOrder() {
        List<String> out = new ArrayList<String>();
        File f = homeDir() == null ? null : new File(homeDir(), "apps.txt");
        boolean present = f != null && f.exists();
        if (present) {
            BufferedReader r = null;
            try {
                r = new BufferedReader(new FileReader(f));
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.length() > 0 && !line.startsWith("#")) { out.add(line); }
                }
            } catch (Exception ignored) {
            } finally { if (r != null) { try { r.close(); } catch (Exception ignored) { } } }
        }
        // Missing file = first run, use the list compiled into this build. An existing but empty
        // file means the owner removed everything: keep it empty instead of resurrecting the default.
        if (!present) { for (String p : Apps.LIST) { out.add(p); } }
        return out;
    }

    /**
     * Drop entries that no longer exist on the device (uninstalled, or a package that never
     * resolved) so a stale row can never shift the tile indices under the remote.
     */
    private List<String> reconcile(List<String> list) {
        // Display filter only. PackageManager can be mid-scan at boot and external storage may not
        // be mounted yet, so a missing entry here is NOT proof of an uninstall — never write it away.
        List<String> kept = new ArrayList<String>();
        for (String pkg : list) {
            try { getPackageManager().getApplicationInfo(pkg, 0); kept.add(pkg); }
            catch (Exception notYet) { android.util.Log.i("ATELIER_HOME", "not resolvable now: " + pkg); }
        }
        return kept;
    }

    private void writeOrder(List<String> list) {
        File dir = homeDir();
        if (dir == null) { return; }
        File f = new File(dir, "apps.txt");
        File tmp = new File(dir, "apps.txt.tmp");
        java.io.FileOutputStream fos = null;
        try {
            fos = new java.io.FileOutputStream(tmp, false);
            java.io.OutputStreamWriter w = new java.io.OutputStreamWriter(fos, "UTF-8");
            for (String p : list) { w.write(p); w.write("\n"); }
            w.flush();
            fos.getFD().sync();          // survive a power cut on a TV that is switched off at the wall
            w.close();
            fos = null;
            if (f.exists()) {
                File bak = new File(dir, "apps.txt.bak");
                if (bak.exists() && !bak.delete()) { android.util.Log.i("ATELIER_HOME", "bak not replaceable"); }
                f.renameTo(bak);
            }
            if (!tmp.renameTo(f)) {
                android.util.Log.e("ATELIER_HOME", "could not promote apps.txt.tmp");
            }
        } catch (Exception e) {
            android.util.Log.e("ATELIER_HOME", "writeOrder failed", e);
        } finally { if (fos != null) { try { fos.close(); } catch (Exception ignored) { } } }
    }

    private String readTitle() {
        File f = homeDir() == null ? null : new File(homeDir(), "title.txt");
        if (f != null && f.exists()) {
            BufferedReader r = null;
            try {
                r = new BufferedReader(new FileReader(f));
                String line = r.readLine();
                if (line != null && line.trim().length() > 0) { return line.trim().toUpperCase(Locale.US); }
            } catch (Exception ignored) {
            } finally { if (r != null) { try { r.close(); } catch (Exception ignored) { } } }
        }
        return "ATELIER";
    }

    /**
     * Every way this package could be started, best first. On a TV set the LEANBACK entry point is
     * the real one; getLaunchIntentForPackage can hand back a stub that closes again instantly,
     * which looks exactly like a dead tile (Netflix / YouTube / Prime Video on TCL do this).
     */
    private List<Intent> launchCandidates(String pkg) {
        PackageManager pm = getPackageManager();
        List<Intent> out = new ArrayList<Intent>();
        Intent[] probes = new Intent[]{
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER).setPackage(pkg),
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg)};
        for (Intent probe : probes) {
            List<ResolveInfo> found = pm.queryIntentActivities(probe, 0);
            if (found == null) { continue; }
            for (ResolveInfo ri : found) {
                if (ri.activityInfo == null || !pkg.equals(ri.activityInfo.packageName)) { continue; }
                Intent i = new Intent(Intent.ACTION_MAIN);
                i.setClassName(pkg, ri.activityInfo.name);
                addCandidate(out, i);
            }
        }
        Intent plain = pm.getLaunchIntentForPackage(pkg);
        if (plain != null) { addCandidate(out, plain); }
        return out;
    }

    private void addCandidate(List<Intent> out, Intent candidate) {
        String target = candidate.getComponent() == null ? candidate.toUri(0) : candidate.getComponent().flattenToShortString();
        for (Intent existing : out) {
            String seen = existing.getComponent() == null ? existing.toUri(0) : existing.getComponent().flattenToShortString();
            if (seen.equals(target)) { return; }
        }
        out.add(candidate);
    }

    /** First candidate, used for validation (e.g. "is this package launchable at all"). */
    private Intent resolveLaunch(String pkg) {
        List<Intent> all = launchCandidates(pkg);
        return all.isEmpty() ? null : all.get(0);
    }

    private List<String[]> allLaunchable() {
        PackageManager pm = getPackageManager();
        List<ResolveInfo> list = new ArrayList<ResolveInfo>();
        Intent a = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        list.addAll(pm.queryIntentActivities(a, 0));
        Intent b = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(b, 0)) {
            boolean dup = false;
            for (ResolveInfo x : list) {
                if (x.activityInfo.packageName.equals(ri.activityInfo.packageName)) { dup = true; break; }
            }
            if (!dup) { list.add(ri); }
        }
        List<String[]> rows = new ArrayList<String[]>();
        for (ResolveInfo ri : list) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName())) { continue; }
            String label;
            try { label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString(); }
            catch (Exception e) { label = pkg; }
            rows.add(new String[]{pkg, label});
        }
        final Collator col = Collator.getInstance(new Locale("id", "ID"));
        Collections.sort(rows, new Comparator<String[]>() {
            @Override public int compare(String[] x, String[] y) { return col.compare(x[1], y[1]); }
        });
        return rows;
    }

    // ------------------------------------------------------------- applications

    private void showAllApps() {
        final FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0xFF07080A);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(96), dp(68), dp(96), dp(48));

        TextView title = new TextView(this);
        title.setText("APPLICATIONS");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        title.setTextColor(GOLD);
        title.setLetterSpacing(0.36f);
        panel.addView(title);

        View rule = new View(this);
        rule.setBackgroundColor(0x40C8A96A);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, dp(1));
        rlp.topMargin = dp(16);
        panel.addView(rule, rlp);

        ScrollView scroll = new ScrollView(this);
        LinearLayout.LayoutParams scp = new LinearLayout.LayoutParams(-1, -1);
        scp.topMargin = dp(22);
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(grid);

        PackageManager pm = getPackageManager();
        List<String[]> apps = allLaunchable();
        LinearLayout rowLayout = null;
        int perRow = 6;
        for (int i = 0; i < apps.size(); i++) {
            if (i % perRow == 0) {
                rowLayout = new LinearLayout(this);
                rowLayout.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-2, -2);
                rp.bottomMargin = dp(16);
                grid.addView(rowLayout, rp);
            }
            final String pkg = apps.get(i)[0];
            Drawable icon;
            try { icon = pm.getApplicationIcon(pkg); } catch (Exception e) { continue; }
            View tile = tile(icon, apps.get(i)[1], true, new Runnable() {
                @Override public void run() {
                    closePage();
                    launchPackage(pkg);
                }
            }, new Runnable() {
                @Override public void run() {
                    android.util.Log.i("ATELIER_MENU", "drawer long-press " + pkg);
                    showMenu(pkg, -1, true);
                }
            });
            rowLayout.addView(tile);
        }
        panel.addView(scroll, scp);

        TextView hint = new TextView(this);
        hint.setText("Hold OK on an app to add it to the home row");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        hint.setTextColor(0x66F6F3EE);
        hint.setLetterSpacing(0.14f);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-2, -2);
        hlp.topMargin = dp(12);
        panel.addView(hint, hlp);

        overlay.addView(panel, new FrameLayout.LayoutParams(-1, -1));
        overlay.setFocusableInTouchMode(true);
        openPage(overlay);
    }

    // ------------------------------------------------------------- long-press menu

    private void showMenu(final String pkg, final int index, final boolean inDrawer) {
        android.util.Log.i("ATELIER_MENU", "open " + pkg + " index=" + index + " drawer=" + inDrawer);
        menuPkg = pkg;

        menuItems.clear();
        menuActions.clear();
        menu = new FrameLayout(this);
        menu.setBackgroundColor(0xF5080809);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(40), dp(32), dp(40), dp(32));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF121216);
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), 0x40C8A96A);
        box.setBackground(bg);

        String label;
        try { label = getPackageManager().getApplicationLabel(
                getPackageManager().getApplicationInfo(pkg, 0)).toString(); }
        catch (Exception e) { label = pkg; }

        TextView title = new TextView(this);
        title.setText(label);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTextColor(INK);
        title.setLetterSpacing(0.06f);
        box.addView(title);

        View rule = new View(this);
        rule.setBackgroundColor(0x33C8A96A);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, dp(1));
        rlp.topMargin = dp(14);
        rlp.bottomMargin = dp(6);
        box.addView(rule, rlp);

        final List<Runnable> actions = new ArrayList<Runnable>();
        final List<String> names = new ArrayList<String>();

        if (inDrawer) {
            if (order.size() >= MAX_ROW && order.indexOf(pkg) < 0) {
                names.add("Row is full (max " + MAX_ROW + ")"); actions.add(new Runnable() { public void run() {
                    closeMenu();
                }});
            } else {
                names.add("Add to home"); actions.add(new Runnable() { public void run() {
                    List<String> list = readOrder();
                    if (!list.contains(pkg)) { list.add(pkg); writeOrder(list); }
                    closeMenu(); buildHome();
                }});
            }
        } else {
            names.add("Move"); actions.add(new Runnable() { public void run() {
                moveMode = true; movePkg = pkg; moveIndex = -1; buildHome();
            }});
            names.add("Remove from home"); actions.add(new Runnable() { public void run() {
                List<String> list = readOrder();
                list.remove(pkg);
                writeOrder(list);
                closeMenu(); buildHome();
            }});
        }
        names.add("Uninstall"); actions.add(new Runnable() { public void run() {
            closeMenu();
            uninstall(pkg);
        }});
        names.add("Cancel"); actions.add(new Runnable() { public void run() { closeMenu(); }});

        for (int i = 0; i < names.size(); i++) {
            final int which = i;
            TextView item = new TextView(this);
            item.setText(names.get(i));
            item.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            item.setTextColor(new android.content.res.ColorStateList(
                    new int[][]{new int[]{android.R.attr.state_focused}, new int[]{}},
                    new int[]{0xFF0B0B0D, 0xFFE2DDD5}));
            item.setPadding(dp(18), dp(12), dp(18), dp(12));
            item.setFocusable(false);
            item.setBackground(menuBg(false));
            item.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (System.currentTimeMillis() - menuOpenedAt < 400) {
                        android.util.Log.i("ATELIER_MENU", "swallowed stray click");
                        return;
                    }
                    actions.get(which).run();
                }
            });
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(-1, -2);
            ilp.topMargin = dp(8);
            box.addView(item, ilp);
            menuItems.add(item);
        }

        menu.addView(box, new FrameLayout.LayoutParams(dp(440), -2) {{ gravity = Gravity.CENTER; }});
        // full-screen page: the home tiles are no longer in the view tree, so no key can leak to them
        menu.setFocusable(true);
        menu.setFocusableInTouchMode(true);
        menuActions.addAll(actions);
        menuIndex = 0;
        menuOpenedAt = System.currentTimeMillis();
        openPage(menu);
        paintMenu();
    }

    private void paintMenu() {
        for (int i = 0; i < menuItems.size(); i++) {
            boolean sel = (i == menuIndex);
            menuItems.get(i).setBackground(menuBg(sel));
            menuItems.get(i).setTextColor(sel ? 0xFF0B0B0D : 0xFFE2DDD5);
        }
    }

    private GradientDrawable menuBg(boolean selected) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(14));
        if (selected) {
            d.setColor(GOLD);
            d.setStroke(dp(1), GOLD);
        } else {
            d.setColor(0x14FFFFFF);
            d.setStroke(dp(1), 0x33FFFFFF);
        }
        return d;
    }

    /** Uninstall where the firmware supports it, otherwise open the app's system page. */
    private void uninstall(String pkg) {
        Uri uri = Uri.parse("package:" + pkg);
        Intent del = new Intent(Intent.ACTION_DELETE, uri);
        del.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (del.resolveActivity(getPackageManager()) != null) {
            try { startActivity(del); return; } catch (Exception ignored) { }
        }
        Intent details = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri);
        details.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(details); } catch (Exception ignored) { }
    }

    private void closeMenu() {
        menu = null;
        menuItems.clear();
        menuActions.clear();
        menuIndex = 0;
        closePage();
    }

    // ------------------------------------------------------------- setup page

    /** Everything the owner can change without a PC: wordmark, adding by package, resetting. */
    private void showSetup() {
        FrameLayout page = new FrameLayout(this);
        page.setBackgroundColor(0xF5080809);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(96), dp(68), dp(96), dp(48));

        TextView head = new TextView(this);
        head.setText("SETUP");
        head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        head.setTextColor(GOLD);
        head.setLetterSpacing(0.36f);
        col.addView(head);

        View rule = new View(this);
        rule.setBackgroundColor(0x40C8A96A);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, dp(1));
        rlp.topMargin = dp(16);
        rlp.bottomMargin = dp(26);
        col.addView(rule, rlp);

        col.addView(setupRow("Wordmark: " + readTitle(), new Runnable() {
            @Override public void run() {
                askText("Wordmark", readTitle(), new TextSink() {
                    @Override public void accept(String value) {
                        if (value.trim().length() > 0) { writeTitle(value.trim()); }
                        showSetup();
                    }
                });
            }
        }));

        col.addView(setupRow("Add app by package name", new Runnable() {
            @Override public void run() {
                askText("Package name (e.g. com.netflix.ninja)", "", new TextSink() {
                    @Override public void accept(String value) {
                        addByPackage(value.trim());
                        showSetup();
                    }
                });
            }
        }));

        col.addView(setupRow("Reset to the apps compiled into this build", new Runnable() {
            @Override public void run() {
                new android.app.AlertDialog.Builder(MainActivity.this)
                        .setTitle("Replace the row?")
                        .setMessage("The row will be replaced with the " + Apps.LIST.length
                                + " apps compiled into this build. Your current row is kept as apps.txt.bak.")
                        .setPositiveButton("Replace", new android.content.DialogInterface.OnClickListener() {
                            @Override public void onClick(android.content.DialogInterface d, int w) {
                                List<String> base = new ArrayList<String>();
                                for (String p : Apps.LIST) { base.add(p); }
                                writeOrder(base);
                                buildHome();
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
        }));

        col.addView(setupRow("Close", new Runnable() {
            @Override public void run() { closePage(); }
        }));

        TextView hint = new TextView(this);
        hint.setText("Applications lists every launchable app. Row holds " + MAX_ROW + ".");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        hint.setTextColor(0x66F6F3EE);
        hint.setLetterSpacing(0.14f);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-2, -2);
        hlp.topMargin = dp(22);
        col.addView(hint, hlp);

        page.addView(col, new FrameLayout.LayoutParams(-1, -1));
        openPage(page);
    }

    private View setupRow(String text, final Runnable action) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        v.setTextColor(0xFFE2DDD5);
        v.setPadding(dp(22), dp(14), dp(22), dp(14));
        v.setFocusable(true);
        v.setFocusableInTouchMode(true);
        v.setBackground(chipFrame());
        v.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View x) { action.run(); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = dp(10);
        v.setLayoutParams(lp);
        return v;
    }

    private interface TextSink { void accept(String value); }

    /** Small dialog with a text field; the TV's own keyboard handles typing. */
    private void askText(String label, String initial, final TextSink sink) {
        final android.widget.EditText field = new android.widget.EditText(this);
        field.setText(initial);
        field.setSelectAllOnFocus(true);
        field.setSingleLine(true);
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        new android.app.AlertDialog.Builder(this)
                .setTitle(label)
                .setView(field)
                .setPositiveButton("Save", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        sink.accept(field.getText().toString());
                    }
                })
                .setNegativeButton("Cancel", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) { showSetup(); }
                })
                .show();
    }

    private void writeTitle(String value) {
        File f = homeDir() == null ? null : new File(homeDir(), "title.txt");
        if (f == null) { return; }
        FileWriter w = null;
        try { w = new FileWriter(f, false); w.write(value); }
        catch (Exception ignored) { }
        finally { if (w != null) { try { w.close(); } catch (Exception ignored) { } } }
    }

    private void addByPackage(String pkg) {
        if (pkg.length() == 0) { return; }
        try { getPackageManager().getApplicationInfo(pkg, 0); }
        catch (Exception missing) { toast("No such package: " + pkg); return; }
        if (resolveLaunch(pkg) == null) { toast("That package has no launchable screen"); return; }
        List<String> list = readOrder();
        if (list.contains(pkg)) { toast("Already on the row"); return; }
        if (list.size() >= MAX_ROW) { toast("Row is full (max " + MAX_ROW + ")"); return; }
        list.add(pkg);
        writeOrder(list);
        buildHome();
        toast("Added " + pkg);
    }

    private void toast(String text) {
        try { android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show(); }
        catch (Exception ignored) { }
    }

    // ------------------------------------------------------------- launching

    private void launchPackage(String pkg) {
        List<Intent> candidates = launchCandidates(pkg);
        if (candidates.isEmpty()) {
            toast("No launchable screen in " + pkg);
            android.util.Log.i("ATELIER_LAUNCH", "nothing to launch for " + pkg);
            return;
        }
        pendingLaunches.clear();
        pendingLaunches.addAll(candidates);
        launchingPkg = pkg;
        tryLaunch(0);
    }

    /**
     * Try each entry point until one actually takes over. If we are still the resumed activity
     * ~1.6 s later, that candidate silently closed itself and the next one gets a turn.
     */
    private void tryLaunch(final int index) {
        if (index >= pendingLaunches.size()) {
            android.util.Log.i("ATELIER_LAUNCH", "no entry point worked for " + launchingPkg);
            if (launchingPkg != null) { toast("Could not open " + launchingPkg); }
            launchingPkg = null;
            return;
        }
        Intent i = pendingLaunches.get(index);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
            android.util.Log.i("ATELIER_LAUNCH", (i.getComponent() == null ? i.toUri(0)
                    : i.getComponent().flattenToShortString()) + " -> " + launchingPkg);
        } catch (Exception refused) {
            tryLaunch(index + 1);
            return;
        }
        new Handler().postDelayed(new Runnable() {
            @Override public void run() {
                if (launchingPkg == null) { return; }        // already opened
                if (!resumedNow) { launchingPkg = null; return; }   // something took over: success
                tryLaunch(index + 1);                        // still us: that candidate bounced
            }
        }, 1600);
    }

    private void openQuickPanel() {
        Intent i = new Intent(QUICK_PANEL_ACTION);
        i.setClassName(QUICK_PANEL, QUICK_PANEL_ACTIVITY);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(i); return; } catch (Exception ignored) { }
        openComponent(TV_SETTINGS, TV_SETTINGS_ACTIVITY);
    }

    private void openPicture() {
        // TCL's PictureActivity exists but destroys itself when started from outside
        // its own Settings flow, so the reliable landing is the Display & Sound page.
        Intent i = new Intent("android.picture");
        i.setClassName(TCL_SETTINGS, TCL_PICTURE);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(i); } catch (Exception e) {
            openComponent(TV_SETTINGS, TV_SETTINGS_ACTIVITY);
        }
    }

    private void openSound() {
        openComponent(TV_SETTINGS, TV_SETTINGS_ACTIVITY);
    }

    private void openComponent(String pkg, String cls) {
        Intent i = new Intent();
        i.setClassName(pkg, cls);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(i); return; } catch (Exception ignored) { }
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        } catch (Exception ignored) { }
        toast("Settings could not be opened on this firmware");
    }

    // ------------------------------------------------------------- views

    private View tile(Drawable icon, String label, boolean small, final Runnable onClick,
                      final Runnable onLongClick) {
        TileBox box = new TileBox(this);
        box.setLongAction(onLongClick);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(6), dp(6), dp(6), dp(6));
        box.setFocusable(true);
        box.setFocusableInTouchMode(true);
        box.setBackground(focusFrame());

        final ImageView img = new ImageView(this);
        img.setImageDrawable(icon);
        img.setAlpha(0.86f);
        int size = small ? dp(48) : dp(52);
        box.addView(img, new LinearLayout.LayoutParams(size, size));

        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tv.setTextColor(LABEL);
        tv.setGravity(Gravity.CENTER);
        tv.setMaxLines(1);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tv.setLetterSpacing(0.04f);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.topMargin = dp(10);
        box.addView(tv, tlp);

        box.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onClick.run(); }
        });
        box.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { onLongClick.run(); return true; }
        });
        box.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean focused) {
                img.animate().alpha(focused ? 1f : 0.86f).setDuration(120).start();
            }
        });
        if (small) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(124), -2);
            box.setLayoutParams(lp);
        }
        return box;
    }

    /** Glass card that holds one app: plate + label, mockup D geometry (220x116 @1920 canvas). */
    private View appCard(String label, Drawable icon, boolean withLabel,
                         final Runnable onClick, final Runnable onLong) {
        TileBox box = new TileBox(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        box.setPadding(px(6), px(6), px(6), px(4));
        box.setFocusable(true);
        box.setFocusableInTouchMode(true);
        box.setBackground(null);
        box.setLongAction(onLong);
        box.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onClick.run(); }
        });

        // The icon keeps its native artwork: no plate, no tint, no shrink.
        FrameLayout holder = new FrameLayout(this);
        holder.setBackground(iconFrame());
        holder.setDuplicateParentStateEnabled(true);   // focus lives on the tile, not the holder
        ImageView img = new ImageView(this);
        img.setImageDrawable(icon);
        holder.addView(img, new FrameLayout.LayoutParams(px(96), px(96), Gravity.CENTER));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(px(112), px(112));
        hlp.bottomMargin = px(10);
        box.addView(holder, hlp);

        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(15));
        tv.setTextColor(INK);
        tv.setAlpha(0.88f);
        tv.setLetterSpacing(0.04f);
        tv.setGravity(Gravity.CENTER_HORIZONTAL);
        tv.setMaxLines(1);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.leftMargin = px(4);
        tlp.rightMargin = px(4);
        box.addView(tv, tlp);
        box.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean focused) {
                if (!focused || scroller == null) { return; }
                int leftPad = px(140), rightPad = px(140);
                int view = scroller.getWidth();
                int scroll = scroller.getScrollX();
                int tileLeft = v.getLeft(), tileRight = v.getRight();
                int target = scroll;
                if (tileRight > scroll + view - rightPad) {
                    target = tileRight - view + rightPad;
                } else if (tileLeft < scroll + leftPad) {
                    target = tileLeft - leftPad;
                }
                int max = Math.max(0, ((View) v.getParent()).getWidth() - view);
                scroller.smoothScrollTo(Math.max(0, Math.min(target, max)), 0);
            }
        });
        return box;
    }

    /** Focus is shown as a champagne ring around the artwork, not a card behind it. */
    private StateListDrawable iconFrame() {
        StateListDrawable d = new StateListDrawable();
        GradientDrawable on = new GradientDrawable();
        on.setColor(0x14FFFFFF);
        on.setCornerRadius(px(22));
        on.setStroke(Math.max(2, px(2)), GOLD);
        GradientDrawable off = new GradientDrawable();
        off.setColor(0x00000000);
        off.setCornerRadius(px(22));
        d.addState(new int[]{android.R.attr.state_focused}, on);
        d.addState(new int[]{}, off);
        return d;
    }

    /** The far-right door to every launchable app: outline card, grid glyph, no brand colour. */
    private View allAppsCard(boolean withLabel) {
        TileBox box = new TileBox(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        box.setPadding(px(6), px(6), px(6), px(4));
        box.setFocusable(true);
        box.setFocusableInTouchMode(true);
        box.setBackground(null);

        FrameLayout holder = new FrameLayout(this);
        holder.setBackground(iconFrame());
        holder.setDuplicateParentStateEnabled(true);
        holder.addView(new GridGlyph(this), new FrameLayout.LayoutParams(px(56), px(56), Gravity.CENTER));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(px(112), px(112));
        hlp.bottomMargin = px(10);
        box.addView(holder, hlp);

        TextView tv = new TextView(this);
        tv.setText("ALL APPS");
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(15));
        tv.setTextColor(INK);
        tv.setAlpha(0.8f);
        tv.setLetterSpacing(0.08f);
        tv.setGravity(Gravity.CENTER_HORIZONTAL);
        box.addView(tv, new LinearLayout.LayoutParams(-1, -2));

        box.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showAllApps(); }
        });
        return box;
    }

    private LinearLayout.LayoutParams cardLp(int width) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(width, px(150));
        lp.rightMargin = px(22);
        return lp;
    }

    private StateListDrawable cardFrame() {
        StateListDrawable d = new StateListDrawable();
        GradientDrawable on = new GradientDrawable();
        on.setColor(0x33FFFFFF);
        on.setCornerRadius(px(26));
        on.setStroke(Math.max(2, px(2)), GOLD);
        GradientDrawable off = new GradientDrawable();
        off.setColor(0x1FFFFFFF);
        off.setCornerRadius(px(26));
        off.setStroke(Math.max(1, px(1)), 0x44FBF8F3);
        d.addState(new int[]{android.R.attr.state_focused}, on);
        d.addState(new int[]{}, off);
        return d;
    }

    private View dockItem(String text, final Runnable action) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(16));
        v.setLetterSpacing(0.30f);
        v.setTextColor(new android.content.res.ColorStateList(
                new int[][]{new int[]{android.R.attr.state_focused}, new int[]{}},
                new int[]{GOLD, 0xCCFBF8F3}));
        v.setPadding(px(30), px(10), px(30), px(10));
        v.setGravity(Gravity.CENTER);
        v.setFocusable(true);
        v.setFocusableInTouchMode(true);
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(0x33FFFFFF);
        focused.setCornerRadius(px(26));
        focused.setStroke(Math.max(2, px(2)), GOLD);
        GradientDrawable idle = new GradientDrawable();
        idle.setColor(0x1AFFFFFF);
        idle.setCornerRadius(px(26));
        idle.setStroke(Math.max(1, px(1)), 0x33FBF8F3);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, focused);
        bg.addState(new int[]{}, idle);
        v.setBackground(bg);
        LinearLayout.LayoutParams dlp2 = new LinearLayout.LayoutParams(-2, px(52));
        dlp2.rightMargin = px(14);
        v.setLayoutParams(dlp2);
        v.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View x) { action.run(); }
        });
        return v;
    }

    /** 2x2 grid glyph used by the ALL APPS card (no font dependency). */
    private class GridGlyph extends View {
        GridGlyph(android.content.Context c) { super(c); }

        @Override protected void onDraw(Canvas c) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.STROKE);
            p.setColor(0xE6FBF8F3);
            p.setStrokeWidth(Math.max(1.5f, px(2)));
            float cell = getWidth() * 0.34f, gap = getWidth() * 0.14f;
            float ox = (getWidth() - (cell * 2 + gap)) / 2f;
            float oy = (getHeight() - (cell * 2 + gap)) / 2f;
            float r = cell * 0.22f;
            for (int r2 = 0; r2 < 2; r2++) {
                for (int c2 = 0; c2 < 2; c2++) {
                    float left = ox + c2 * (cell + gap), top = oy + r2 * (cell + gap);
                    c.drawRoundRect(new android.graphics.RectF(left, top, left + cell, top + cell), r, r, p);
                }
            }
        }
    }

    private View chip(String text, final Runnable action) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        v.setTextColor(0xFFE2DDD5);
        v.setLetterSpacing(0.16f);
        v.setPadding(dp(18), dp(10), dp(18), dp(10));
        v.setFocusable(true);
        v.setFocusableInTouchMode(true);
        v.setBackground(chipFrame());
        v.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View x) { action.run(); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = dp(9);
        v.setLayoutParams(lp);
        return v;
    }

    private StateListDrawable focusFrame() {
        StateListDrawable d = new StateListDrawable();
        GradientDrawable on = new GradientDrawable();
        on.setColor(0x24C8A96A);
        on.setCornerRadius(dp(14));
        on.setStroke(dp(2), GOLD);
        GradientDrawable off = new GradientDrawable();
        off.setColor(0x00000000);
        off.setCornerRadius(dp(14));
        d.addState(new int[]{android.R.attr.state_focused}, on);
        d.addState(new int[]{}, off);
        return d;
    }

    private StateListDrawable chipFrame() {
        StateListDrawable d = new StateListDrawable();
        GradientDrawable on = new GradientDrawable();
        on.setColor(GOLD);
        on.setCornerRadius(dp(18));
        on.setStroke(dp(1), GOLD);
        GradientDrawable off = new GradientDrawable();
        off.setColor(0x14FFFFFF);
        off.setCornerRadius(dp(18));
        off.setStroke(dp(1), 0x3DFFFFFF);
        d.addState(new int[]{android.R.attr.state_focused}, on);
        d.addState(new int[]{}, off);
        return d;
    }

    // ------------------------------------------------------------- remote keys

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (menu != null) {
            if (code == KeyEvent.KEYCODE_BACK) { closeMenu(); return true; }
            if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (!menuItems.isEmpty()) { menuIndex = (menuIndex + 1) % menuItems.size(); paintMenu(); }
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_UP) {
                if (!menuItems.isEmpty()) { menuIndex = (menuIndex - 1 + menuItems.size()) % menuItems.size(); paintMenu(); }
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                    || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                if (System.currentTimeMillis() - menuOpenedAt < 400) {
                    android.util.Log.i("ATELIER_MENU", "swallowed stray enter");
                    return true;
                }
                if (menuIndex >= 0 && menuIndex < menuActions.size()) {
                    // the action rebuilds the home view; the key-up of this same press would
                    // otherwise land on the freshly focused tile and launch that app
                    pageOpenedAt = System.currentTimeMillis();
                    menuActions.get(menuIndex).run();
                }
                return true;
            }
            return super.onKeyDown(code, e);   // volume and every other key stay live
        }

        if (page != null && code == KeyEvent.KEYCODE_BACK) {
            closePage();
            return true;
        }

        if (moveMode) {
            if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
                return true;   // keep focus on the row while reordering
            }
            if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                int from = movePkg == null ? -1 : order.indexOf(movePkg);
                if (from < 0) {
                    // the package left the row underneath us (removed / uninstalled) — leave cleanly
                    android.util.Log.i("ATELIER_MOVE", "move target gone, exiting move mode");
                    moveMode = false; movePkg = null; moveIndex = -1;
                    buildHome();
                    return true;
                }
                int to = from + (code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                if (to < 0 || to >= order.size()) { return true; }   // edge: swallow, never throw
                try {
                    Collections.swap(order, from, to);
                } catch (RuntimeException bad) {
                    android.util.Log.i("ATELIER_MOVE", "swap refused from=" + from
                            + " to=" + to + " size=" + order.size());
                    return true;
                }
                moveIndex = to;
                writeOrder(order);
                buildHome();
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                    || code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_BACK) {
                moveMode = false; movePkg = null; moveIndex = -1;
                buildHome();
                return true;
            }
            return super.onKeyDown(code, e);
        }
        switch (code) {
            case KeyEvent.KEYCODE_BACK:
                // Back on the home screen has nothing to close; removing root children here
                // used to make the dock chips disappear one press at a time.
                return true;
            case KeyEvent.KEYCODE_SETTINGS:
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_GUIDE:
                openComponent(TV_SETTINGS, TV_SETTINGS_ACTIVITY);
                return true;
            case KeyEvent.KEYCODE_INFO:
                showAllApps();
                return true;
            case KeyEvent.KEYCODE_TV_INPUT:
            case 4077: // TCL remote gear key
                openQuickPanel();
                return true;
            case KeyEvent.KEYCODE_HOME:
                if (menu != null) { closeMenu(); }
                else if (page != null) { closePage(); }
                else if (root != null) { setContentView(root); }
                restoreFocus();
                return true;
            default:
                android.util.Log.i("ATELIER_KEY", "unhandled key=" + code);
                return super.onKeyDown(code, e);
        }
    }

    @Override
    protected void onDestroy() {
        tick.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    /** A tile that understands a held OK key, which is how a TV remote long-press arrives. */
    private class TileBox extends LinearLayout {
        private Runnable longAction;
        private boolean longFired = false;
        /** Set only by this instance's own key-down, so a rebuilt tile never fires on a stray key-up. */
        private boolean armed = false;

        TileBox(android.content.Context c) { super(c); }

        void setLongAction(Runnable r) { longAction = r; }

        private boolean isOk(int code) {
            return code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                    || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        }

        @Override public boolean onKeyDown(int code, KeyEvent event) {
            if (menu != null) { return false; }   // let the popup own every key
            if (moveMode) { return false; }       // move mode is driven by the activity
            if (isOk(code)) {
                armed = true;
                event.startTracking();   // required: without this the framework never reports a hold
                return true;
            }
            return super.onKeyDown(code, event);
        }

        @Override public boolean onKeyUp(int code, KeyEvent event) {
            if (menu != null) { longFired = false; armed = false; return false; }
            if (moveMode) { longFired = false; armed = false; return true; }
            if (isOk(code)) {
                if (!armed) { return true; }   // key-up without our own key-down: ignore it
                armed = false;
                if (!longFired && !event.isCanceled()) { performClick(); }
                longFired = false;
                return true;
            }
            return super.onKeyUp(code, event);
        }

        @Override public boolean onKeyLongPress(int code, KeyEvent event) {
            if (menu != null || moveMode) { return false; }
            if (isOk(code) && longAction != null) {
                longFired = true;
                armed = false;   // the click for this press is already spent
                longAction.run();
                return true;
            }
            return super.onKeyLongPress(code, event);
        }
    }

    /** Google TV wallpaper (when one is set) behind a scrim; gradient only as a fallback. */
    private final Paint washPaint = new Paint();

    private Paint paintWash(int color) {
        washPaint.setStyle(Paint.Style.FILL);
        washPaint.setShader(null);
        washPaint.setColor(color);
        return washPaint;
    }

    private class Stage extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Drawable wallpaper;
        private Drawable photo;

        Stage() {
            super(MainActivity.this);
            setFocusable(false);
            try { wallpaper = WallpaperManager.getInstance(MainActivity.this).getDrawable(); }
            catch (Exception ignored) { wallpaper = null; }
            try {
                java.io.InputStream is = getAssets().open("bg.jpg");
                android.graphics.BitmapFactory.Options opt = new android.graphics.BitmapFactory.Options();
                opt.inPreferredConfig = android.graphics.Bitmap.Config.RGB_565;   // 4 MB, not 8
                photo = new android.graphics.drawable.BitmapDrawable(getResources(),
                        android.graphics.BitmapFactory.decodeStream(is, null, opt));
                is.close();
            } catch (Exception ignored) { photo = null; }
        }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            if (w == 0 || h == 0) { return; }

            if (photo != null) {
                photo.setBounds(0, 0, w, h);
                photo.draw(c);
                // even wash so ivory type keeps its contrast over a bright aurora
                c.drawRect(0, 0, w, h, paintWash(0x2E080809));
                paint.setShader(new LinearGradient(w * 0.30f, 0, w, 0,
                        new int[]{0x00000000, 0x8C080809}, new float[]{0f, 1f}, Shader.TileMode.CLAMP));
                c.drawRect(w * 0.25f, 0, w, h * 0.62f, paint);
                paint.setShader(new LinearGradient(0, h * 0.42f, 0, h,
                        new int[]{0x00000000, 0xB3080809}, new float[]{0f, 1f}, Shader.TileMode.CLAMP));
                c.drawRect(0, h * 0.38f, w, h, paint);
                paint.setShader(new LinearGradient(0, 0, w * 0.22f, 0,
                        new int[]{0x59080809, 0x00000000}, new float[]{0f, 1f}, Shader.TileMode.CLAMP));
                c.drawRect(0, 0, w * 0.22f, h * 0.22f, paint);
                paint.setShader(null);
                return;
            }

            if (wallpaper != null) {
                wallpaper.setBounds(0, 0, w, h);
                wallpaper.draw(c);
                paint.setShader(new LinearGradient(0, 0, w * 0.55f, h,
                        new int[]{0xA6080809, 0x8C0A0A0C, 0xD9080809},
                        new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
                c.drawRect(0, 0, w, h, paint);
                paint.setShader(new RadialGradient(w * 0.16f, h * 0.18f, Math.max(w, h) * 0.55f,
                        new int[]{0x1AC8A96A, 0x00000000}, null, Shader.TileMode.CLAMP));
                c.drawRect(0, 0, w, h, paint);
            } else {
                paint.setShader(new LinearGradient(0, 0, w * 0.25f, h,
                        new int[]{0xFF0A0A0C, 0xFF131218, 0xFF08080A},
                        new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
                c.drawRect(0, 0, w, h, paint);
                paint.setShader(new RadialGradient(w * 0.16f, h * 0.20f, Math.max(w, h) * 0.60f,
                        new int[]{0x16C8A96A, 0x00000000}, null, Shader.TileMode.CLAMP));
                c.drawRect(0, 0, w, h, paint);
            }
            paint.setShader(null);
        }
    }
}

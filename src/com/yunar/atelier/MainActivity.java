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
    private boolean arcPending = false;
    private int homeFailures = 0;

    private final Handler tick = new Handler();
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", Locale.US);
    private final SimpleDateFormat dateFmt =
            new SimpleDateFormat("EEEE, d MMMM yyyy", new Locale("id", "ID"));

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
        // One re-arm per boot: the soundbar is usually asleep when the set finishes booting.
        new Handler().postDelayed(new Runnable() {
            @Override public void run() { audioRehandshake("boot"); }
        }, 9000);
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

    /** Start Tailscale once per boot: Android's always-on VPN setting does not wake it on this set. */
    private void maybeKickVpn() {
        try {
            android.content.SharedPreferences sp = getSharedPreferences("atelier", MODE_PRIVATE);
            long bootId = System.currentTimeMillis() / 1000L
                    - android.os.SystemClock.elapsedRealtime() / 1000L;
            if (sp.getLong("vpn_kick_boot", 0L) == bootId) { return; }
            sp.edit().putLong("vpn_kick_boot", bootId).apply();
            Intent ts = getPackageManager().getLaunchIntentForPackage("com.tailscale.ipn");
            if (ts == null) { return; }
            ts.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(ts);
            new Handler().postDelayed(new Runnable() {
                @Override public void run() {
                    if (!hasWindowFocus()) { return; }   // user already moved on; don't yank them
                    Intent home = new Intent(Intent.ACTION_MAIN);
                    home.addCategory(Intent.CATEGORY_HOME);
                    home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try { startActivity(home); } catch (Exception ignored) { }
                }
            }, 2600);
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
        pruneIfStale();
    }

    /** After an uninstall the tile should disappear without the owner doing anything. */
    private void pruneIfStale() {
        if (order == null || order.isEmpty()) { return; }
        for (String p : new ArrayList<String>(order)) {
            try { getPackageManager().getApplicationInfo(p, 0); }
            catch (Exception gone) { buildHome(); return; }
        }
    }

    // ------------------------------------------------------------- HDMI ARC audio

    /**
     * The soundbar can sit in CEC standby while the TV already routes to ARC, which looks
     * exactly like "no sound". Toggling system audio control forces a fresh handshake and
     * wakes it. Needs WRITE_SECURE_SETTINGS, granted once over adb.
     */
    private void audioRehandshake(final String why) {
        if (arcPending) { return; }
        arcPending = true;
        try {
            android.provider.Settings.Global.putInt(
                    getContentResolver(), "hdmi_system_audio_control_enabled", 0);
            android.util.Log.i("ATELIER_AUDIO", "re-arm ARC (" + why + "): off");
        } catch (Exception denied) {
            arcPending = false;
            android.util.Log.i("ATELIER_AUDIO", "skipped (" + why + "): no WRITE_SECURE_SETTINGS");
            return;
        }
        new Handler().postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    android.provider.Settings.Global.putInt(
                            getContentResolver(), "hdmi_system_audio_control_enabled", 1);
                    android.util.Log.i("ATELIER_AUDIO", "re-arm ARC (" + why + "): on");
                } catch (Exception ignored) { }
                arcPending = false;
            }
        }, 2500);
    }

    /** If a previous toggle was interrupted, the global can be left at 0 — put it back. */
    private void ensureArcEnabled() {
        try {
            int v = android.provider.Settings.Global.getInt(
                    getContentResolver(), "hdmi_system_audio_control_enabled", 1);
            if (v == 0 && !arcPending) {
                android.provider.Settings.Global.putInt(
                        getContentResolver(), "hdmi_system_audio_control_enabled", 1);
                android.util.Log.i("ATELIER_AUDIO", "restored hdmi_system_audio_control_enabled=1");
            }
        } catch (Exception ignored) { }
    }

    /** Fire the handshake when the screen comes back on, which is when the soundbar is asleep. */
    private void watchWake() {
        boolean interactive;
        try {
            interactive = ((android.os.PowerManager) getSystemService(POWER_SERVICE)).isInteractive();
        } catch (Exception e) { return; }
        boolean edge = interactive && !lastInteractive;
        lastInteractive = interactive;
        // never renegotiate CEC audio while another app is playing
        if (edge && hasWindowFocus()) { audioRehandshake("wake"); }
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

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);
        column.setPadding(dp(96), dp(88), dp(64), dp(48));

        clock = new TextView(this);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 104);
        clock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        clock.setTextColor(INK);
        clock.setIncludeFontPadding(false);
        clock.setLetterSpacing(0.02f);
        column.addView(clock);

        dateline = new TextView(this);
        dateline.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        dateline.setTextColor(GOLD);
        dateline.setLetterSpacing(0.36f);
        dateline.setIncludeFontPadding(false);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-2, -2);
        dlp.topMargin = dp(12);
        column.addView(dateline, dlp);

        View rule = new View(this);
        rule.setBackgroundColor(0x40C8A96A);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(dp(196), dp(1));
        rlp.topMargin = dp(30);
        column.addView(rule, rlp);

        // One row, justified edge to edge: tiles share the width equally and the row
        // re-flows automatically when an app is removed. No scroll, so nothing clips.
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);
        row.setClipChildren(false);
        row.setPadding(dp(8), dp(6), dp(8), dp(6));
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
        rowLp.topMargin = dp(34);
        column.addView(row, rowLp);

        PackageManager pm = getPackageManager();
        firstTile = null;
        int shown = 0;
        for (int i = 0; i < order.size() && shown < MAX_ROW; i++) {
            final String pkg = order.get(i);
            final int index = i;
            Drawable icon;
            String label;
            try {
                icon = pm.getApplicationIcon(pkg);
                label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
            } catch (Exception missing) { continue; }
            View tile = tile(icon, label, false, new Runnable() {
                @Override public void run() {
                    if (editMode) { showMenu(pkg, index, false); } else { launchPackage(pkg); }
                }
            }, new Runnable() {
                @Override public void run() { showMenu(pkg, index, false); }
            });
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1f);
            row.addView(tile, tlp);
            tileViews.add(tile);
            if (firstTile == null) { firstTile = tile; }
            shown++;
        }

        root.addView(column, new FrameLayout.LayoutParams(-1, -1));

        TextView mark = new TextView(this);
        mark.setText(readTitle());
        mark.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        mark.setTextColor(0x80F6F3EE);
        mark.setLetterSpacing(0.62f);
        FrameLayout.LayoutParams mlp = new FrameLayout.LayoutParams(-2, -2);
        mlp.gravity = Gravity.TOP | Gravity.END;
        mlp.setMargins(0, dp(92), dp(64), 0);
        root.addView(mark, mlp);

        LinearLayout shortcuts = new LinearLayout(this);
        shortcuts.setOrientation(LinearLayout.HORIZONTAL);
        shortcuts.setGravity(Gravity.CENTER_VERTICAL);
        shortcuts.addView(chip("Input", new Runnable() {
            @Override public void run() { openQuickPanel(); }
        }));
        shortcuts.addView(chip("Applications", new Runnable() {
            @Override public void run() { showAllApps(); }
        }));
        shortcuts.addView(chip("Settings", new Runnable() {
            @Override public void run() { openComponent(TV_SETTINGS, TV_SETTINGS_ACTIVITY); }
        }));
        shortcuts.addView(chip("Setup", new Runnable() {
            @Override public void run() { showSetup(); }
        }));
        shortcuts.addView(chip(editMode ? "Done" : "Edit", new Runnable() {
            @Override public void run() { editMode = !editMode; buildHome(); }
        }));

        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(-2, -2);
        slp.gravity = Gravity.BOTTOM | Gravity.END;
        slp.setMargins(0, 0, dp(56), dp(46));
        root.addView(shortcuts, slp);

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
                tick.postDelayed(this, 20000);
            }
        }, 20000);
    }

    @Override protected void onStart() {
        super.onStart();
        scheduleTick();
        ensureArcEnabled();
    }

    @Override protected void onStop() {
        tick.removeCallbacksAndMessages(null);
        super.onStop();
    }

    private void paintTime() {
        Date now = new Date();
        clock.setText(timeFmt.format(now));
        dateline.setText(dateFmt.format(now).toUpperCase(Locale.US));
    }

    // ------------------------------------------------------------- app list io

    private File homeDir() { return getExternalFilesDir(null); }

    private List<String> readOrder() {
        List<String> out = new ArrayList<String>();
        File f = homeDir() == null ? null : new File(homeDir(), "apps.txt");
        if (f != null && f.exists()) {
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
        if (out.isEmpty()) { for (String p : Apps.LIST) { out.add(p); } }
        return out;
    }

    /**
     * Drop entries that no longer exist on the device (uninstalled, or a package that never
     * resolved) so a stale row can never shift the tile indices under the remote.
     */
    private List<String> reconcile(List<String> list) {
        List<String> kept = new ArrayList<String>();
        boolean changed = false;
        for (String pkg : list) {
            boolean installed;
            try { getPackageManager().getApplicationInfo(pkg, 0); installed = true; }
            catch (Exception gone) { installed = false; }
            if (installed) { kept.add(pkg); } else { changed = true; }
        }
        if (kept.isEmpty()) { kept.addAll(list); changed = false; }
        if (changed) {
            android.util.Log.i("ATELIER_HOME", "dropped " + (list.size() - kept.size()) + " missing package(s)");
            writeOrder(kept);
        }
        return kept;
    }

    private void writeOrder(List<String> list) {
        File f = homeDir() == null ? null : new File(homeDir(), "apps.txt");
        if (f == null) { return; }
        FileWriter w = null;
        try {
            w = new FileWriter(f, false);
            for (String p : list) { w.write(p); w.write("\n"); }
        } catch (Exception ignored) {
        } finally { if (w != null) { try { w.close(); } catch (Exception ignored) { } } }
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

    private Intent resolveLaunch(String pkg) {
        PackageManager pm = getPackageManager();
        Intent plain = pm.getLaunchIntentForPackage(pkg);
        if (plain != null) { return plain; }
        Intent[] probes = new Intent[]{
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER),
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)};
        for (Intent probe : probes) {
            probe.setPackage(pkg);
            List<ResolveInfo> found = pm.queryIntentActivities(probe, 0);
            if (found != null && !found.isEmpty()) {
                Intent i = new Intent(Intent.ACTION_MAIN);
                i.setClassName(pkg, found.get(0).activityInfo.name);
                return i;
            }
        }
        return null;
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

        col.addView(setupRow("Reset home row to default", new Runnable() {
            @Override public void run() {
                List<String> base = new ArrayList<String>();
                for (String p : Apps.LIST) { base.add(p); }
                writeOrder(base);
                buildHome();
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
        String msg;
        try { getPackageManager().getApplicationInfo(pkg, 0); }
        catch (Exception missing) { toast("No such package: " + pkg); return; }
        List<String> list = readOrder();
        if (list.contains(pkg)) { toast("Already on the row"); return; }
        if (list.size() >= MAX_ROW) { toast("Row is full (max " + MAX_ROW + ")"); return; }
        list.add(pkg);
        writeOrder(list);
        buildHome();
    }

    private void toast(String text) {
        try { android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show(); }
        catch (Exception ignored) { }
    }

    // ------------------------------------------------------------- launching

    private void launchPackage(String pkg) {
        Intent i = resolveLaunch(pkg);
        if (i == null) { return; }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(i); } catch (Exception ignored) { }
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
        try { startActivity(i); } catch (Exception ignored) { }
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
    private class Stage extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Drawable wallpaper;

        Stage() {
            super(MainActivity.this);
            setFocusable(false);
            try { wallpaper = WallpaperManager.getInstance(MainActivity.this).getDrawable(); }
            catch (Exception ignored) { wallpaper = null; }
        }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            if (w == 0 || h == 0) { return; }

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

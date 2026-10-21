package com.yunar.atelier;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Catches the remote's gear / menu key while ANY app is in front.
 * Google TV keeps its quick-settings panel inside its own launcher, so replacing the
 * launcher loses that key path; an accessibility service is the only way back without root.
 * The owner has to switch this on once in Settings > Accessibility.
 */
public class AtelierA11y extends AccessibilityService {

    private static final String TV_SETTINGS = "com.android.tv.settings";
    private static final String TV_SETTINGS_ACTIVITY = "com.android.tv.settings.MainSettings";
    private static final String QUICK_PANEL = "com.tcl.suspension";
    private static final String QUICK_PANEL_ACTIVITY = "com.tcl.suspension.ui.main.InputActivity";
    private static final String QUICK_PANEL_ACTION = "com.tcl.suspension.VIEW_INPUTS";
    /** The remote's gear key on this TCL set: a vendor keycode, not KEYCODE_SETTINGS. */
    private static final int TCL_GEAR = 4077;
    private long lastGear = 0L;

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override public void onInterrupt() { }

    private void openSettings() {
        Intent i = new Intent();
        i.setClassName(TV_SETTINGS, TV_SETTINGS_ACTIVITY);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(i); } catch (Exception ignored) { }
    }

    /** Set while we swallowed a key-down, so its key-up does not reach the app alone. */
    private int pendingDown = 0;

    @Override protected boolean onKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();

        if (event.getAction() == KeyEvent.ACTION_UP) {
            if (code == pendingDown) { pendingDown = 0; return true; }
            return super.onKeyEvent(event);
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN) { return super.onKeyEvent(event); }

        // Only the vendor gear key (and the input key) are ours. MENU / GUIDE / SETTINGS stay
        // with the foreground app: video players and IPTV apps use them for their own menus.
        if (code != TCL_GEAR && code != KeyEvent.KEYCODE_TV_INPUT) { return super.onKeyEvent(event); }
        pendingDown = code;
        android.util.Log.i("ATELIER_A11Y", "gear key=" + code);

        long now = System.currentTimeMillis();
        boolean doublePress = (now - lastGear) < 2500L;
        lastGear = now;
        if (doublePress) {
            // Double-press is the escape hatch: full TV Settings, always reachable.
            openSettings();
            return true;
        }
        // TCL's panel gets stuck in a "already shown" state; dismiss it first.
        try { sendBroadcast(new Intent("com.tcl.quickmenu.dismiss")); } catch (Exception ignored) { }
        Intent quick = new Intent(QUICK_PANEL_ACTION);
        quick.setClassName(QUICK_PANEL, QUICK_PANEL_ACTIVITY);
        quick.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(quick); return true; } catch (Exception ignored) { }
        openSettings();
        return true;
    }
}

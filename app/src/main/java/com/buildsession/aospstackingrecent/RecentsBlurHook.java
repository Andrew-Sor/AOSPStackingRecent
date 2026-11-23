package com.buildsession.aospstackingrecent;

import android.app.WallpaperManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.View;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import java.lang.reflect.Method;

/**
 * Draws a blurred copy of the system wallpaper behind the Recents cards.
 *
 * This does NOT rely on window-level blur (which is disabled on low-end / Go devices). Instead a
 * tiny, software-blurred bitmap of the wallpaper is painted inside Launcher's ScrimView, the layer
 * that sits between the workspace and RecentsView. Its opacity follows the scrim colour that
 * Launcher already animates when Overview opens/closes, so it fades in and out together with it.
 */
public class RecentsBlurHook {
    static final String PREFS_PACKAGE = "com.buildsession.aospstackingrecent";
    static final String KEY_BLUR_ENABLED = "blur_enabled";
    static final String KEY_BLUR_DIM = "blur_dim";
    static final String KEY_BLUR_STRENGTH = "blur_strength";
    static final boolean DEFAULT_BLUR_ENABLED = true;
    static final float DEFAULT_BLUR_DIM = 0.25f;
    static final float DEFAULT_BLUR_STRENGTH = 0.5f;

    /** Width of the working bitmap. Small on purpose: cheap to blur, upscaling smooths it further. */
    private static final int TARGET_WIDTH = 128;
    private static final long PREFS_RELOAD_MS = 1000;
    private static final long WALLPAPER_CHECK_MS = 3000;
    private static final long RETRY_AFTER_FAILURE_MS = 30000;

    private final ClassLoader classLoader;
    private final XSharedPreferences prefs = new XSharedPreferences(PREFS_PACKAGE);
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect srcRect = new Rect();
    private final Rect dstRect = new Rect();

    private Class<?> launcherClass;
    private Class<?> overviewStateClass;
    private Method getStateManagerMethod;
    private Method getStateMethod;
    private Method getStableStateMethod;
    private Method getWorkspaceScrimColorMethod;
    private Method getBackgroundColorMethod;
    private Method getWorkspaceMethod;
    private Method getWallpaperOffsetMethod;
    private Object overviewState;

    private long lastPrefsReload;
    private boolean enabled = DEFAULT_BLUR_ENABLED;
    private float dim = DEFAULT_BLUR_DIM;
    private float strength = DEFAULT_BLUR_STRENGTH;

    // Wallpaper cache (written by the loader thread, read on the UI thread).
    private volatile Bitmap blurred;
    private volatile int blurredRadius = -1;
    private volatile long blurredWallpaperId = -2;
    private volatile boolean loading;
    private volatile long failedUntil;
    private long lastWallpaperCheck;
    private long currentWallpaperId = -1;
    private boolean loggedFailure;

    public RecentsBlurHook(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    public void initHooks() {
        try {
            Class<?> scrimViewClass = XposedHelpers.findClassIfExists("com.android.launcher3.views.ScrimView", classLoader);
            launcherClass = XposedHelpers.findClassIfExists("com.android.launcher3.Launcher", classLoader);
            Class<?> launcherStateClass = XposedHelpers.findClassIfExists("com.android.launcher3.LauncherState", classLoader);
            overviewStateClass = XposedHelpers.findClassIfExists("com.android.launcher3.uioverrides.states.OverviewState", classLoader);
            Class<?> stateManagerClass = XposedHelpers.findClassIfExists("com.android.launcher3.statemanager.StateManager", classLoader);
            if (scrimViewClass == null || launcherClass == null || launcherStateClass == null
                    || overviewStateClass == null || stateManagerClass == null) {
                XposedBridge.log("AOSPStackingRecent: Blur: required classes not found, skipping");
                return;
            }

            getStateManagerMethod = accessible(XposedHelpers.findMethodExactIfExists(launcherClass, "getStateManager"));
            getStateMethod = accessible(XposedHelpers.findMethodExactIfExists(stateManagerClass, "getState"));
            getStableStateMethod = accessible(XposedHelpers.findMethodExactIfExists(stateManagerClass, "getCurrentStableState"));
            getWorkspaceScrimColorMethod = accessible(XposedHelpers.findMethodExactIfExists(launcherStateClass, "getWorkspaceScrimColor", launcherClass));
            getBackgroundColorMethod = accessible(XposedHelpers.findMethodExactIfExists(scrimViewClass, "getBackgroundColor"));
            overviewState = XposedHelpers.getStaticObjectField(launcherStateClass, "OVERVIEW");

            // Optional: lets the blurred layer follow the home screen's wallpaper parallax.
            // Without them we fall back to a centred crop.
            Class<?> workspaceClass = XposedHelpers.findClassIfExists("com.android.launcher3.Workspace", classLoader);
            getWorkspaceMethod = accessible(XposedHelpers.findMethodExactIfExists(launcherClass, "getWorkspace"));
            if (workspaceClass != null) {
                getWallpaperOffsetMethod = accessible(
                        XposedHelpers.findMethodExactIfExists(workspaceClass, "getWallpaperOffsetForCenterPage"));
            }
            XposedBridge.log("AOSPStackingRecent: Blur: wallpaper parallax sync "
                    + (getWorkspaceMethod != null && getWallpaperOffsetMethod != null ? "available" : "unavailable"));

            if (getStateManagerMethod == null || getStateMethod == null || getStableStateMethod == null
                    || getWorkspaceScrimColorMethod == null || getBackgroundColorMethod == null
                    || overviewState == null) {
                XposedBridge.log("AOSPStackingRecent: Blur: required members not found, skipping");
                return;
            }

            XposedBridge.hookAllMethods(scrimViewClass, "onDraw", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        drawBlur((View) param.thisObject, (Canvas) param.args[0]);
                    } catch (Throwable t) {
                        // Never break Launcher's drawing because of us.
                    }
                }
            });
            XposedBridge.log("AOSPStackingRecent: Blur: hooked ScrimView.onDraw");
        } catch (Throwable t) {
            XposedBridge.log("AOSPStackingRecent: Blur: init failed: " + t);
        }
    }

    private static Method accessible(Method m) {
        if (m != null) m.setAccessible(true);
        return m;
    }

    private void reloadPrefsIfNeeded() {
        long now = SystemClock.uptimeMillis();
        if (now - lastPrefsReload < PREFS_RELOAD_MS) return;
        lastPrefsReload = now;
        prefs.reload();
        enabled = prefs.getBoolean(KEY_BLUR_ENABLED, DEFAULT_BLUR_ENABLED);
        dim = clamp(prefs.getFloat(KEY_BLUR_DIM, DEFAULT_BLUR_DIM), 0f, 0.9f);
        strength = clamp(prefs.getFloat(KEY_BLUR_STRENGTH, DEFAULT_BLUR_STRENGTH), 0f, 1f);
    }

    private Object findLauncher(View view) {
        Context c = view.getContext();
        while (c != null && !launcherClass.isInstance(c)) {
            c = c instanceof ContextWrapper ? ((ContextWrapper) c).getBaseContext() : null;
        }
        return c;
    }

    /** 0..1: how far the Overview scrim is faded in. 0 outside of Overview. */
    private float overviewProgress(View scrim, Object launcher) throws Throwable {
        Object stateManager = getStateManagerMethod.invoke(launcher);
        if (stateManager == null) return 0f;
        // The target state is OVERVIEW while opening, the stable state is still OVERVIEW while closing.
        boolean inOverview = overviewStateClass.isInstance(getStateMethod.invoke(stateManager))
                || overviewStateClass.isInstance(getStableStateMethod.invoke(stateManager));
        if (!inOverview) return 0f;

        int target = Color.alpha((int) getWorkspaceScrimColorMethod.invoke(overviewState, launcher));
        if (target <= 0) return 0f;
        int current = Color.alpha((int) getBackgroundColorMethod.invoke(scrim));
        return clamp(current / (float) target, 0f, 1f);
    }

    private void drawBlur(View scrim, Canvas canvas) throws Throwable {
        reloadPrefsIfNeeded();
        if (!enabled) return;

        Object launcher = findLauncher(scrim);
        if (launcher == null) return;
        float progress = overviewProgress(scrim, launcher);
        if (progress <= 0.01f) return;

        Context context = scrim.getContext();
        int radius = 1 + Math.round(strength * 7f);
        refreshWallpaperIdIfNeeded(context);
        Bitmap bmp = blurred;
        if (bmp == null || blurredRadius != radius || blurredWallpaperId != currentWallpaperId) {
            requestLoad(context, scrim, radius, currentWallpaperId);
        }
        if (bmp == null) return; // Not ready yet; Launcher keeps its normal scrim meanwhile.

        int w = scrim.getWidth();
        int h = scrim.getHeight();
        if (w <= 0 || h <= 0) return;

        // Crop the wallpaper the same way the system draws it behind the home screen: it is scaled so
        // that it covers the screen, vertically centred, and slid horizontally by the parallax offset
        // (0 = left edge, 1 = right edge). Lawnchair already computes that offset for the current page.
        float offset = currentWallpaperOffset(launcher);
        float scale = Math.max(w / (float) bmp.getWidth(), h / (float) bmp.getHeight());
        float cropW = Math.min(bmp.getWidth(), w / scale);
        float cropH = Math.min(bmp.getHeight(), h / scale);
        float left = (bmp.getWidth() - cropW) * offset;
        float top = (bmp.getHeight() - cropH) * 0.5f;
        srcRect.set(Math.round(left), Math.round(top), Math.round(left + cropW), Math.round(top + cropH));
        dstRect.set(0, 0, w, h);

        bitmapPaint.setAlpha(Math.round(255f * progress));
        canvas.drawBitmap(bmp, srcRect, dstRect, bitmapPaint);
        if (dim > 0f) {
            canvas.drawColor(Color.argb(Math.round(255f * dim * progress), 0, 0, 0));
        }
    }

    /** Horizontal wallpaper offset (0..1) of the page currently shown on the home screen; 0.5 if unknown. */
    private float currentWallpaperOffset(Object launcher) {
        if (getWorkspaceMethod == null || getWallpaperOffsetMethod == null) return 0.5f;
        try {
            Object workspace = getWorkspaceMethod.invoke(launcher);
            if (workspace == null) return 0.5f;
            float offset = (float) getWallpaperOffsetMethod.invoke(workspace);
            return Float.isNaN(offset) ? 0.5f : clamp(offset, 0f, 1f);
        } catch (Throwable t) {
            return 0.5f;
        }
    }

    private void refreshWallpaperIdIfNeeded(Context context) {
        long now = SystemClock.uptimeMillis();
        if (currentWallpaperId != -1 && now - lastWallpaperCheck < WALLPAPER_CHECK_MS) return;
        lastWallpaperCheck = now;
        try {
            currentWallpaperId = WallpaperManager.getInstance(context).getWallpaperId(WallpaperManager.FLAG_SYSTEM);
        } catch (Throwable t) {
            currentWallpaperId = 0;
        }
    }

    private void requestLoad(final Context context, final View invalidateTarget, final int radius, final long wallpaperId) {
        if (loading || SystemClock.uptimeMillis() < failedUntil) return;
        loading = true;
        final Context appContext = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Bitmap small = decodeWallpaperSmall(appContext);
                    if (small == null) {
                        failedUntil = SystemClock.uptimeMillis() + RETRY_AFTER_FAILURE_MS;
                        if (!loggedFailure) {
                            loggedFailure = true;
                            XposedBridge.log("AOSPStackingRecent: Blur: could not read the system wallpaper "
                                    + "(live wallpaper, or Lawnchair has no access to it). Blur is disabled.");
                        }
                    } else {
                        blur(small, radius);
                        blurredRadius = radius;
                        blurredWallpaperId = wallpaperId;
                        blurred = small;
                    }
                } catch (Throwable e) {
                    failedUntil = SystemClock.uptimeMillis() + RETRY_AFTER_FAILURE_MS;
                    if (!loggedFailure) {
                        loggedFailure = true;
                        XposedBridge.log("AOSPStackingRecent: Blur: wallpaper load failed: " + e);
                    }
                } finally {
                    loading = false;
                    invalidateTarget.postInvalidate();
                }
            }
        }, "AOSPStackingRecent-blur");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private static Bitmap decodeWallpaperSmall(Context context) throws Exception {
        WallpaperManager wm = WallpaperManager.getInstance(context);
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        ParcelFileDescriptor pfd = wm.getWallpaperFile(WallpaperManager.FLAG_SYSTEM);
        if (pfd == null) return null;
        try {
            BitmapFactory.decodeFileDescriptor(pfd.getFileDescriptor(), null, opts);
        } finally {
            pfd.close();
        }
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null;

        int sample = 1;
        while (opts.outWidth / (sample * 2) >= TARGET_WIDTH) sample *= 2;
        opts.inJustDecodeBounds = false;
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        pfd = wm.getWallpaperFile(WallpaperManager.FLAG_SYSTEM);
        if (pfd == null) return null;
        Bitmap decoded;
        try {
            decoded = BitmapFactory.decodeFileDescriptor(pfd.getFileDescriptor(), null, opts);
        } finally {
            pfd.close();
        }
        if (decoded == null) return null;

        int outH = Math.max(1, Math.round(TARGET_WIDTH * decoded.getHeight() / (float) decoded.getWidth()));
        Bitmap small = Bitmap.createScaledBitmap(decoded, TARGET_WIDTH, outH, true);
        if (small != decoded) decoded.recycle();
        // Make sure we own a mutable ARGB_8888 copy to blur in place.
        if (!small.isMutable() || small.getConfig() != Bitmap.Config.ARGB_8888) {
            Bitmap copy = small.copy(Bitmap.Config.ARGB_8888, true);
            small.recycle();
            small = copy;
        }
        return small;
    }

    /** In-place 3-pass box blur (approximates a Gaussian) on a small bitmap. */
    private static void blur(Bitmap bmp, int radius) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int[] a = new int[w * h];
        int[] b = new int[w * h];
        bmp.getPixels(a, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 3; pass++) {
            boxPass(a, b, w, h, radius, true);
            boxPass(b, a, w, h, radius, false);
        }
        bmp.setPixels(a, 0, w, 0, 0, w, h);
    }

    private static void boxPass(int[] src, int[] dst, int w, int h, int r, boolean horizontal) {
        int lines = horizontal ? h : w;
        int len = horizontal ? w : h;
        int step = horizontal ? 1 : w;
        int win = 2 * r + 1;
        for (int line = 0; line < lines; line++) {
            int base = horizontal ? line * w : line;
            int sr = 0, sg = 0, sb = 0;
            for (int k = -r; k <= r; k++) {
                int c = src[base + clampInt(k, 0, len - 1) * step];
                sr += (c >> 16) & 0xFF;
                sg += (c >> 8) & 0xFF;
                sb += c & 0xFF;
            }
            for (int i = 0; i < len; i++) {
                dst[base + i * step] = 0xFF000000 | ((sr / win) << 16) | ((sg / win) << 8) | (sb / win);
                int add = src[base + clampInt(i + r + 1, 0, len - 1) * step];
                int sub = src[base + clampInt(i - r, 0, len - 1) * step];
                sr += ((add >> 16) & 0xFF) - ((sub >> 16) & 0xFF);
                sg += ((add >> 8) & 0xFF) - ((sub >> 8) & 0xFF);
                sb += (add & 0xFF) - (sub & 0xFF);
            }
        }
    }

    private static int clampInt(int v, int min, int max) {
        return v < min ? min : (v > max ? max : v);
    }

    private static float clamp(float v, float min, float max) {
        return v < min ? min : (v > max ? max : v);
    }
}

package com.buildsession.aospstackingrecent;

import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.os.SystemClock;
import android.animation.TimeInterpolator;
import android.view.animation.LinearInterpolator;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.XC_MethodHook;

/**
 * 核心 Hook 逻辑，通过修改 RecentsView 实现多任务界面的卡片堆叠视觉效果。
 */
public class RecentsHook {
    private static final String PREFS_NAME = "com.buildsession.aospstackingrecent_preferences";
    private static final String KEY_SPACING = "card_spacing_factor";
    private static final String KEY_TIGHTNESS = "card_tightness_factor";
    private static final float DEFAULT_SPACING = 4.7f;
    private static final float DEFAULT_TIGHTNESS = 0.72f;
    private static final long DEBUG_LOG_THROTTLE_MS = 1500;
    private static final float DISMISS_FILL_SHIFT_FACTOR = 0.75f;

    private final ClassLoader classLoader;
    private final XSharedPreferences prefs;

    private Class<?> recentsViewClass;
    private Class<?> taskViewClass;
    private Class<?> orientationHandlerClass;
    
    private Field persistentTranslationXField;
    private Field nonGridTranslationXField;
    private Field dismissTranslationXField;
    
    private Method getPagedOrientationHandlerMethod;
    private Method getPrimaryScrollMethod;
    private Method getMeasuredSizeMethod;
    private Method getChildStartMethod;
    private Method getOffsetAdjustmentMethod;

    // False on Android 13 based Quickstep (Lawnchair 14): translateTaskWhenDismissed does not exist there.
    private boolean hasTranslateTaskWhenDismissed = true;
    // True only if RecentsView itself overrides getPageSpacing() (see initHooks).
    private boolean pageSpacingGetterHooked = false;

    public RecentsHook(ClassLoader classLoader) {
        this.classLoader = classLoader;
        this.prefs = new XSharedPreferences("com.buildsession.aospstackingrecent");
        
        try {
            java.io.File file = this.prefs.getFile();
            XposedBridge.log("AOSPStackingRecent: Checking prefs at: " + file.getAbsolutePath());
            if (file.exists()) {
                XposedBridge.log("AOSPStackingRecent: Prefs file found. Readable: " + file.canRead());
            } else {
                XposedBridge.log("AOSPStackingRecent: Prefs file NOT found! This usually means MainActivity hasn't saved settings yet or permissions are restricted.");
            }
        } catch (Throwable t) {
            XposedBridge.log("AOSPStackingRecent: Error checking prefs file: " + t.getMessage());
        }
    }

    public void initHooks() {
        XposedBridge.log("AOSPStackingRecent: Initializing hooks for ClassLoader: " + classLoader);
        try {
            recentsViewClass = XposedHelpers.findClassIfExists("com.android.quickstep.views.RecentsView", classLoader);
            taskViewClass = XposedHelpers.findClassIfExists("com.android.quickstep.views.TaskView", classLoader);
            orientationHandlerClass = XposedHelpers.findClassIfExists("com.android.launcher3.touch.PagedOrientationHandler", classLoader);

            if (recentsViewClass == null || taskViewClass == null || orientationHandlerClass == null) {
                XposedBridge.log("AOSPStackingRecent: Critical classes not found, aborting.");
                return;
            }

            // Probing Fields
            persistentTranslationXField = findField(taskViewClass, "persistentTranslationX", "mPersistentTranslationX");
            nonGridTranslationXField = findField(taskViewClass, "nonGridTranslationX", "mNonGridTranslationX");
            dismissTranslationXField = findField(taskViewClass, "dismissTranslationX", "mDismissTranslationX");

            // Probing Methods
            getPagedOrientationHandlerMethod = findMethod(recentsViewClass, "getPagedOrientationHandler");
            getPrimaryScrollMethod = findMethod(orientationHandlerClass, "getPrimaryScroll", View.class);
            getMeasuredSizeMethod = findMethod(orientationHandlerClass, "getMeasuredSize", View.class);
            getChildStartMethod = findMethod(orientationHandlerClass, "getChildStart", View.class);
            
            // Hook getOffsetAdjustment to report our stack offset to Launcher's layout/snapping logic.
            // This ensures that snap-to-page and hit-testing work correctly with our shifts.
            Method getOffsetAdjustmentMethod = findMethod(taskViewClass, "getOffsetAdjustment");
            if (getOffsetAdjustmentMethod != null) {
                XposedBridge.hookMethod(getOffsetAdjustmentMethod, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        View child = (View) param.thisObject;
                        Float extra = (Float) XposedHelpers.getAdditionalInstanceField(child, "apple_stack_offset");
                        if (extra != null && extra != 0f) {
                            float original = (float) param.getResult();
                            param.setResult(original + extra);
                        }
                    }
                });
                XposedBridge.log("AOSPStackingRecent: Hooked getOffsetAdjustment");
             }

             // Hook getPageSpacing to physically bring cards closer and reduce scroll distance
             // NOTE: On Android 13 based Quickstep (e.g. Lawnchair 14) getPageSpacing() is declared in
             // PagedView, which is also the base class of Workspace / WidgetPagedView. Hooking it would
             // change the home screen too, and since it just returns mPageSpacing (which we write
             // ourselves in updatePageSpacingField) the offset would be applied twice. So we only hook it
             // when RecentsView itself overrides it, and otherwise only touch the mPageSpacing field.
             Method getPageSpacingMethod = findMethod(recentsViewClass, "getPageSpacing");
             pageSpacingGetterHooked = getPageSpacingMethod != null
                     && getPageSpacingMethod.getDeclaringClass() == recentsViewClass;
             if (pageSpacingGetterHooked) {
                 XposedBridge.hookMethod(getPageSpacingMethod, new XC_MethodHook() {
                     @Override
                     protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                         prefs.reload();
                         float spacingFactor = prefs.getFloat(KEY_SPACING, DEFAULT_SPACING);
                         int original = (int) param.getResult();
                         float deltaFactor = spacingFactor - DEFAULT_SPACING;
                         int deltaPx = Math.round(deltaFactor * 10f);
                         param.setResult(original + deltaPx - 120);
                     }
                 });
                 XposedBridge.log("AOSPStackingRecent: Hooked getPageSpacing");
             }

             // Unify entry state: Hook onStateTransitionStart and onGestureAnimationStart
             // to ensure the internal mPageSpacing field is updated before any animation
             Class<?> launcherRecentsViewClass = XposedHelpers.findClassIfExists("com.android.quickstep.views.LauncherRecentsView", classLoader);
             final Class<?> overviewStateClass = XposedHelpers.findClassIfExists(
                     "com.android.launcher3.uioverrides.states.OverviewState", classLoader);
             XC_MethodHook entryHook = new XC_MethodHook() {
                 @Override
                 protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                     updatePageSpacingField(param.thisObject);
                     // A new gesture, or entering Overview, ends the "quick switch left an old card
                     // hidden" state (see the setRunningTaskHidden hook below).
                     String name = param.method.getName();
                     boolean newGesture = name.equals("onGestureAnimationStart");
                     boolean enteringOverview = name.equals("onStateTransitionStart")
                             && param.args != null && param.args.length > 0
                             && overviewStateClass != null && overviewStateClass.isInstance(param.args[0]);
                     // Entering the Overview state is part of the release animation of a gesture, so it must not
                     // cancel the "gather" animation (that made the cards stop short and then snap into place).
                     if (newGesture || (enteringOverview && !isGestureActive(param.thisObject))) {
                         XposedHelpers.setAdditionalInstanceField(param.thisObject, "apple_forced_hidden_view", null);
                         XposedHelpers.setAdditionalInstanceField(param.thisObject, "apple_gesture_running_view", null);
                     }
                     if (newGesture) {
                         XposedHelpers.setAdditionalInstanceField(param.thisObject, "apple_gather_until", 0L);
                         XposedHelpers.setAdditionalInstanceField(param.thisObject, "apple_last_end_target", null);
                         XposedHelpers.setAdditionalInstanceField(param.thisObject, "apple_was_hold", false);
                     }
                 }
             };

             // Launcher keeps Recents "detached" from the app window while the finger is down: the content alpha is 0
             // and every card beside the running one is pushed away by ADJACENT_PAGE_HORIZONTAL_OFFSET
             // (updatePageOffsets). That is why no neighbouring card was visible. During a gesture (and the
             // short "gather" animation after it) the stock offset is neutralised, and the cards are placed by
             // our own layout and smoothing instead, so the two animations no longer fight each other.
             XposedBridge.hookAllMethods(recentsViewClass, "updatePageOffsets", new XC_MethodHook() {
                 @Override
                 protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                     try {
                         if (!shouldNeutraliseStockOffsets(param.thisObject)) return;
                         float stock = XposedHelpers.getFloatField(param.thisObject, "mAdjacentPageHorizontalOffset");
                         XposedHelpers.setAdditionalInstanceField(param.thisObject, "apple_saved_adjacent_offset", stock);
                         XposedHelpers.setFloatField(param.thisObject, "mAdjacentPageHorizontalOffset", 0f);
                     } catch (Throwable ignored) {
                     }
                 }

                 @Override
                 protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                     try {
                         Object saved = XposedHelpers.getAdditionalInstanceField(param.thisObject, "apple_saved_adjacent_offset");
                         if (saved instanceof Float) {
                             XposedHelpers.setFloatField(param.thisObject, "mAdjacentPageHorizontalOffset", (Float) saved);
                             XposedHelpers.setAdditionalInstanceField(param.thisObject, "apple_saved_adjacent_offset", null);
                         }
                     } catch (Throwable ignored) {
                     }
                 }
             });

             // The stock fade sets the cards' alpha to 0 (content alpha); re-apply our layout right away so
             // the neighbouring cards are not left invisible until the next scroll update.
             XposedBridge.hookAllMethods(recentsViewClass, "setContentAlpha", new XC_MethodHook() {
                 @Override
                 protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                     // While the finger is still down the stock code keeps Recents fully transparent
                     // (content alpha 0, which even makes RecentsView INVISIBLE), so the neighbouring cards
                     // could never be seen. Keep the content visible during the hold phase; the running
                     // card stays hidden by the stock code and every end target animates as usual.
                     try {
                         if (param.args != null && param.args.length == 1 && param.args[0] instanceof Float
                                 && (Float) param.args[0] < 1f
                                 && isGestureActive(param.thisObject)
                                 && getEndTargetName(param.thisObject) == null) {
                             param.args[0] = 1f;
                         }
                     } catch (Throwable ignored) {
                     }
                 }

                 @Override
                 protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                     try {
                         if (isGestureActive(param.thisObject)) {
                             XposedHelpers.callMethod(param.thisObject, "updateCurveProperties");
                         }
                     } catch (Throwable ignored) {
                     }
                 }
             });

             // Start the "gather" animation the moment the finger is released into Overview, instead of
             // waiting for the next layout pass.
             XposedBridge.hookAllMethods(recentsViewClass, "onPrepareGestureEndAnimation", new XC_MethodHook() {
                 @Override
                 protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                     try {
                         if ("RECENTS".equals(getEndTargetName(param.thisObject))) {
                             XposedHelpers.setAdditionalInstanceField(param.thisObject, "apple_last_end_target", "RECENTS");
                             XposedHelpers.setAdditionalInstanceField(param.thisObject, "apple_gather_until",
                                     SystemClock.uptimeMillis() + GATHER_DURATION_MS);
                             XposedHelpers.callMethod(param.thisObject, "updateCurveProperties");
                         }
                     } catch (Throwable ignored) {
                     }
                 }
             });

             // When a swipe ends by switching to ANOTHER app (end target NEW_TASK), Launcher "un-hides" the
             // card of the app we are leaving (setRunningTaskHidden(false)). With the stack layout that
             // card is pulled towards the screen centre, so a sliver of it shows at the screen edge next to
             // the new app. Keep that one card hidden until the next gesture / Overview.
             XposedBridge.hookAllMethods(recentsViewClass, "setRunningTaskHidden", new XC_MethodHook() {
                 @Override
                 protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                     try {
                         if (param.args == null || param.args.length != 1 || !(param.args[0] instanceof Boolean)
                                 || (Boolean) param.args[0]) {
                             return;
                         }
                         if (!isNewTaskEndTarget(param.thisObject)) return;
                         Object card = XposedHelpers.getAdditionalInstanceField(param.thisObject, "apple_gesture_running_view");
                         if (!(card instanceof View)) {
                             card = getRunningTaskViewSafe(param.thisObject);
                         }
                         if (card instanceof View) {
                             XposedHelpers.setAdditionalInstanceField(param.thisObject, "apple_forced_hidden_view", card);
                             ((View) card).setAlpha(0f);
                         }
                     } catch (Throwable ignored) {
                     }
                 }
             });

             if (launcherRecentsViewClass != null) {
                 XposedBridge.hookAllMethods(launcherRecentsViewClass, "onStateTransitionStart", entryHook);
             }
             XposedBridge.hookAllMethods(recentsViewClass, "onGestureAnimationStart", entryHook);

             // Additional hooks for early state synchronization
             // onMeasure is declared in PagedView on Android 13 (not in RecentsView), so hook the declaring
             // class and filter to RecentsView instances.
             Class<?> onMeasureOwner = recentsViewClass;
             while (onMeasureOwner != null
                     && XposedHelpers.findMethodExactIfExists(onMeasureOwner, "onMeasure", int.class, int.class) == null) {
                 onMeasureOwner = onMeasureOwner.getSuperclass();
             }
             if (onMeasureOwner != null && onMeasureOwner != View.class && onMeasureOwner != ViewGroup.class) {
                 XposedBridge.hookAllMethods(onMeasureOwner, "onMeasure", new XC_MethodHook() {
                     @Override
                     protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                         if (recentsViewClass.isInstance(param.thisObject)) {
                             updatePageSpacingField(param.thisObject);
                         }
                     }
                 });
             }

             XposedBridge.hookAllMethods(recentsViewClass, "onVisibilityChanged", new XC_MethodHook() {
                 @Override
                 protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                     if (param.args != null && param.args.length >= 2 && (int)param.args[1] == View.VISIBLE) {
                         updatePageSpacingField(param.thisObject);
                     }
                 }
             });

             // Ensure mPageSpacing is updated for dismiss animations too (createTaskDismissAnimation uses the field)
             Class<?> pendingAnimationClass = XposedHelpers.findClassIfExists(
                     "com.android.launcher3.anim.PendingAnimation", classLoader);
             if (pendingAnimationClass != null) {
                 Method createTaskDismissAnimationMethod = findMethod(
                         recentsViewClass,
                         "createTaskDismissAnimation",
                         pendingAnimationClass,
                         taskViewClass,
                         boolean.class,
                         boolean.class,
                         long.class,
                         boolean.class
                 );
                 if (createTaskDismissAnimationMethod != null) {
                     XposedBridge.hookMethod(createTaskDismissAnimationMethod, new XC_MethodHook() {
                         @Override
                         protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                             updatePageSpacingField(param.thisObject);
                         }
                     });
                     XposedBridge.log("AOSPStackingRecent: Hooked createTaskDismissAnimation (sync page spacing)");
                 }
             }

             // Hook TaskView.applyTranslationX and Y to inject our stack offset
            // NOTE: On many Launcher versions, applyTranslationX already calls getOffsetAdjustment().
            // If it does, our hook above handles it. If it doesn't, we might need this hook as a fallback.
            // We'll keep this hook but ONLY apply the subtraction of dismissTranslationX here,
            // to avoid double-adding the apple_stack_offset if getOffsetAdjustment is already working.
            Method applyTranslationXMethod = findMethod(taskViewClass, "applyTranslationX");
            if (applyTranslationXMethod != null) {
                XposedBridge.hookMethod(applyTranslationXMethod, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        View child = (View) param.thisObject;
                        
                        // We restore the manual addition of apple_stack_offset here.
                        // While getOffsetAdjustment hook handles logic/snapping, 
                        // many Launcher versions don't actually call getOffsetAdjustment() 
                        // inside their applyTranslationX() implementation for the visual update.
                        Float extra = (Float) XposedHelpers.getAdditionalInstanceField(child, "apple_stack_offset");
                        if (extra != null && extra != 0f) {
                            child.setTranslationX(child.getTranslationX() + extra);
                        }
                        
                        // IMPORTANT: When a task is being dismissed, Launcher applies its own 'dismissTranslationX'.
                        // We counteract it because we handle the gap-filling ourselves via physical linkage.
                        if (dismissTranslationXField != null) {
                            try {
                                float dismissTx = dismissTranslationXField.getFloat(child);
                                if (Math.abs(dismissTx) > 0.1f) {
                                    child.setTranslationX(child.getTranslationX() - dismissTx);
                                }
                            } catch (Throwable ignored) {}
                        }
                    }
                });
            }

            Method applyTranslationYMethod = findMethod(taskViewClass, "applyTranslationY");
            if (applyTranslationYMethod != null) {
                XposedBridge.hookMethod(applyTranslationYMethod, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        View child = (View) param.thisObject;
                        Float extra = (Float) XposedHelpers.getAdditionalInstanceField(child, "apple_stack_offset_y");
                        if (extra != null && extra != 0f) {
                            child.setTranslationY(child.getTranslationY() + extra);
                        }
                    }
                });
            }

            // Hook resetViewTransforms to maintain our Z-index and force re-apply our stack translation
             Method resetViewTransformsMethod = findMethod(taskViewClass, "resetViewTransforms");
             if (resetViewTransformsMethod != null) {
                 XposedBridge.hookMethod(resetViewTransformsMethod, new XC_MethodHook() {
                     @Override
                     protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                         View child = (View) param.thisObject;
                         Float targetZ = (Float) XposedHelpers.getAdditionalInstanceField(child, "apple_target_z");
                         if (targetZ != null) {
                             child.setTranslationZ(targetZ);
                         }
                         // Re-apply our custom X/Y translation after the system reset
                         XposedHelpers.callMethod(child, "applyTranslationX");
                         XposedHelpers.callMethod(child, "applyTranslationY");
                     }
                 });
             }

            Method updateCurvePropertiesMethod = findMethod(recentsViewClass, "updateCurveProperties");
            if (updateCurvePropertiesMethod != null) {
                XposedBridge.hookMethod(updateCurvePropertiesMethod, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        handleUpdateCurveProperties(param);
                    }
                });
                XposedBridge.log("AOSPStackingRecent: Successfully hooked updateCurveProperties");
            }

            XposedBridge.hookAllMethods(recentsViewClass, "dispatchDraw", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    try {
                        long nowDraw = SystemClock.uptimeMillis();
                        Object untilObj = XposedHelpers.getAdditionalInstanceField(param.thisObject, "apple_dismiss_until");
                        Object gatherObj = XposedHelpers.getAdditionalInstanceField(param.thisObject, "apple_gather_until");
                        boolean dismissing = untilObj instanceof Long && nowDraw < (Long) untilObj;
                        boolean gatheringNow = gatherObj instanceof Long && nowDraw < (Long) gatherObj;
                        // Keep recomputing the stack every frame during dismiss and "gather" animations.
                        if (dismissing || gatheringNow) {
                            XposedHelpers.callMethod(param.thisObject, "updateCurveProperties");
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });

            XposedBridge.hookAllMethods(recentsViewClass, "createTaskDismissAnimation", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    updatePageSpacingField(param.thisObject);
                    try {
                        if (param.args != null && param.args.length >= 5) {
                            Object durationObj = param.args[4];
                            long duration = durationObj instanceof Long ? (Long) durationObj
                                    : durationObj instanceof Integer ? ((Integer) durationObj).longValue()
                                    : 0L;
                            XposedHelpers.setAdditionalInstanceField(param.thisObject, "apple_dismiss_until",
                                    SystemClock.uptimeMillis() + duration + 200L);
                        }
                        // Android 13 has no translateTaskWhenDismissed(); the page distance Launcher uses for
                        // the gap-closing slide is computed inline, so derive the same step here
                        // (|scroll(page1) - scroll(page0)|) for the "physical linkage" logic.
                        if (!hasTranslateTaskWhenDismissed && ((ViewGroup) param.thisObject).getChildCount() > 1) {
                            int s0 = (int) XposedHelpers.callMethod(param.thisObject, "getScrollForPage", 0);
                            int s1 = (int) XposedHelpers.callMethod(param.thisObject, "getScrollForPage", 1);
                            float step = Math.abs(s1 - s0);
                            if (step > 0f) {
                                XposedHelpers.setAdditionalInstanceField(param.thisObject,
                                        "apple_last_scroll_step", step);
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });

            hasTranslateTaskWhenDismissed = false;
            for (Method m : recentsViewClass.getDeclaredMethods()) {
                if (m.getName().equals("translateTaskWhenDismissed")) {
                    hasTranslateTaskWhenDismissed = true;
                    break;
                }
            }
            XposedBridge.log("AOSPStackingRecent: translateTaskWhenDismissed present: " + hasTranslateTaskWhenDismissed);
            XposedBridge.hookAllMethods(recentsViewClass, "translateTaskWhenDismissed", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    handleTranslateTaskWhenDismissed(param);
                }
            });

            // Fallback for interaction: hook getTaskViewAt if getOffsetAdjustment is not enough
            Method getTaskViewAtMethod = findMethod(recentsViewClass, "getTaskViewAt", float.class, float.class);
            if (getTaskViewAtMethod != null) {
                XposedBridge.hookMethod(getTaskViewAtMethod, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.getResult() == null) {
                            handleGetTaskViewAt(param);
                        }
                    }
                });
            }

        } catch (Throwable t) {
            XposedBridge.log("AOSPStackingRecent: Error during initHooks: " + Log.getStackTraceString(t));
        }
    }

    private Field findField(Class<?> clazz, String... names) {
        for (String name : names) {
            Field f = XposedHelpers.findFieldIfExists(clazz, name);
            if (f != null) {
                f.setAccessible(true);
                return f;
            }
        }
        return null;
    }

    private Method findMethod(Class<?> clazz, String name, Class<?>... parameterTypes) {
        Method m = XposedHelpers.findMethodExactIfExists(clazz, name, (Object[]) parameterTypes);
        if (m != null) {
            m.setAccessible(true);
            return m;
        }
        return null;
    }

    private float lastSpacingFactor = -1;
    private float lastTightnessFactor = -1;
    private long lastDismissHookLogUptime;

    /** True while Launcher hides the running app's card during a swipe (the live app window is drawn instead). */
    private static boolean isRunningTaskTileHidden(Object recentsView) {
        try {
            return XposedHelpers.getBooleanField(recentsView, "mRunningTaskTileHidden");
        } catch (Throwable t) {
            return false;
        }
    }

    private static View getRunningTaskViewSafe(Object recentsView) {
        try {
            Object v = XposedHelpers.callMethod(recentsView, "getRunningTaskView");
            return v instanceof View ? (View) v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Name of the GestureEndTarget chosen when the finger is released (RECENTS, NEW_TASK, ...); null while the finger is down. */
    private static String getEndTargetName(Object recentsView) {
        try {
            Object target = XposedHelpers.getObjectField(recentsView, "mCurrentGestureEndTarget");
            return target == null ? null : String.valueOf(target);
        } catch (Throwable t) {
            return null;
        }
    }

    /** True when the current swipe is ending by switching to another app (GestureEndTarget.NEW_TASK). */
    private static boolean isNewTaskEndTarget(Object recentsView) {
        return "NEW_TASK".equals(getEndTargetName(recentsView));
    }

    /** True while our own layout / gather animation owns the horizontal card offsets. */
    private static boolean shouldNeutraliseStockOffsets(Object recentsView) {
        if (isGestureActive(recentsView)) return true;
        Object until = XposedHelpers.getAdditionalInstanceField(recentsView, "apple_gather_until");
        return until instanceof Long && SystemClock.uptimeMillis() < (Long) until;
    }

    private static float easeTo(float current, float target, float factor) {
        return current + (target - current) * factor;
    }

    /** True from the start of a swipe-up from an app until the whole gesture animation has finished. */
    private static boolean isGestureActive(Object recentsView) {
        try {
            return XposedHelpers.getBooleanField(recentsView, "mGestureActive");
        } catch (Throwable t) {
            return false;
        }
    }

    // After the finger is released into Overview the hidden cards slide into the stack and fade in.
    private static final long GATHER_DURATION_MS = 450;
    // Gap between neighbouring cards during the hold phase, as a fraction of the Recents size.
    private static final float HOLD_GAP_FRACTION = 0.03f;
    private static final float GATHER_TAU_MS = 90f;

    private void handleUpdateCurveProperties(XC_MethodHook.MethodHookParam param) {
        ViewGroup recentsView = (ViewGroup) param.thisObject;
        if (recentsView == null) return;

        try {
            if (getPagedOrientationHandlerMethod == null) return;
            Object handler = getPagedOrientationHandlerMethod.invoke(recentsView);
            if (handler == null) return;

            // Reload prefs to get dynamic changes
            prefs.reload();
            float spacingFactor = prefs.getFloat(KEY_SPACING, DEFAULT_SPACING);
            float tightnessFactor = prefs.getFloat(KEY_TIGHTNESS, DEFAULT_TIGHTNESS);
            if (spacingFactor != lastSpacingFactor || tightnessFactor != lastTightnessFactor) {
                XposedBridge.log("AOSPStackingRecent: Parameters updated - Spacing: " + spacingFactor + ", Tightness: " + tightnessFactor);
                lastSpacingFactor = spacingFactor;
                lastTightnessFactor = tightnessFactor;
            }

            int scroll = (int) getPrimaryScrollMethod.invoke(handler, recentsView);
            int measuredSize = (int) getMeasuredSizeMethod.invoke(handler, recentsView);
            
            if (measuredSize <= 0) return; // Avoid invalid calculations on first frame

            float center = scroll + measuredSize / 2f;
            
            // Physics Linkage Fallback:
            // When entering from an app, scroll is often 0 initially while the 'Running Task' 
            // is actually the primary focus. We use the running task's logical center 
            // as our reference to prevent all cards from overlapping at the beginning.
            try {
                View runningTask = (View) XposedHelpers.callMethod(recentsView, "getRunningTaskView");
                if (runningTask != null) {
                    // Check if scroll is 0 but running task is at a later index
                    int runningIndex = (int) XposedHelpers.callMethod(recentsView, "indexOfChild", runningTask);
                    if (scroll == 0 && runningIndex > 0) {
                        int childStart = (int) getChildStartMethod.invoke(handler, runningTask);
                        int childSize = (int) getMeasuredSizeMethod.invoke(handler, runningTask);
                        center = childStart + childSize / 2f;
                    }
                }
            } catch (Throwable ignored) {}

            long nowUptime = SystemClock.uptimeMillis();
            boolean isDismissing = false;
            long dismissUntil = 0L;
            try {
                Object untilObj = XposedHelpers.getAdditionalInstanceField(recentsView, "apple_dismiss_until");
                if (untilObj instanceof Long) {
                    dismissUntil = (Long) untilObj;
                    isDismissing = nowUptime < dismissUntil;
                }
            } catch (Throwable ignored) {
            }

            int childCount = recentsView.getChildCount();

            // During a swipe from an app Launcher hides the running app's card (alpha 0) because the live
            // app window is drawn instead. That card is still logically there, so the other cards must
            // stack behind it exactly as if it were visible; otherwise the neighbouring card sits right under
            // it with no offset and then jumps into place as soon as the card is shown again.
            View hiddenRunning = isRunningTaskTileHidden(recentsView) ? getRunningTaskViewSafe(recentsView) : null;
            if (hiddenRunning != null) {
                XposedHelpers.setAdditionalInstanceField(recentsView, "apple_gesture_running_view", hiddenRunning);
            }
            // The card of the app we just left after a quick switch to another app: stays hidden.
            Object forcedObj = XposedHelpers.getAdditionalInstanceField(recentsView, "apple_forced_hidden_view");
            View forcedHidden = forcedObj instanceof View ? (View) forcedObj : null;

            // --- Gesture "hold" phase -------------------------------------------------------------------
            // While the finger is still down during a swipe from an app (no end target chosen yet) the cards
            // are laid out in a plain line with a small, constant gap between neighbours: no stacking, scaling
            // or fading. Cards further away simply stay beyond the screen edges. When the finger is released
            // into Overview every card slides from that line into the stack ("gather").
            long frameNow = SystemClock.uptimeMillis();
            String endTargetName = getEndTargetName(recentsView);
            boolean holdPhase = isGestureActive(recentsView) && !"RECENTS".equals(endTargetName);
            Object wasHoldObj = XposedHelpers.getAdditionalInstanceField(recentsView, "apple_was_hold");
            boolean wasHold = wasHoldObj instanceof Boolean && (Boolean) wasHoldObj;
            if (endTargetName != null) {
                XposedHelpers.setAdditionalInstanceField(recentsView, "apple_last_end_target", endTargetName);
            }
            if (wasHold && !holdPhase) {
                Object lastTarget = XposedHelpers.getAdditionalInstanceField(recentsView, "apple_last_end_target");
                if ("RECENTS".equals(lastTarget) || "RECENTS".equals(endTargetName)) {
                    XposedHelpers.setAdditionalInstanceField(recentsView, "apple_gather_until", frameNow + GATHER_DURATION_MS);
                }
            }
            XposedHelpers.setAdditionalInstanceField(recentsView, "apple_was_hold", holdPhase);

            Object gatherUntilObj = XposedHelpers.getAdditionalInstanceField(recentsView, "apple_gather_until");
            long gatherUntil = gatherUntilObj instanceof Long ? (Long) gatherUntilObj : 0L;
            boolean gathering = !holdPhase && frameNow < gatherUntil;
            Object lastCurveObj = XposedHelpers.getAdditionalInstanceField(recentsView, "apple_last_curve_time");
            float dtMs = lastCurveObj instanceof Long ? clamp(frameNow - (Long) lastCurveObj, 1f, 64f) : 16f;
            XposedHelpers.setAdditionalInstanceField(recentsView, "apple_last_curve_time", frameNow);
            float gatherLerp = 1f - (float) Math.exp(-dtMs / GATHER_TAU_MS);

            // Extra translation (relative to the natural layout) that turns the natural layout into the
            // linear hold layout, indexed by child index. null outside of the hold phase.
            float[] holdExtra = null;
            if (holdPhase) {
                int n = 0;
                int[] taskChild = new int[childCount];
                float[] dist = new float[childCount];
                float[] size = new float[childCount];
                for (int i = 0; i < childCount; i++) {
                    View c = recentsView.getChildAt(i);
                    if (c == null || !taskViewClass.isInstance(c)) continue;
                    int cSize = (int) getMeasuredSizeMethod.invoke(handler, c);
                    taskChild[n] = i;
                    size[n] = cSize;
                    dist[n] = (int) getChildStartMethod.invoke(handler, c) + cSize / 2f - center;
                    n++;
                }
                // The layout can be mirrored (child 0 on the right), so order the cards by their on-screen
                // position (ascending distance from the centre) before working out the linear layout.
                for (int a = 1; a < n; a++) {
                    int tc = taskChild[a];
                    float td = dist[a];
                    float ts = size[a];
                    int b = a - 1;
                    while (b >= 0 && dist[b] > td) {
                        taskChild[b + 1] = taskChild[b];
                        dist[b + 1] = dist[b];
                        size[b + 1] = size[b];
                        b--;
                    }
                    taskChild[b + 1] = tc;
                    dist[b + 1] = td;
                    size[b + 1] = ts;
                }
                if (n >= 2) {
                    // Fractional position of the screen centre between the cards (0 .. n-1).
                    int p = -1;
                    for (int k = 0; k < n; k++) {
                        if (dist[k] <= 0f) p = k;
                    }
                    float fi;
                    if (p < 0) {
                        fi = 0f;
                    } else if (p >= n - 1) {
                        fi = n - 1;
                    } else {
                        float span = dist[p + 1] - dist[p];
                        fi = span > 1f ? p + (-dist[p]) / span : p;
                    }
                    int q = Math.max(0, Math.min(p, n - 2));
                    float naturalStep = dist[q + 1] - dist[q];
                    float focusSize = size[Math.max(0, Math.min(n - 1, Math.round(fi)))];
                    float gap = measuredSize * HOLD_GAP_FRACTION;
                    float delta = (focusSize + gap) - naturalStep;
                    holdExtra = new float[childCount];
                    for (int k = 0; k < n; k++) {
                        holdExtra[taskChild[k]] = delta * (k - fi);
                    }
                }
            }

            // Diagnostics (throttled): lets us see what the hold layout really does on the device.
            try {
                Object lastLogObj = XposedHelpers.getAdditionalInstanceField(recentsView, "apple_last_log");
                long lastLog = lastLogObj instanceof Long ? (Long) lastLogObj : 0L;
                if (isGestureActive(recentsView) && frameNow - lastLog > 150) {
                    XposedHelpers.setAdditionalInstanceField(recentsView, "apple_last_log", frameNow);
                    StringBuilder sb = new StringBuilder("AOSPStackingRecent DIAG hold=").append(holdPhase)
                            .append(" gather=").append(gathering).append(" end=").append(endTargetName)
                            .append(" rvVis=").append(recentsView.getVisibility())
                            .append(" rvAlpha=").append(recentsView.getAlpha())
                            .append(" rvScale=").append(recentsView.getScaleX())
                            .append(" rvTx=").append(recentsView.getTranslationX())
                            .append(" W=").append(recentsView.getWidth())
                            .append(" center=").append(center).append(" measured=").append(measuredSize)
                            .append(" holdExtra=").append(holdExtra != null)
                            .append(" hiddenRunning=").append(hiddenRunning != null);
                    for (int i = 0; i < childCount; i++) {
                        View c = recentsView.getChildAt(i);
                        if (c == null || !taskViewClass.isInstance(c)) continue;
                        sb.append(" | #").append(i).append(" l=").append(c.getLeft()).append(" w=").append(c.getWidth())
                                .append(" tx=").append(c.getTranslationX()).append(" a=").append(c.getAlpha())
                                .append(" vis=").append(c.getVisibility())
                                .append(" sc=").append(c.getScaleX())
                                .append(" he=").append(holdExtra != null ? holdExtra[i] : Float.NaN);
                    }
                    XposedBridge.log(sb.toString());
                }
            } catch (Throwable t) {
                XposedBridge.log("AOSPStackingRecent DIAG failed: " + t);
            }

            // First pass: Calculate distance and weights for all cards
            for (int i = 0; i < childCount; i++) {
                View child = recentsView.getChildAt(i);
                if (child == null || !taskViewClass.isInstance(child)) continue;
                if (child == forcedHidden) {
                    child.setAlpha(0f);
                }

                int childStart = (int) getChildStartMethod.invoke(handler, child);
                int childSize = (int) getMeasuredSizeMethod.invoke(handler, child);
                float childCenter = childStart + childSize / 2f;
                float distance = childCenter - center;
                float absDistance = Math.abs(distance);

                XposedHelpers.setAdditionalInstanceField(child, "apple_stack_distance", distance);
                XposedHelpers.setAdditionalInstanceField(child, "apple_stack_abs_distance", absDistance);

                // Calculate dismiss progress from both manual drag (translationY) and auto animation (alpha)
                float transY = Math.abs(child.getTranslationY());
                // Use 35% of height as the point where the card is "fully gone" from layout perspective
                float dismissThreshold = child.getHeight() * 0.35f;
                float dragProgress = clamp(transY / dismissThreshold, 0f, 1f);
                float alphaProgress = clamp(1.0f - child.getAlpha(), 0f, 1f);
                
                // Total progress is the maximum of drag and alpha animations
                float totalProgress = Math.max(dragProgress, alphaProgress);

                // If it's completely gone, weight is 0. Otherwise, it's 1.0 -> 0.0
                float weight = (child.getVisibility() != View.VISIBLE || child.getAlpha() < 0.05f) ? 0f : (1.0f - totalProgress);
                if (child == hiddenRunning) {
                    // Hidden only because of the live window, not dismissed: count it fully, no dismiss pull.
                    totalProgress = 0f;
                    weight = 1f;
                }
                XposedHelpers.setAdditionalInstanceField(child, "apple_dismiss_progress", totalProgress);
                XposedHelpers.setAdditionalInstanceField(child, "apple_layout_weight", weight);
            }

            // Second pass: Apply physics and calculate smooth rank-based positions
            for (int i = 0; i < childCount; i++) {
                View child = recentsView.getChildAt(i);
                if (child == null || !taskViewClass.isInstance(child)) continue;

                Float distanceObj = (Float) XposedHelpers.getAdditionalInstanceField(child, "apple_stack_distance");
                Float weightObj = (Float) XposedHelpers.getAdditionalInstanceField(child, "apple_layout_weight");
                if (distanceObj == null || weightObj == null) continue;

                float originalDistance = distanceObj;
                float selfWeight = weightObj;

                // The hidden running card keeps its place and is not restyled (that would make it visible).
                if (child == hiddenRunning) {
                    XposedHelpers.setAdditionalInstanceField(child, "apple_stack_offset", 0f);
                    XposedHelpers.setAdditionalInstanceField(child, "apple_target_z", 0f);
                    continue;
                }

                // Hold phase: plain linear layout with a small gap, nothing stacked, scaled or faded.
                if (holdExtra != null) {
                    XposedHelpers.setAdditionalInstanceField(child, "apple_stack_offset", holdExtra[i]);
                    XposedHelpers.setAdditionalInstanceField(child, "apple_target_z", 0f);
                    child.setTranslationZ(0f);
                    child.setScaleX(1f);
                    child.setScaleY(1f);
                    if (child != forcedHidden) child.setAlpha(1f);
                    XposedHelpers.callMethod(child, "applyTranslationX");
                    continue;
                }

                // Right after the finger is released into Overview every card eases from the linear layout
                // into its place in the stack (offset, scale and alpha are smoothed for a short while).
                boolean gatherThis = gathering;

                // Reset for gone cards
                if (selfWeight <= 0f && (child.getVisibility() != View.VISIBLE || child.getAlpha() < 0.01f)) {
                    XposedHelpers.setAdditionalInstanceField(child, "apple_stack_offset", 0f);
                    XposedHelpers.setAdditionalInstanceField(child, "apple_target_z", 0f);
                    continue;
                }

                if (originalDistance < 0) { // Left side stacking
                    float effectiveRank = 0f;
                    float dismissPullProgress = 0f; 
                    
                    for (int j = 0; j < childCount; j++) {
                        View other = recentsView.getChildAt(j);
                        if (other == null || other == child || !taskViewClass.isInstance(other)) continue;
                        
                        Float otherDistance = (Float) XposedHelpers.getAdditionalInstanceField(other, "apple_stack_distance");
                        Float otherWeight = (Float) XposedHelpers.getAdditionalInstanceField(other, "apple_layout_weight");
                        Float otherProgress = (Float) XposedHelpers.getAdditionalInstanceField(other, "apple_dismiss_progress");
                        if (otherDistance == null || otherWeight == null || otherProgress == null) continue;

                        // Rank: Only count visible cards that are NOT being dismissed.
                        // We use a smooth transition (100px range) instead of a 1px hard cut to avoid jitter.
                        if (otherDistance > originalDistance && otherWeight > 0.01f) {
                            float rankWeight = clamp((otherDistance - 50f) / 100f, 0f, 1f);
                            if (rankWeight < 1.0f) {
                                effectiveRank += otherWeight * (1.0f - rankWeight);
                            }
                        }

                        // Pull: ONLY count cards actively being swiped up
                        // We restrict this to cards between us and the focus center (plus a buffer)
                        // to avoid right-side dismissals from pulling left-side cards.
                        if (otherDistance > originalDistance && otherDistance < 50f && otherProgress > 0.01f) {
                            dismissPullProgress += (1.0f - otherWeight);
                        }
                    }

                    // --- PHYSICAL LINKAGE CORE ---
                    float scrollUnit = 0f;
                    Object savedStep = XposedHelpers.getAdditionalInstanceField(recentsView, "apple_last_scroll_step");
                    if (savedStep instanceof Float) {
                        scrollUnit = (Float) savedStep;
                    }

                    if (scrollUnit <= 0) {
                        scrollUnit = measuredSize * 0.75f;
                    }

                    // Pull Unloading Mechanism:
                    // As the card approaches the physical center (originalDistance -> 0), 
                    // we fade out the virtual pull because the manual scroll has "filled the gap".
                    float pullFade = clamp(-originalDistance / scrollUnit, 0f, 1f);
                    float effectivePull = dismissPullProgress * scrollUnit * pullFade;
                    
                    // virtualDistance should be 0 when at center
                    float virtualDistance = originalDistance + effectivePull;
                    float absVirtualDistance = Math.abs(virtualDistance);
                    
                    float peek = clamp(spacingFactor * 14f, measuredSize * 0.03f, measuredSize * 0.12f);
                    float stackTarget = -effectiveRank * peek;

                    float baseK = spacingFactor * 55f;
                    float exponent = 1.20f + (tightnessFactor * 0.80f) + (effectiveRank * 0.05f);
                    float hill = (float) (1.0 / (1.0 + Math.pow(absVirtualDistance / baseK, exponent)));

                    float finalTargetPos = (virtualDistance * hill) + (stackTarget * (1f - hill));
                    float extraTranslation = finalTargetPos - originalDistance;
                    
                    // Use higher responsiveness for manual scrolling.
                    // Only use smooth lerp when the card is being pulled purely by a dismissal.
                    Float currentOffsetObj = (Float) XposedHelpers.getAdditionalInstanceField(child, "apple_stack_offset");
                    float currentOffset = currentOffsetObj != null ? currentOffsetObj : 0f;
                    
                    // If the user is actively scrolling (originalDistance changing) or near center, 
                    // increase responsiveness to 1.0.
                    // We also blend the lerp factor to avoid sudden jumps.
                    float targetLerp = (dismissPullProgress > 0.01f && absVirtualDistance > 30f) ? 0.72f : 1.0f;
                    float lerpFactor = currentOffsetObj != null ?
                            (0.85f * targetLerp + 0.15f * 1.0f) : targetLerp; // Slight bias towards stability
                    if (gatherThis) lerpFactor = gatherLerp; // slide in from the natural position

                    float smoothedOffset = currentOffset + (extraTranslation - currentOffset) * lerpFactor;

                    XposedHelpers.setAdditionalInstanceField(child, "apple_stack_offset", smoothedOffset);
                    
                    // Manually trigger property update
                    XposedHelpers.callMethod(child, "applyTranslationX");
                    
                    // Visual effects (Scale/Alpha) also use virtual distance for perfect synchronization
                    float visualProgress = Math.min(1.0f, absVirtualDistance / (measuredSize * 2.5f));
                    float targetScale = 1.0f - (visualProgress * 0.20f);
                    float targetAlpha = 1.0f - (visualProgress * 0.2f);
                    float scale = gatherThis ? easeTo(child.getScaleX(), targetScale, gatherLerp) : targetScale;
                    child.setScaleX(scale);
                    child.setScaleY(scale);
                    child.setAlpha(gatherThis ? easeTo(child.getAlpha(), targetAlpha, gatherLerp) : targetAlpha);

                    XposedHelpers.setAdditionalInstanceField(child, "apple_child_index", effectiveRank);
                    float targetZ = 100f - effectiveRank;
                    XposedHelpers.setAdditionalInstanceField(child, "apple_target_z", targetZ);
                    child.setTranslationZ(targetZ);
                } else {
                    // Right side cards: stable anchor
                    float dismissPullProgressRight = 0f;
                    for (int j = 0; j < childCount; j++) {
                        View other = recentsView.getChildAt(j);
                        if (other == null || other == child || !taskViewClass.isInstance(other)) continue;
                        Float otherDistance = (Float) XposedHelpers.getAdditionalInstanceField(other, "apple_stack_distance");
                        Float otherWeight = (Float) XposedHelpers.getAdditionalInstanceField(other, "apple_layout_weight");
                        Float otherProgress = (Float) XposedHelpers.getAdditionalInstanceField(other, "apple_dismiss_progress");
                        if (otherDistance == null || otherWeight == null || otherProgress == null) continue;

                        // If a card closer to the center is being dismissed, pull this card left.
                         // We ONLY do this if the dismissed card is also on the right side (dist > 50).
                         // If the center card is dismissed, only left cards should move right.
                         if (otherDistance < originalDistance && otherDistance > 50f && otherProgress > 0.01f) {
                             dismissPullProgressRight += (1.0f - otherWeight);
                         }
                    }

                    float scrollUnit = 0f;
                    Object savedStep = XposedHelpers.getAdditionalInstanceField(recentsView, "apple_last_scroll_step");
                    if (savedStep instanceof Float) {
                        scrollUnit = (Float) savedStep;
                    }
                    if (scrollUnit <= 0) {
                        scrollUnit = measuredSize * 0.75f;
                    }

                    // Pull Unloading Mechanism for Right Side:
                    // We use a simpler pull for the right side to maintain spacing between cards.
                    float effectivePull = -dismissPullProgressRight * scrollUnit;
                    float virtualDistance = originalDistance + effectivePull;
                    float absVirtualDistance = Math.abs(virtualDistance);

                    boolean isLastChild = (i == childCount - 1);
                    float targetZ = isLastChild ? 200f : (150f - i);
                    XposedHelpers.setAdditionalInstanceField(child, "apple_target_z", targetZ);
                    child.setTranslationZ(targetZ);

                    // Increase negative translation to pull right cards closer
                    float peekRightMax = clamp(spacingFactor * 35f, measuredSize * 0.08f, measuredSize * 0.25f);
                    float decayFactor = Math.min(1.0f, absVirtualDistance / (measuredSize * 0.8f)); 
                    float extraTranslation = (-peekRightMax * decayFactor) + effectivePull;
                    
                    Float currentOffsetRightObj = (Float) XposedHelpers.getAdditionalInstanceField(child, "apple_stack_offset");
                    float currentOffsetRight = currentOffsetRightObj != null ? currentOffsetRightObj : 0f;
                    
                    // Higher responsiveness for dismiss animations
                    float targetLerp = (dismissPullProgressRight > 0.01f && absVirtualDistance > 30f) ? 0.72f : 1.0f;
                    float rightLerp = gatherThis ? gatherLerp : (0.4f * targetLerp + 0.6f * 1.0f);
                    float smoothedOffset = currentOffsetRight + (extraTranslation - currentOffsetRight) * rightLerp;

                    XposedHelpers.setAdditionalInstanceField(child, "apple_stack_offset", smoothedOffset);
                    XposedHelpers.callMethod(child, "applyTranslationX");

                    // Scale logic: make it slightly LARGER as it moves right to create depth hierarchy
                    float progress = Math.min(1.0f, absVirtualDistance / (measuredSize * 1.5f));
                    float targetScale = 1.0f + (progress * 0.04f);
                    float targetAlpha = 1.0f - (progress * 0.02f);
                    float scale = gatherThis ? easeTo(child.getScaleX(), targetScale, gatherLerp) : targetScale;
                    child.setScaleX(scale);
                    child.setScaleY(scale);
                    child.setAlpha(gatherThis ? easeTo(child.getAlpha(), targetAlpha, gatherLerp) : targetAlpha);
                }

                if (!isDismissing && dismissTranslationXField != null) {
                    try {
                        Object targetObj = XposedHelpers.getAdditionalInstanceField(child, "apple_dismiss_target");
                        if (targetObj instanceof Integer) {
                            float dismissTx = dismissTranslationXField.getFloat(child);
                            if (Math.abs(dismissTx) < 0.1f) {
                                XposedHelpers.setAdditionalInstanceField(child, "apple_dismiss_target", null);
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable t) {
            // log once
        }
    }

    private void handleTranslateTaskWhenDismissed(XC_MethodHook.MethodHookParam param) {
        try {
            long now = SystemClock.uptimeMillis();
            if (now - lastDismissHookLogUptime > DEBUG_LOG_THROTTLE_MS) {
                lastDismissHookLogUptime = now;
                XposedBridge.log("AOSPStackingRecent: translateTaskWhenDismissed hooked");
            }

            if (param.args == null || param.args.length < 6) {
                return;
            }
            View view = (View) param.args[0];
            if (view == null || taskViewClass == null || !taskViewClass.isInstance(view)) {
                return;
            }
            int indexDiff = (int) param.args[1];
            int scrollDiffPerPage = (int) param.args[2];
            Object pendingAnimation = param.args[3];
            int index = (int) param.args[5];

            prefs.reload();
            float spacingFactor = prefs.getFloat(KEY_SPACING, DEFAULT_SPACING);

            float animationStartProgress = clamp(0.35f + 0.04f * indexDiff, 0f, 1f);
            float animationEndProgress = 1f;
            TimeInterpolator clampedInterpolator = clampToProgress(new LinearInterpolator(),
                    animationStartProgress, animationEndProgress);

            Object translationProperty = XposedHelpers.callMethod(view, "getPrimaryDismissTranslationProperty");

            int[] mDismissPrimaryTranslations = (int[]) XposedHelpers.getObjectField(param.thisObject, "mDismissPrimaryTranslations");
            if (mDismissPrimaryTranslations != null && index >= 0 && index < mDismissPrimaryTranslations.length) {
                mDismissPrimaryTranslations[index] = scrollDiffPerPage;
            }

            XposedHelpers.setAdditionalInstanceField(view, "apple_dismiss_target", scrollDiffPerPage);
            XposedHelpers.setAdditionalInstanceField(param.thisObject, "apple_last_scroll_step", (float)scrollDiffPerPage);
            XposedHelpers.callMethod(pendingAnimation, "setFloat", view, translationProperty,
                    (float) scrollDiffPerPage, clampedInterpolator);

            try {
                long duration = 350L;
                try {
                    Object d = XposedHelpers.callMethod(pendingAnimation, "getDuration");
                    if (d instanceof Long) {
                        duration = (Long) d;
                    } else if (d instanceof Integer) {
                        duration = ((Integer) d).longValue();
                    }
                } catch (Throwable ignored) {
                }
                Object untilObj = XposedHelpers.getAdditionalInstanceField(param.thisObject, "apple_dismiss_until");
                long until = untilObj instanceof Long ? (Long) untilObj : 0L;
                long newUntil = SystemClock.uptimeMillis() + duration + 200L;
                if (newUntil > until) {
                    XposedHelpers.setAdditionalInstanceField(param.thisObject, "apple_dismiss_until", newUntil);
                }
            } catch (Throwable ignored) {
            }

            boolean mEnableDrawingLiveTile = XposedHelpers.getBooleanField(param.thisObject, "mEnableDrawingLiveTile");
            if (mEnableDrawingLiveTile && (boolean)XposedHelpers.callMethod(view, "isRunningTask")) {
                XposedHelpers.callMethod(pendingAnimation, "addOnFrameCallback", (Runnable) () -> {
                    try {
                        XposedHelpers.callMethod(param.thisObject, "redrawLiveTile");
                    } catch (Exception ignored) {}
                });
            }

            try {
                Object added = XposedHelpers.getAdditionalInstanceField(pendingAnimation, "apple_curve_framecb");
                if (!(added instanceof Boolean) || !((Boolean) added)) {
                    XposedHelpers.setAdditionalInstanceField(pendingAnimation, "apple_curve_framecb", true);
                    XposedHelpers.callMethod(pendingAnimation, "addOnFrameCallback", (Runnable) () -> {
                        try {
                            XposedHelpers.callMethod(param.thisObject, "updateCurveProperties");
                        } catch (Throwable ignored) {
                        }
                    });
                }
            } catch (Throwable ignored) {
            }

            // We NO LONGER set param.setResult(null) here.
            // Letting the original method run ensures Launcher3 updates its internal state correctly,
            // which is critical for snapping-to-page logic when a dismiss is canceled.
        } catch (Throwable t) {
            XposedBridge.log("AOSPStackingRecent: Error in handleTranslateTaskWhenDismissed: " + t.getMessage());
        }
    }

    private static TimeInterpolator clampToProgress(TimeInterpolator base, float start, float end) {
        final float s = clamp(start, 0f, 1f);
        final float e = clamp(end, 0f, 1f);
        if (e <= s) {
            return input -> input < s ? 0f : 1f;
        }
        return input -> {
            if (input <= s) return 0f;
            if (input >= e) return 1f;
            float t = (input - s) / (e - s);
            t = clamp(t, 0f, 1f);
            return base.getInterpolation(t);
        };
    }

    private static float clamp(float value, float min, float max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    private void handleGetTaskViewAt(XC_MethodHook.MethodHookParam param) {
        ViewGroup recentsView = (ViewGroup) param.thisObject;
        float x = (float) param.args[0];
        float y = (float) param.args[1];
        
        int scrollX = recentsView.getScrollX();
        int scrollY = recentsView.getScrollY();

        int childCount = recentsView.getChildCount();
        for (int i = childCount - 1; i >= 0; i--) {
            View child = recentsView.getChildAt(i);
            // Check alpha to avoid hitting invisible/dismissing cards
            if (child == null || child.getVisibility() != View.VISIBLE || child.getAlpha() < 0.2f || !taskViewClass.isInstance(child)) continue;
            
            // Calculate visual bounds in container coordinates
            float left = child.getLeft() + child.getTranslationX() - scrollX;
            float top = child.getTop() + child.getTranslationY() - scrollY;
            float width = child.getWidth() * child.getScaleX();
            float height = child.getHeight() * child.getScaleY();
            
            // Account for scale centering
            if (child.getScaleX() != 1.0f) {
                left += (child.getWidth() - width) / 2f;
            }
            if (child.getScaleY() != 1.0f) {
                top += (child.getHeight() - height) / 2f;
            }

            if (x >= left && x <= left + width && y >= top && y <= top + height) {
                param.setResult(child);
                return;
            }
        }
    }

    private void applyTranslation(View child, float translation) {
        try {
            // 1. Set the internal fields used by Launcher's own logic
            if (persistentTranslationXField != null) {
                persistentTranslationXField.set(child, translation);
            } else if (nonGridTranslationXField != null) {
                nonGridTranslationXField.set(child, translation);
            }
            
            // 2. Force the actual translationX property to update.
            // This ensures the entire view container (including background/border) moves.
            child.setTranslationX(translation);
            
            // 3. In some Launcher versions, the icon or other overlays are handled via separate fields.
            // We attempt to find and sync them if they exist.
            try {
                // If there's an IconView that needs separate translation, it might be a field in TaskView.
                // However, setting translationX on the TaskView parent usually moves the icon too.
            } catch (Exception ignored) {}

        } catch (Exception e) {
        }
    }

    private void updatePageSpacingField(Object recentsView) {
        try {
            // 1. Locate the mPageSpacing field in PagedView
            Field f = XposedHelpers.findFieldIfExists(recentsView.getClass(), "mPageSpacing");
            if (f == null) {
                f = XposedHelpers.findFieldIfExists(recentsView.getClass().getSuperclass(), "mPageSpacing");
            }

            if (f != null) {
                f.setAccessible(true);
                int oldSpacing = f.getInt(recentsView);

                int newSpacing;
                if (pageSpacingGetterHooked) {
                    // RecentsView overrides getPageSpacing() and our hook already returns the final value.
                    try {
                        newSpacing = (int) XposedHelpers.callMethod(recentsView, "getPageSpacing");
                    } catch (Throwable ignored) {
                        prefs.reload();
                        float spacingFactor = prefs.getFloat(KEY_SPACING, DEFAULT_SPACING);
                        float deltaFactor = spacingFactor - DEFAULT_SPACING;
                        newSpacing = Math.round(deltaFactor * 10f) - 120;
                    }
                } else {
                    // getPageSpacing() just returns mPageSpacing, so we must derive the value from the
                    // *stock* spacing, otherwise every call would subtract another 120px. The stock value is
                    // whatever the field holds unless it still equals what we wrote last time (Launcher
                    // resets it via setPageSpacing(dp.overviewPageSpacing) in setInsets()).
                    Object lastSetObj = XposedHelpers.getAdditionalInstanceField(recentsView, "apple_spacing_set");
                    Object lastBaseObj = XposedHelpers.getAdditionalInstanceField(recentsView, "apple_spacing_base");
                    int base = (lastSetObj instanceof Integer && lastBaseObj instanceof Integer
                            && oldSpacing == (Integer) lastSetObj) ? (Integer) lastBaseObj : oldSpacing;
                    prefs.reload();
                    float spacingFactor = prefs.getFloat(KEY_SPACING, DEFAULT_SPACING);
                    float deltaFactor = spacingFactor - DEFAULT_SPACING;
                    newSpacing = base + Math.round(deltaFactor * 10f) - 120;
                    XposedHelpers.setAdditionalInstanceField(recentsView, "apple_spacing_base", base);
                    XposedHelpers.setAdditionalInstanceField(recentsView, "apple_spacing_set", newSpacing);
                }
                if (oldSpacing != newSpacing) {
                    f.setInt(recentsView, newSpacing);
                    
                    // 2. Clear mPageScrolls to force re-calculation ONLY if spacing changed.
                    // IMPORTANT: We avoid clearing this if a gesture is active (running task exists)
                    // to prevent the "overlap and snap" flicker during the transition.
                    boolean isGestureActive = false;
                    try {
                        Object runningTask = XposedHelpers.callMethod(recentsView, "getRunningTaskView");
                        isGestureActive = (runningTask != null);
                    } catch (Throwable ignored) {}

                    if (!isGestureActive) {
                         Field scrollsField = XposedHelpers.findFieldIfExists(recentsView.getClass().getSuperclass(), "mPageScrolls");
                         if (scrollsField == null) {
                             scrollsField = XposedHelpers.findFieldIfExists(recentsView.getClass(), "mPageScrolls");
                         }
                         
                         if (scrollsField != null) {
                             scrollsField.setAccessible(true);
                             scrollsField.set(recentsView, null);
                         }
                     }
                }
            }
        } catch (Throwable ignored) {}
    }
}

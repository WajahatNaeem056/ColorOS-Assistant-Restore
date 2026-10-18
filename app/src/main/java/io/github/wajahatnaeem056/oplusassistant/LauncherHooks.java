package io.github.wajahatnaeem056.oplusassistant;

import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Launcher side of the corner-swipe gesture.
 *
 * <p>The launcher decides whether the bottom-corner swipe may summon the assistant with a
 * {@code (long systemUiStateFlags) -> boolean} rule: ColorOS 16 ships it as
 * {@code QuickStepContract.isAssistantGestureDisabled}, ColorOS 17 as the launcher's own copy
 * {@code ab.n.c}. The mask is {@code 3083} on both, i.e. the gesture is disabled while any of
 * these are set:</p>
 *
 * <pre>
 * 1     SYSUI_STATE_SCREEN_PINNING             screen pinning
 * 2     SYSUI_STATE_NAV_BAR_HIDDEN             nav bar hidden
 * 8     SYSUI_STATE_BOUNCER_SHOWING            lock screen credential UI
 * 128   SYSUI_STATE_OVERVIEW_DISABLED          requested by the focused app
 * 1024  SYSUI_STATE_SEARCH_DISABLED            requested by the focused app
 * 2048  SYSUI_STATE_QUICK_SETTINGS_EXPANDED    quick settings expanded
 * </pre>
 *
 * plus "notification shade expanded while not on the lock screen".
 *
 * <p>The two application-requested flags (128 and 1024) are what make individual system pages,
 * such as pages inside Settings, refuse the gesture. Those carry no gesture conflict, so the hook
 * keeps the state-based blocks (pinning, nav bar hidden, lock screen, shade, quick settings) and
 * only clears the page-level ones.</p>
 */
final class LauncherHooks {
    private static final String QUICK_STEP_CONTRACT =
            "com.android.systemui.shared.system.QuickStepContract";

    /**
     * ColorOS 17 (Android 17) stopped handing the shared contract class to the launcher: the
     * launcher now carries its own copy of the same state-flag rule, obfuscated to
     * {@code ab.n.c(long)} on this build. The lookup verifies the shape of the candidate so a
     * future rename cannot silently hook an unrelated method.
     */
    private static final String LAUNCHER_GESTURE_CONTRACT = "ab.n";
    private static final String LAUNCHER_GESTURE_METHOD = "c";

    private static final long STATE_SCREEN_PINNING = 1L;
    private static final long STATE_NAV_BAR_HIDDEN = 2L;
    private static final long STATE_NOTIFICATION_PANEL_EXPANDED = 4L;
    private static final long STATE_BOUNCER_SHOWING = 8L;
    private static final long STATE_STATUS_BAR_KEYGUARD_SHOWING = 64L;
    private static final long STATE_OVERVIEW_DISABLED = 128L;
    private static final long STATE_SEARCH_DISABLED = 1024L;
    private static final long STATE_QUICK_SETTINGS_EXPANDED = 2048L;
    private static final long STATE_ALLOW_GESTURE_IGNORING_BAR_VISIBILITY = 131072L;

    /** States that keep the gesture disabled even after the page-level flags are cleared. */
    private static final long BLOCKING_STATES = STATE_SCREEN_PINNING
            | STATE_NAV_BAR_HIDDEN
            | STATE_BOUNCER_SHOWING
            | STATE_QUICK_SETTINGS_EXPANDED;

    /** Flags the focused application can request; they only suppress the gesture, nothing else. */
    private static final long PAGE_LEVEL_STATES = STATE_OVERVIEW_DISABLED | STATE_SEARCH_DISABLED;

    private LauncherHooks() {
    }

    static void install(OplusAssistantModule module, ClassLoader classLoader) {
        try {
            Method isAssistantGestureDisabled = resolveGestureDisabled(module, classLoader);
            if (isAssistantGestureDisabled == null) {
                module.logError("hook_skipped target=" + QUICK_STEP_CONTRACT
                        + ".isAssistantGestureDisabled reason=not_found");
                return;
            }

            module.hook(isAssistantGestureDisabled)
                    .setId("assistant_gesture_disabled")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        if (!AssistConfig.isEnabled(HookPrefs.get())) {
                            return chain.proceed();
                        }
                        Object result = chain.proceed();
                        if (!Boolean.TRUE.equals(result)) {
                            return result;
                        }
                        if (!AssistConfig.PAGE_FLAGS_SUPPORTED) {
                            // ColorOS 17 no longer produces the page-level flags; keep the OEM
                            // answer and say so once.
                            logPageFlagsUnsupported(module);
                            return result;
                        }
                        if (!AssistConfig.unblockPageFlags(HookPrefs.get())) {
                            return result;
                        }
                        long flags = (Long) chain.getArg(0);
                        long mask = flags;
                        if ((mask & STATE_ALLOW_GESTURE_IGNORING_BAR_VISIBILITY) != 0) {
                            mask &= ~STATE_NAV_BAR_HIDDEN;
                        }
                        boolean stateBlocked = (mask & BLOCKING_STATES) != 0
                                || ((mask & STATE_NOTIFICATION_PANEL_EXPANDED) != 0
                                        && (mask & STATE_STATUS_BAR_KEYGUARD_SHOWING) == 0);
                        if (stateBlocked) {
                            return result;
                        }
                        long pageOnly = mask & PAGE_LEVEL_STATES;
                        if (pageOnly == 0) {
                            // The original decision came from something this hook does not model;
                            // leave it alone instead of silently enabling the gesture.
                            module.logWarn("assist_gesture_keep_disabled flags=0x"
                                    + Long.toHexString(flags));
                            return result;
                        }
                        module.logInfo("assist_gesture_unblocked pageFlags=0x"
                                + Long.toHexString(pageOnly)
                                + " flags=0x" + Long.toHexString(flags));
                        return Boolean.FALSE;
                    });
            module.logInfo("hook_installed target="
                    + isAssistantGestureDisabled.getDeclaringClass().getName() + "."
                    + isAssistantGestureDisabled.getName());
        } catch (Throwable t) {
            module.logError("hook_failed target=" + QUICK_STEP_CONTRACT
                    + ".isAssistantGestureDisabled", t);
        }
    }

    private static volatile boolean loggedPageFlagsUnsupported;

    /**
     * ColorOS 17 stopped producing the two page-level flags, so this hook has nothing to unblock
     * there; the single log line explains why it stays silent on such builds.
     */
    private static void logPageFlagsUnsupported(OplusAssistantModule module) {
        if (!loggedPageFlagsUnsupported) {
            loggedPageFlagsUnsupported = true;
            module.logInfo("assist_gesture_unblock_skipped reason=unsupported_on_this_build");
        }
    }

    /**
     * @return the state-flag rule this build offers the launcher, or {@code null} when neither
     *         location resolves
     */
    private static Method resolveGestureDisabled(OplusAssistantModule module,
            ClassLoader classLoader) {
        Class<?> shared = Refl.load(classLoader, QUICK_STEP_CONTRACT);
        if (shared != null) {
            try {
                return shared.getMethod("isAssistantGestureDisabled", long.class);
            } catch (Throwable ignored) {
                // ColorOS 17 dropped it from the shared class; fall through to the launcher copy.
            }
        }
        Class<?> launcherCopy = Refl.load(classLoader, LAUNCHER_GESTURE_CONTRACT);
        if (launcherCopy == null) {
            return null;
        }
        try {
            Method candidate =
                    launcherCopy.getDeclaredMethod(LAUNCHER_GESTURE_METHOD, long.class);
            if (!Modifier.isStatic(candidate.getModifiers())
                    || candidate.getReturnType() != boolean.class) {
                module.logWarn("assist_gesture_contract_unexpected candidate="
                        + launcherCopy.getName() + "." + candidate.getName());
                return null;
            }
            candidate.setAccessible(true);
            return candidate;
        } catch (Throwable t) {
            return null;
        }
    }
}

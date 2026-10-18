package io.github.wajahatnaeem056.oplusassistant;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.UserHandle;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

/**
 * Power-key double press: replaces the OEM wallet quick launch with the system quick-access wallet
 * screen.
 *
 * <p>A China build funnels the double press into {@code OplusWalletController.startWallet}, which
 * starts the wallet named by {@code /etc/extension/default_wallet_configuration.xml} (the ColorOS
 * wallet). This hook keeps that call site and instead opens SystemUI's
 * {@code com.android.systemui.wallet.ui.WalletActivity}: the AOSP quick-access wallet screen, fed
 * by the default wallet role, which is the Google Wallet service on a GMS device. The activity is
 * {@code showWhenLocked}, so it also comes up over the keyguard, and system_server may start it
 * even though it is not exported.</p>
 */
final class WalletHooks {
    private static final String WALLET_CONTROLLER =
            "com.android.server.policy.OplusWalletController";
    private static final String SYSTEMUI_PACKAGE = "com.android.systemui";
    private static final String QUICK_ACCESS_WALLET_ACTIVITY =
            SYSTEMUI_PACKAGE + ".wallet.ui.WalletActivity";

    private WalletHooks() {
    }

    static void install(OplusAssistantModule module, ClassLoader classLoader) {
        try {
            Class<?> controller = Class.forName(WALLET_CONTROLLER, true, classLoader);
            Method startWallet = controller.getDeclaredMethod("startWallet", boolean.class);
            startWallet.setAccessible(true);
            Field contextField = controller.getDeclaredField("mContext");
            contextField.setAccessible(true);

            module.hook(startWallet)
                    .setId("double_press_wallet")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        if (!AssistConfig.isEnabled(HookPrefs.get())
                                || !AssistConfig.doublePressWallet(HookPrefs.get())) {
                            return chain.proceed();
                        }
                        if (launchQuickAccessWallet(module, chain.getThisObject(), contextField)) {
                            return null;
                        }
                        // Never leave the gesture unanswered: fall back to the OEM wallet.
                        return chain.proceed();
                    });
            module.logInfo("hook_installed target=" + WALLET_CONTROLLER + ".startWallet");
        } catch (Throwable t) {
            module.logError("hook_failed target=" + WALLET_CONTROLLER + ".startWallet", t);
        }
    }

    /** @return {@code true} when the quick-access wallet screen was asked to start */
    private static boolean launchQuickAccessWallet(
            OplusAssistantModule module, Object controller, Field contextField) {
        try {
            Object value = contextField.get(controller);
            if (!(value instanceof Context)) {
                module.logWarn("double_press_wallet_failed reason=no_context");
                return false;
            }
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(SYSTEMUI_PACKAGE, QUICK_ACCESS_WALLET_ACTIVITY));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startForCurrentUser((Context) value, intent);
            module.logInfo("double_press_wallet target=quick_access_wallet launched=true");
            return true;
        } catch (Throwable t) {
            module.logWarn("double_press_wallet_failed " + t);
            return false;
        }
    }

    /**
     * {@code Context.startActivityAsUser} is hidden API: it is reached reflectively so the activity
     * lands on the current user exactly like the OEM call does, with the public call as fallback.
     */
    private static void startForCurrentUser(Context context, Intent intent) {
        try {
            Method startActivityAsUser =
                    Context.class.getMethod("startActivityAsUser", Intent.class, UserHandle.class);
            // UserHandle.CURRENT is hidden API as well; both are resolved by reflection.
            Object currentUser = UserHandle.class.getField("CURRENT").get(null);
            startActivityAsUser.invoke(context, intent, currentUser);
            return;
        } catch (Throwable ignored) {
            // Hidden overload unavailable: fall through to the public call.
        }
        context.startActivity(intent);
    }
}

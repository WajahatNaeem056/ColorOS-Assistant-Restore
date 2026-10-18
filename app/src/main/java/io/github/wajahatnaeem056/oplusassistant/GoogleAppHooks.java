package io.github.wajahatnaeem056.oplusassistant;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Makes the Google app believe it runs on a device Google ships Circle to Search for.
 *
 * <p>GSA decides whether Circle to Search is available from the device identity, so on a ColorOS
 * device the feature stays off even when the framework side is fixed. The values below are the
 * Samsung S24 Ultra identity that GSA accepts. It is applied inside every Google app process the
 * module is injected into, because the feature entry points run in more than one of them.</p>
 *
 * <p>The technique follows the approach used by the Oplus-Assistant-Hook project.</p>
 */
final class GoogleAppHooks {
    private static final String MANUFACTURER = "samsung";
    private static final String BRAND = "samsung";
    private static final String MODEL = "SM-S928B";
    private static final String PRODUCT = "e3s";
    private static final String DEVICE = "e3s";

    private GoogleAppHooks() {
    }

    static void install(OplusAssistantModule module, ClassLoader classLoader) {
        if (!AssistConfig.isEnabled(HookPrefs.get())) {
            module.logInfo("google_app_identity_spoof_skipped reason=module_disabled");
            return;
        }
        if (!AssistConfig.spoofGoogleBuild(HookPrefs.get())) {
            module.logInfo("google_app_identity_spoof_skipped reason=switch_off");
            return;
        }
        try {
            Class<?> build = Class.forName("android.os.Build", true, classLoader);
            setStaticField(build, "MANUFACTURER", MANUFACTURER);
            setStaticField(build, "BRAND", BRAND);
            setStaticField(build, "MODEL", MODEL);
            setStaticField(build, "PRODUCT", PRODUCT);
            setStaticField(build, "DEVICE", DEVICE);
            module.logInfo("google_app_identity_spoofed model=" + MODEL
                    + " manufacturer=" + MANUFACTURER);
        } catch (Throwable t) {
            module.logError("google_app_identity_spoof_failed", t);
        }
    }

    private static void setStaticField(Class<?> owner, String name, String value)
            throws Throwable {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        try {
            field.set(null, value);
            return;
        } catch (Throwable finalFieldRejected) {
            // Android 17 refuses reflective writes to final fields; the internal Unsafe still
            // reaches the ART static slot, which is also how the framework's own writers work.
            writeWithUnsafe(field, value);
        }
    }

    private static void writeWithUnsafe(Field field, Object value) throws Throwable {
        Object unsafe = internalUnsafe();
        Class<?> unsafeClass = unsafe.getClass();
        Method staticFieldBase = unsafeClass.getMethod("staticFieldBase", Field.class);
        Method staticFieldOffset = unsafeClass.getMethod("staticFieldOffset", Field.class);
        Method putObjectVolatile = unsafeClass.getMethod(
                "putObjectVolatile", Object.class, long.class, Object.class);
        Object base = staticFieldBase.invoke(unsafe, field);
        long offset = ((Number) staticFieldOffset.invoke(unsafe, field)).longValue();
        putObjectVolatile.invoke(unsafe, base, offset, value);
    }

    /**
     * Android 17 moved the static-field natives to {@code jdk.internal.misc.Unsafe};
     * {@code sun.misc.Unsafe} keeps the only instance its proxy calls go through in a private
     * static field, so that field is read first and the class accessor is the fallback.
     */
    private static Object internalUnsafe() throws Throwable {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        try {
            Field internal = unsafeClass.getDeclaredField("theInternalUnsafe");
            internal.setAccessible(true);
            Object instance = internal.get(null);
            if (instance != null) {
                return instance;
            }
        } catch (Throwable ignored) {
            // Fall through to the class accessor.
        }
        Method getUnsafe = Class.forName("jdk.internal.misc.Unsafe").getMethod("getUnsafe");
        return getUnsafe.invoke(null);
    }
}

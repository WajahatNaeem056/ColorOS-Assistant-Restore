# OplusAssistant

An LSPosed module that restores AOSP's default digital-assistant behaviour on China-region ColorOS:
holding the power button, holding the gesture handle, and swiping in from the left/right bottom corners
all wake the app currently set as the system default assistant
(the app that `Settings.Secure.assistant` / `RoleManager.ROLE_ASSISTANT` points to), instead of being hard-wired to Breeno (小布助手).

The module only reconnects the few dispatch points that the OEM rewrote. It does not replace the AOSP assistant stack.

## 1. Evidence

The conclusions come from framework artifacts pulled read-only from the device and from their decompiled output:

| Artifact | Purpose |
| --- | --- |
| `services.jar` (`/system/framework`) | `launchAssistAction` in `com.android.server.policy.PhoneWindowManager` |
| `oplus-services.jar` (`/system/framework`) | `PhoneWindowManagerExtImpl.startSpeech`, `StrategyShutdown` |
| `系统界面_16.99.12_New.apk` (SystemUI) | `AssistManager`, `NavBarUtils`, `SpeedChassistMainBusiness`, `LauncherProxyService` |
| `设置_16.1.0.apk` (Settings) | `DefaultVoiceassistPreferenceController` (the default-assistant picker) |
| `/my_region/etc/extension/com.oplus.oplus-feature.xml` | line 35, `oplus.software.speech_assist_for_breeno` |

> **Compatibility (this fork):** The maintainer of this fork has tested the module working on ColorOS 17 (CN) using a ported ROM on the OnePlus Ace 5. Stock ColorOS 17 firmware.

Current device state: `cmd role get-role-holders android.app.role.ASSISTANT` already returns
`com.google.android.googlequicksearchbox`, and `settings get secure assistant` also points to GSA.
This shows the framework-side assistant binding is fully healthy; what gets blocked is only the dispatch at each entry point.

All four analysed files match what is actually installed on the device (md5 verified), so the hooked method signatures are the runtime signatures:

| File | Device path | md5 |
| --- | --- | --- |
| SystemUI | `/system_ext/priv-app/SystemUI/SystemUI.apk` | `e249e527020ee80547dd8200179b0656` |
| OPlus framework | `/system/framework/oplus-services.jar` | `9658e6361030204c749d74d2fb7a5ae7` |
| AOSP framework | `/system/framework/services.jar` | `ce501d636dcee11e2bef77a2270eaead` |
| Launcher | `/system_ext/priv-app/OplusLauncher/OplusLauncher.apk` | `e77c29b92cc2df3aece19d5dc46b45d2` |

Region background: `ro.vendor.oplus.regionmark = CN` (`FeatureOption.isExpRegion()` is tied to
`VENDOR_REGIONMARK` / `getRmRegionName()`; the China value means non-exp). Therefore the two SystemUI gates below
are both closed on this device, and the module's re-dispatch path is the one that actually takes effect.

### 1.1 Power-button long press

`PhoneWindowManagerExtImpl.startSpeech(int deviceID, int startSource, long eventTime)` is the single funnel for the key semantics
(the `what = 1011` message of `OplusSpeechHandler`, `startSource = 1024`). Inside the method:

- `mSpeechAsssistForBreeno = OplusFeatureConfigManager.hasFeature("oplus.software.speech_assist_for_breeno")`
  is `true` on China firmware;
- so the AOSP branch `if (!mSpeechAsssistForBreeno && mhasGoogleAssistant)` (which goes through
  `mBase.getWrapper().launchAssistAction(...)`) is skipped, and an explicit Intent is built to start the
  foreground service of `com.heytap.speechassist`.

### 1.2 Gesture-handle long press

`SpeedChassistMainBusiness.onLongPressed()` does exactly one thing:
`ActivityStartedHelper.startBreenoService(context, 91)`, i.e.
`heytap.intent.action.ACTIVATE_SPEECH_ASSIST` plus a hard-coded `ComponentName`, never touching the assistant stack.

The switch chain for this gesture is broken between Settings and SystemUI. You have to read it carefully to know whether extra handling is needed:

| Step | Key / condition | Current device value |
| --- | --- | --- |
| Settings item `gesture_side_wake_cui` (the row in system navigation settings; its title is chosen between two static strings by `NavBarUtil.isSupportOcr()`) | Writes `oplus_home_handle_wake_up_ocr_enable` when screen recognition is supported, otherwise `oplus_gesture_handle_cui_enable` (secure, written for user -2) | `oplus_home_handle_wake_up_ocr_enable=1`, `oplus_gesture_handle_cui_enable=0` |
| Whether that settings item is shown (`mGestureSideWakeCuiSwitchIsShow()`) | `isSupportOcr()` or `oplus.speechassist.main.type == 2` | `oplus.speechassist.main.type=2` |
| Whether SystemUI registers the gesture-handle long press (`SpeedChassistMainObserver`) | Global key `oplus.speechassist.main.type == 2` | 2, i.e. registered |
| Action after the long press | Only hands `start_type=91` to the Breeno service; whether it runs screen recognition or voice is decided on the Breeno side by reading the OCR/CUI keys above | - |

In other words: **the switch in Settings has nothing to do with SystemUI's gesture registration**. SystemUI never reads those two OCR/CUI keys;
it only looks at `oplus.speechassist.main.type`, which is the real switch for "does a long press on the gesture handle trigger". So once the module is enabled, a long press on the gesture handle
goes straight to `AssistManager` and wakes the current default assistant, and you do not need to touch the switch in Settings. The wording of that settings row is a static string resource
(the two variants are chosen by `NavBarUtil.isSupportOcr()`) and does not change with the default assistant; it is a purely cosmetic difference.

The module adds one more step: since SystemUI does not read that switch, a switch that has been turned off would make taps "do nothing", so
`SystemUiHooks.isHandleWakeSwitchOff()` reads the two keys by the same rule Settings uses. When "neither key is 1 and at least one is 0"
(the user explicitly turned it off), it no longer dispatches the assistant, so the switch keeps working. When the keys are unset (-1), the original behaviour is kept.

Note: the paragraph above describes the logic of the "SpeedChassistMainBusiness" business itself. On-device testing found that what is actually registered and handles the gesture-handle long press is
the screen-recognition business (see section 8.1), and the module handles both.

### 1.3 Bottom-corner swipe

The corner gesture is implemented by the launcher (Launcher/Quickstep). SystemUI only does two things: tell the launcher "the assistant is available",
and actually launch the assistant on its own side.

- `LauncherProxyService.onNavigationModeChanged` calls
  `UtilsStaticToolsExImpl.isAssistantAvailable(...)` → `NavBarUtils.isAssistantAvailable(Context,int,int)`,
  then notifies the launcher via `ILauncherProxy.onAssistantAvailable(available, longPressHome)`.
  The first line of that method is `if (!FeatureOption.isExpRegion() || ...) return false;`, so it is always `false` on China firmware,
  and the launcher never enables the bottom-corner swipe zones.
- When the gesture completes, the launcher calls back `ISystemUiProxy.startAssistant(Bundle)` →
  `NavBarHelper.startAssistant` → `AssistManager.startAssist(Bundle)`.
- The entire dispatch in `AssistManager.startAssist` is written inside `if (FeatureOption.isExpRegion() && ...)`.
  On China firmware the method logs and returns, and does **not** call `startAssistInternal`. This is also the last break point of the power-key chain:
  `launchAssistAction` → `SearchManager.launchAssist` → `SearchManagerService.launchAssist` →
  `StatusBarManagerService$1.startAssist` → `IStatusBar.startAssist` →
  SystemUI `CommandQueue.startAssist` → `AssistManager.startAssist`.
  The hops in the middle are all plain AOSP forwarding (no OEM override was found in `services.jar`); the region check appears only at the two ends.

The launcher-side switch logic was confirmed from `/system_ext/priv-app/OplusLauncher/OplusLauncher.apk` pulled from the device. The launcher has no
region gate of its own and relies entirely on the availability sent by SystemUI:

- `com.android.quickstep.TouchInteractionService$TISBinder` implements `ILauncherProxy.onAssistantAvailable(available, longPressHome)`,
  and on receipt calls `DeviceState.setAssistantAvailable(available)` + `notifyUpdateRegionForAssistantAndOneHanded()`;
- that call passes `isAssitantValid()` to
  `OplusOrientationTouchTransformerImpl.updateRegionForAssistantAndOneHanded(...)` to compute the assistant gesture region,
  and `isAssitantValid()` is defined as `mAssistantAvailable && (!QuickStepContract.isAssistantGestureDisabled(flags) || ...)`,
  i.e. it is **decided by this one flag alone**;
- the gesture itself is handled by `com.android.quickstep.inputconsumers.AssistantInputConsumer`, which on completion calls back into SystemUI through
  `SystemUiProxy.startAssistant` → `ISystemUiProxy.startAssistant`.

Also, the circle-to-search branch inside `NavBarUtils.isAssistantAvailable` does not interfere on this device: in `FeatureOption`,
`CustomizeFeatureOption.sIsSupportCircleToSearch = isExpRegion() && hasSystemFeature("com.google.android.feature.CONTEXTUAL_SEARCH")`
is always `false` on China firmware, so the bottom-corner gesture always belongs to the assistant.

### 1.4 Long press at bottom centre stops working when the gesture bar is hidden

The gesture-bar view itself has no touch listener. Touches at the bottom centre are first received by `SideGestureDetector` (log TAG `NoBackGesture`),
and `OplusNavigationHandle.handleValidTouchEvent(event)` is only called when all of these hold: "the touch lands in the lower gesture area + `!NavBarUtils.isSideGestureBarHide()` + `!NavBarUtils.getTaskbarStatus()` +
the view is an `OplusNavigationHandle`".
This check is written twice in `SideGestureDetector` as two posted Runnables (the ACTION_DOWN branch and the branch for other events),
and only after that come `NavigationGestureDetector`'s `onDown/onShowPress/onPreLongPress/onLongPress` →
`GestureHomeHandleEventController.onLongClick()`.

So when `isSideGestureBarHide()` is true, touches at the bottom centre never reach the gesture bar at all, and a long press cannot happen. The method is defined as:

```java
public static final boolean isSideGestureBarHide() {
    return isGestureSideMode() && SwipeSideGestureBarTypeObserver.INSTANCE.getSwipeSideGestureBarType() == 1;
}
```

- `isGestureSideMode()` = `getNavState() == 3` (`isGestureUpMode()` is `== 2`; the lower gesture bar in that mode uses the
  `GestureUpGuideBarView` chain and is not affected by these two checks);
- `getSwipeSideGestureBarType()` reads the secure key `gesture_side_hide_bar_prevention_enable` (only 0/1 are accepted).
  This is the same flag that decides "draw the gesture bar or hide it". The visibility checks below read exactly this flag, so it is the key written by the
  "Hide gesture bar" row in Settings (the mapping from key name to UI was not verified word-for-word in the Settings package `设置_16.1.0.apk`).

The same flag is also used to hide the gesture bar: `NavigationBar.getBarLayoutParams()` sets the whole navigation-bar window's `layoutParams.alpha` to 0 when
`isHideNavBarGestureMode()` (= `isGestureUpMode() || isSideGestureBarHide()`) is true and the mode is not swipe-up gesture;
`OplusNavigationBarView.updateViewVisible$1()` and
`OplusNavigationBarInflaterView.resizeLayout()` also decide the visibility of each child view from it. In addition, the QS special mode
(`OplusQSSpecialModeProvider.isSideGestureBarHide()`) and `UtilsStaticToolsExImpl.canSamplingRegionMode()` read it as well.
So the module only changes this one touch-forwarding check, and touches neither the flag itself nor the window transparency.

The evidence for the same chain in the device log (`log/log.txt`, retest on 8.3, gesture bar visible at the time):

```
NoBackGesture-->gestureBar animation: gestureBarNotHide = true, isCorrectHomeHandle = true
NoBackGesture-->send down event to NavigationBarHandle
```

OxygenOS can still trigger the assistant with a long press at the original position after the gesture bar is hidden; what is missing here is exactly that check. The module answers
`isSideGestureBarHide()` with `false` only for calls made by `SideGestureDetector` itself, and every other caller still reads the original value.

### 1.5 The page also fires its own long press when the gesture handle is long-pressed

On China firmware the gesture handle is not a touchable window: `OplusNavigationHandle extends View` and does not handle touches, and the window's default is
`touchableRegion=<empty>` (measured on device). A press on this bottom strip is actually received by the page (the app, launcher or input method), and SystemUI only listens in through the
gesture monitor and then decides whether to hand it to the gesture handle. That is why "the assistant and the page's long press happen at the same time": the page's own long press fires at 500 ms,
while the gesture handle is only recognised at 800 ms (`NavigationGestureDetector`: SHOW_PRESS 200 ms, onPreLongPress 300 ms, LONG_PRESS 800 ms),
so the page always reacts first.

The module hands the gesture handle's own area back to the navigation-bar window:

- **Touch region**: `ViewRootImpl.setTouchableRegion` sets the window's touchable area to "the gesture-handle column × the bottom gesture area", measured as
  x∈[480,960], y∈[3080,3168] (in-window coordinates x∈[480,960], y∈[88,176]). The width comes from the `oplus` package's
  `navigation_gesture_view_width` (measured 480px), the height from SystemUI's `bottom_gesture_area_height` (88px, the same value used by
  the `SideGestureDetector` check), and the window size is the navigation-bar window itself, so this can be computed even when the gesture bar is hidden and the view takes no part in layout.
- **Window visibility**: when the gesture bar is hidden, the OEM sets the window alpha to 0, and WindowManager removes fully transparent windows from input dispatch
  (`inputConfig=NOT_VISIBLE`), so the region becomes ineffective. The module rewrites 0 to 0.01 in both window-parameter generation (`NavigationBar.getBarLayoutParamsForRotation`)
  and the live toggle (`OplusNavigationBarView.updateWindowAlpha`), so the window stays in the input chain while still being invisible on screen.
- **Exception**: when the input method is visible, the region is cleared on purpose (`handle_touch_region_skipped reason=ime_visible`), and the bottom row of the keyboard still belongs to the keyboard.

Verification (PJZ110 / ColorOS 16; after injecting one 1.8 s long press at the bottom centre and reading the TouchStates of `dumpsys input`): with the gesture bar both shown and hidden,
the touch target is `NavigationBar_displayId_0` (`targetFlags=FOREGROUND`), and the app window is not in the touch list. At the same time the module log shows the usual
`hidden_gesture_bar_handle_unblocked` and `circle_to_search_triggered`, and swiping up from the gesture-handle position back to the home screen still works.

## 2. Module implementation

| Process | Hook target | Effect |
| --- | --- | --- |
| `system_server` | `PhoneWindowManagerExtImpl.startSpeech(int,int,long)` | Calls `PhoneWindowManager.launchAssistAction(null, deviceId, eventTime, invocationType, 1)` following the AOSP branch and sets `mSpeechLongPressHandled`; also restores the OEM's pre-dispatch vibration `performHapticFeedback(0, "Speech - Long Press")` |
| `com.android.systemui` | `AssistManager.startAssist(Bundle)` | Runs the original method first; when the region gate blocks the request (or `isExpRegion()` cannot be resolved), calls `startAssistInternal` with the same `componentName`/`isService` to finish the dispatch |
| `com.android.systemui` | `NavBarUtils.isAssistantAvailable(Context,int,int)` | Answers with AOSP semantics: gesture navigation possible + assistant configured + `assist_touch_gesture_enabled` on (default read from the framework `config_assistTouchGestureEnabledDefault`) |
| `com.android.systemui` | `SpeedChassistMainBusiness.onLongPressed()` | Changed to dispatch through `AssistManager.startAssist` (`invocation_type = 5`) |
| `com.android.launcher` | `QuickStepContract.isAssistantGestureDisabled(long)` | Keeps only the blocking for screen pinning / hidden navigation bar / lock screen / notification shade / QS, and releases the page-level flags an app can request (128/1024), so the bottom-corner gesture also works on pages such as Settings |
| `com.android.systemui` | `OplusOcrScreenServiceHandler.onLongPressed()` | The real entry of the gesture-handle long press on this device (vibration + flag + posting the action); assistant dispatch is now done here |
| `com.android.systemui` | `OplusOcrScreenServiceHandler.onPreLongPress()` | Pre-binding of the screen-recognition service before the long press; skipped directly to avoid needlessly waking that service (it is also the precondition for `handleLongPressAction` to be called, hence the dispatch is hooked on `onLongPressed`) |
| `com.android.systemui` | `NavBarUtils.isSideGestureBarHide()` | Answers `false` only when the caller is `SideGestureDetector` (the bottom touch-forwarding check) and the gesture bar really is hidden, so a long press after hiding the gesture bar still reaches the gesture handle; other callers (window transparency, QS special mode, screenshot sampling region) keep the original value |
| `com.android.systemui` | `OplusNavigationHandle.onLayout`, `NavigationBar.getBarLayoutParamsForRotation(int,WindowMetrics)`, `OplusNavigationBarView.updateWindowAlpha(int)` | Sets the navigation-bar window's touchable region to the gesture handle's own strip; when the gesture bar is hidden, keeps the window alpha at 0.01 instead of 0, so the window is not removed from input dispatch (which would give the strip back to the page and trigger the page's own long press) |

Design constraints:

- `FeatureOption.isExpRegion()` is not flipped globally. That check is reused by many SystemUI features (signal icons, carrier
  customisation, panel behaviour and so on), and forcing it true would cause side effects unrelated to the assistant. Only the single conclusion "assistant availability" is rewritten here,
  and `AssistManager.startAssist` reads the original gate once to decide whether a re-dispatch is needed.
- Each hook has its own try/catch and is bound to a hook id. If a target class is missing, only a log line is written and the other hooks are unaffected.
- All hooks use `ExceptionMode.PROTECTIVE`: exceptions are recorded by the framework and the original logic is let through, so nothing is swallowed.
- No distinction is made between Breeno and third-party assistants: whoever `Settings.Secure.assistant` points to gets woken, which is exactly AOSP semantics.

## 3. Project structure

```
OplusAssistant/
├─ app/                             Module APK (`io.github.wajahatnaeem056.oplusassistant`)
│  ├─ src/main/java/io/github/wajahatnaeem056/oplusassistant/
│  │  ├─ OplusAssistantModule.java   Entry point, routes by process and package name
│  │  ├─ SystemUiHooks.java         The three SystemUI hooks
│  │  ├─ SystemServerHooks.java     Power-key dispatch in system_server
│  │  ├─ CtsHooks.java              Fills in the ContextualSearch service on the system_server side
│  │  ├─ LauncherHooks.java         Per-page release of the bottom-corner gesture on the launcher side
│  │  ├─ GoogleAppHooks.java        Device-model spoofing inside the Google app process
│  │  └─ Refl.java                  Small reflection helper
│  ├─ src/main/kotlin/io/github/wajahatnaeem056/oplusassistant/ui/
│  │  ├─ MainActivity.kt            Host Activity of the settings UI
│  │  ├─ OplusAssistantApp.kt        Five pages: entry / wake target / custom / advanced / diagnostics
│  │  ├─ AssistData.kt              Reads assistant candidates and the current default assistant from the device
│  │  └─ Theme.kt                   Material 3 theme
│  ├─ src/main/res/values{,-night}/themes.xml   UI theme (light and night)
│  └─ src/main/resources/META-INF/xposed/{module.prop,java_init.list,scope.list}
├─ libxposed-api/                   API 102 source module used as compileOnly
│  ├─ src/api/java/                 API 102 source shipped with the repo (see src/api/README.md)
│  └─ build.gradle.kts              sourceSets.java.srcDir points to src/api/java
└─ gradle/wrapper/                  Gradle 8.13 + AGP 8.13.2
```

`:libxposed-api` points `sourceSets.java.srcDir` at the `src/api/java` shipped with the repo (taken from upstream
`libxposed/api` commit `79b75b4`, i.e. three commits after tag `102.0.0`, `XposedInterface.API_102 = 102`),
so what is compiled against is that API 102 source itself, not a downloaded binary.
`io.github.libxposed.annotation.SinceApi/InternalApi` are not shipped upstream, so the two compile-time annotations under `src/annotation/java` fill that gap.
The API is always `compileOnly` and never ends up in the APK.

`scope.list` has four entries: `system`, `com.android.systemui`, `com.android.launcher` and
`com.google.android.googlequicksearchbox`, corresponding respectively to the power key and region gate, the gesture chain, the per-page release of the bottom-corner gesture,
and the device-model spoofing inside the Google app process.
`module.prop` uses `minApiVersion=102`, `targetApiVersion=102`, `exceptionMode=protective` and
`autoHotReload=false`.

The UI is written in Kotlin + Jetpack Compose (material3) and is UI-only for now: the assistant candidates in the list and the "current default
assistant" are really read from the device (`PackageManager` queries `VoiceInteractionService` and `ACTION_ASSIST`, de-duplicates by package name, then takes the app's own icon and label; the default assistant is read from `Settings.Secure.assistant`). The switches and selections only change UI state
and do not write any configuration yet, and the "assign one target to each of the three entry points" logic is not wired in. The build uses `--offline`, so the Compose /
AndroidX versions are pinned in the `resolutionStrategy` of `app/build.gradle.kts`, matching the versions actually present in the local Gradle cache
(Compose runtime 1.10.5, lifecycle 2.9.4, etc.). Before building on another machine, first confirm that these versions exist in the target cache.

## 4. Build

```powershell
cd path/to/OplusAssistant
.\gradlew.bat --offline assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`.

> Note: on the original dev machine `GRADLE_USER_HOME=E:\Buildcache\gradle`, and Gradle needs to write to that directory. In a restricted sandbox it fails with
> `gradle-8.13-bin.zip.lck` access denied, so it must be run outside the sandbox.

## 5. Installation and verification

1. Install the APK and enable the module in LSPosed. In the scope, tick **System Framework (system)**, **System UI (com.android.systemui)** and **Launcher (com.android.launcher)** (the launcher entry was added for the "release the per-page restriction" feature).
2. Reboot the device (the `system_server` hooks are loaded during boot and only take effect after a reboot). Restarting only SystemUI is enough to verify the gesture chain.
3. Confirm the default assistant:
   ```bash
   cmd role get-role-holders android.app.role.ASSISTANT
   settings get secure assistant
   settings put secure assist_touch_gesture_enabled 1
   ```

   Reference values (verified on the device): the default assistant is GSA, `assist_long_press_home_enabled=1`,
   `navigation_mode=2` (gesture navigation), `assist_touch_gesture_enabled` is unset;
   `cmd overlay lookup android android:bool/config_assistTouchGestureEnabledDefault` returns `true`,
   i.e. the bottom-corner gesture is on by default and you do **not** need to run `settings put`.
4. Verify the three chains one by one and compare with the LSPosed log (TAG `OplusAssistant`):
   - Power-button long press → log `power_key_long_press startSource=1024 ...` → `power_key_haptic effect=0 reason=Speech - Long Press` → `assist_dispatch component=...`;
   - Gesture-handle long press → log `gesture_handle_long_press invocationType=5` → `assist_dispatch ...`;
   - Bottom-corner swipe → log `assistant_availability available=true ...`, followed by `assist_dispatch ...`.

   The navigation-bar item "Long-press gesture indicator" (settings key `gesture_side_wake_cui`) has these values on the device:
   `oplus_home_handle_wake_up_ocr_enable=1`, `oplus_gesture_handle_cui_enable=0`, and the registration key
   `oplus.speechassist.main.type=2`, i.e. that gesture is currently on. When that switch is explicitly turned off, the module makes the gesture do nothing.

## 6. Known limitations

- **How the region gate is read.** The re-dispatch in `AssistManager.startAssist` is conditional on one call to
  `FeatureOption.isExpRegion()`. If that static method does not exist on the target version, the module treats the gate as closed,
  which matches China firmware behaviour.
- `startAssistInternal` is the lower-level method that the OEM region branch really calls. Calling it directly skips the lock-screen device check and the circle-to-search interception in
  `AssistManagerImpl.beforeStartAssistInternal`. China firmware never gets there anyway,
  so the difference only appears in these edge cases.
- The module does not modify `Settings.Secure.assistant` and does not install an assistant app; the assistant must already appear among the options in the system "Default apps"
  (for example, installed and supporting `VoiceInteractionService` / `ACTION_ASSIST`).
- Other power-key semantics such as the 3-second long-press shutdown and the SOS multi-press are not handled; those paths do not go through `startSpeech`.
- The static evidence for all three chains is complete (including the launcher side and the framework default), but the module has **not yet been installed and run on the device** at the time of writing this section: its actual behaviour
  still needs a device verification following section 5.
- **Long press while the gesture bar is hidden**: the conclusion comes from static analysis (`SideGestureDetector` + `NavBarUtils`) and from the
  `gestureBarNotHide` check in the device log; it has not been retested on a real device with "Hide gesture bar" turned on. To verify, turn that switch on and long-press the bottom centre; the log should first show
  `hidden_gesture_bar_handle_unblocked mode=...`, followed by the existing `gesture_handle_long_press invocationType=5`.
  This chain only covers the side-swipe back gesture (`getNavState() == 3`); the lower gesture-bar long press in swipe-up gesture mode is a different chain and was left unchanged.
- The **label** of "long-press the gesture indicator to wake Breeno Screen Recognition (小布识屏)" in Settings is **still a hard-coded static string resource** (`NavBarUtil.isSupportOcr()` decides whether to use
  the "Breeno Screen Recognition" one or the non-recognition one), and it does not change with the default assistant. The behaviour has been changed by the module to wake the default assistant, but if that wording matters to you,
  there are three options: leave it alone; replace those two strings with an RRO overlay for `com.android.settings` (a standalone overlay APK, the cleanest, no hooks needed);
  or add `com.android.settings` to the module's scope and hook that settings item to rewrite the title dynamically with the default assistant's name. The last two
  have not been done.

## 7. Troubleshooting

| Symptom | What to check |
| --- | --- |
| No `OplusAssistant` output in the log at all | Whether the module is enabled; whether the scope includes `system` and `com.android.systemui`; whether the device has been rebooted |
| Only SystemUI hooks exist, the power key does nothing | The `system_server` hook needs a reboot; confirm `hook_installed target=PhoneWindowManagerExtImpl.startSpeech` |
| `assist_dispatch_skipped reason=no_assistant_configured` | No default assistant is set; bind `android.app.role.ASSISTANT` first |
| `assistant_availability available=false` | `assist_touch_gesture_enabled` is set to 0, or the device is not in gesture navigation, or this version really has circle-to-search enabled (China firmware does not) |
| `hook_failed ... NoSuchMethodException` | The target class/method signature changed with the version; recheck against the decompiled output using the class name in the log |
| `gesture_handle_long_press_skipped reason=nav_bar_switch_off` | The "Long-press gesture indicator" switch in Settings was explicitly turned off (both OCR/CUI keys are 0); the module does not dispatch, following the switch's semantics |
| `assist_skip reason=exp_region_active / lock_task_mode / launcher_override` | The dispatch was skipped on purpose; the log gives the reason. `override` only appears when that type is occupied by the launcher |
| `gesture_handle_long_press_skipped reason=debounce` | The same gesture was fired twice by two callbacks; the module already de-duplicates |
| Long press at bottom centre does nothing after hiding the gesture bar | Check whether `hidden_gesture_bar_handle_unblocked` appears in the log. If not, the "Keep long press when gesture bar is hidden" option on the advanced page may be off, that entry may be set to "Breeno Screen Recognition / all off", or the device is not in side-swipe back gesture mode (`getNavState() == 3`) |
| `hidden_gesture_bar_handle_unblocked mode=...` | Normal: the module released the long-press forwarding while the gesture bar is hidden; `mode` is the current configuration of that entry |
| The page also fires its own long press when the gesture handle is long-pressed | Read `touchableRegion` and `inputConfig` of the NavigationBar window in `dumpsys input`: the region should be the gesture handle's strip and must not carry `NOT_VISIBLE`; the matching module logs are `handle_touch_region applied=...` and `handle_window_alpha=0.01` |
| `handle_touch_region_skipped reason=ime_visible` | Normal: when the input method is up, that region is handed back to the keyboard |
| `handle_touch_region_skipped reason=not_owned` | That entry is set to "Breeno Screen Recognition" or "All off"; the module does not take over this region |
| `gesture_handle_ocr_preload_skipped` | Normal: this long press was taken over by the assistant, and the screen-recognition pre-binding was skipped |
| `assist_gesture_unblocked pageFlags=0x...` | Normal: the page only set app-requestable page-level flags, and the module released the bottom-corner gesture |
| `assist_gesture_keep_disabled flags=0x...` | The device is on the lock/password screen, the notification shade or QS is expanded, the navigation bar is hidden, or the screen is pinned; the module keeps it blocked |
| `assist_dispatch` is logged but no assistant appears on screen | The dispatch chain is fine; the assistant process was frozen/reclaimed (see section 8.2) |
| `power_key_haptic_failed ...` | The vibration call failed (does not affect dispatch, it is only a log reminder); `power_key_haptic effect=0` is the success case |

## 8. On-device test records (2026-09-13)

Real-device verification exposed two problems, and the log `log/log.txt` (19:16–19:21) gives clear evidence.

### 8.1 Long-pressing the gesture handle still wakes Breeno Screen Recognition

In the log, `gesture_handle_long_press` (hooked on `SpeedChassistMainBusiness.onLongPressed` at the time) never fired once,
and the long press at the bottom centre (x≈780~960) corresponds to:

```
NoBackGesture-->send down event to NavigationBarHandle
OcrScreenService-->getServiceIntent bundle: Bundle[{StartUpType=0}]
```

On the launcher side, the input consumer's `TYPE_...:TYPE_ASSISTANT:...` also only appears at the **bottom corners**; a bottom long press only shows `TYPE_ONE_HANDED`.
Decompilation confirmed the real chain is `GestureHomeHandleEventController.onLongClick()` → listener `OplusOcrScreenBusiness` →
`OplusOcrScreenServiceHandler.onLongPressed()` → `handleLongPressAction()` → screen-recognition service `start()`.
The module hooks two places in this family of classes: `onPreLongPress()` skips the pre-binding directly, and the dispatch is hooked on `onLongPressed()` (first `chain.proceed()` to keep the vibration and flags, then dispatch and de-duplicate). `handleLongPressAction()` is only called when the screen-recognition service is already connected, so after skipping the pre-binding it necessarily runs empty, and the dispatch cannot be hooked there. The `SpeedChassistMainBusiness` hook is kept
for other versions that register that business; the two share the same dispatch and switch check.

### 8.2 Bottom-corner gesture and power key "stop responding after a while"

The same log shows this has nothing to do with the module: **the Google app is frozen/reclaimed by ColorOS, and the voice-interaction binding dies with it**.

```
19:21:04  power_key_long_press + assist_dispatch (dispatch OK) → no GSA UI was created
19:21:09  assist_dispatch invocationType=1          → again no GSA UI
19:21:19  ActivityManager: Killing 29305:...:interactor (adj 100): permissions revoked
19:21:19  VoiceInteractionServiceManager: onBindingDied to ...GsaVoiceInteractionService
19:21:19  OplusHansManager: uid=10413 ... F exit(), F stay=51   ← had been in the frozen state until then
19:21:21  am_proc_start ...GsaVoiceInteractionService (bindService); GsaVoiceInteractionSrv: onReady
19:21:24  power_key_long_press + assist_dispatch → wm_create_activity ...FloatyActivity ✔
19:21:26  assist_dispatch invocationType=1        → wm_create_activity ...FloatyActivity ✔
```

Conclusion: the module's dispatch is called every time (`assist_dispatch` is recorded every time), but while the assistant process is frozen,
`GsaVoiceInteractionService` cannot respond and the session does not appear. Re-selecting the default assistant rebinds and starts that service,
so it "works for a while", and after it is frozen/reclaimed again it "does nothing". Around 20:12 the log also shows PermissionController
revoking `SEND_SMS`/`READ_CALL_LOG`/`READ_SMS` (`sysui_multi_action`) right before `am_kill`,
which is ColorOS's permission-reclaim / background-freeze mechanism and cannot be changed at the LSPosed level.

Actionable fixes (all on the system/app side):

1. In Settings → Battery / power saving (or "Phone Manager → Background freezing / Smart power saving"), set the **Google app** to allow background running and not be frozen;
2. In Settings → Permissions & privacy → Permission manager, turn off **automatic permission revocation** for that app;
3. Before verifying, open Google Assistant manually once to confirm the process is thawed, then try the power-button long press and the bottom corners.

For troubleshooting, the module added reason logs: `assist_skip reason=exp_region_active|lock_task_mode|launcher_override`,
`gesture_handle_long_press_skipped reason=debounce|nav_bar_switch_off`. Next time you capture a log, if you see
"`assist_dispatch` is there but no assistant UI appears on the system", the problem is in the assistant process itself and not in the dispatch chain.

### 8.3 Retest result (2026-09-13 19:36–19:38)

After reinstalling and rebooting, all three entry points reach the assistant, and the 9 `assist_dispatch` calls match the 9 GSA `FloatyActivity` creations one to one:

| Time | Entry | Module log | Result |
| --- | --- | --- | --- |
| 19:37:40.317 | Gesture-handle long press | `gesture_handle_long_press invocationType=5` | GSA created 19:37:40.469 ✔ |
| 19:37:44.041 | Corner swipe | `assist_dispatch invocationType=1` | GSA created 19:37:44.106 ✔ |
| 19:37:47.415 | Power-button long press | `power_key_long_press startSource=1024` → `invocationType=6` | GSA created 19:37:47.467 ✔ |
| 19:37:53.582 | Corner swipe | `assist_dispatch invocationType=1` | GSA created 19:37:53.653 ✔ |
| 19:37:57.408 | Gesture-handle long press | `gesture_handle_long_press invocationType=5` | GSA created 19:37:57.462 ✔ |
| 19:38:00.898 | Gesture-handle long press | `gesture_handle_long_press invocationType=5` | GSA created 19:38:00.969 ✔ |
| 19:38:04.520 | Corner swipe | `assist_dispatch invocationType=1` | GSA created 19:38:04.581 ✔ |
| 19:38:09.577 | Power-button long press | `power_key_long_press startSource=1024` → `invocationType=6` | GSA created 19:38:09.635 ✔ |
| 19:38:12.882 | Gesture-handle long press | `gesture_handle_long_press invocationType=5` | GSA created 19:38:12.930 ✔ |

The same log shows no more `assist_skip`, `gesture_handle_long_press_skipped`, or GSA `am_kill` / `onBindingDied`,
so the freeze/reclaim behaviour from 8.2 did not reappear in this period (the process was active after the reboot).

That point has been handled in this version: what used to remain was `OcrScreenService-->getServiceIntent` (the screen-recognition service pre-binding right when the long press starts, initiated by
`OplusOcrScreenServiceHandler.onPreLongPress()`). It does not start screen recognition (the log shows no visibility change of `assistantscreen`), but it needlessly wakes the screen-recognition service once.
`onPreLongPress()` is now hooked as well: it unconditionally skips the pre-binding, the log shows
`gesture_handle_ocr_preload_skipped`, and `OcrScreenService-->getServiceIntent` no longer appears;
on paths the module does not take over (switch off, or the assistant pipeline fails to resolve), the OEM's original pre-binding behaviour is still kept.

### 8.4 Releasing the bottom-corner gesture restriction per page

The launcher decides whether the bottom-corner gesture can be used through `com.android.systemui.shared.system.QuickStepContract.isAssistantGestureDisabled(long)`, whose mask on this device is `3083`:

| Bit | Constant | Meaning | Module handling |
| --- | --- | --- | --- |
| 1 | SYSUI_STATE_SCREEN_PINNING | Screen pinning | Keep blocked |
| 2 | SYSUI_STATE_NAV_BAR_HIDDEN | Navigation bar hidden | Keep blocked (when ALLOW_GESTURE is set, this bit is ignored by the original logic) |
| 8 | SYSUI_STATE_BOUNCER_SHOWING | Lock-screen password screen | Keep blocked |
| 128 | SYSUI_STATE_OVERVIEW_DISABLED | **Requested by the foreground app** | Release |
| 1024 | SYSUI_STATE_SEARCH_DISABLED | **Requested by the foreground app** | Release |
| 2048 | SYSUI_STATE_QUICK_SETTINGS_EXPANDED | QS expanded | Keep blocked |
| 4 (and 64 not set) | SYSUI_STATE_NOTIFICATION_PANEL_EXPANDED | Notification shade expanded and not on lock screen | Keep blocked |

128 and 1024 are requested by the foreground app through interfaces like `StatusBarManager.disable()`, and they are exactly the source of "the bottom-corner gesture does nothing on some pages (such as some pages in Settings)". These two bits do not conflict with the gesture itself, so the module only clears them and keeps blocking every other state.
Matching logs: when releasing it logs `assist_gesture_unblocked pageFlags=0x...`, and when keeping it blocked it logs `assist_gesture_keep_disabled flags=0x...` (that line carries the full flags, making it easy to check next time which bit is in effect).
Note that this requires adding `com.android.launcher` to the module's scope; the power-key chain is not affected by the page (`StrategyIngoreKeyInFocusedWindow` only intercepts HOME/MENU, not the power key).

### 8.5 Gesture-handle long press changed to Circle to Search

In the CN firmware's SystemUI, the CTS part is an empty implementation (`OplusCircleToSearchManagerEx.interceptStartAssistInternal()` always returns false and Impl is an empty class),
so instead of relying on assistant routing, the system-service chain is filled in. The approach follows the implementation idea of `E:\\我开发的模块\\Gemini2\\Oplus-Assistant-Hook`:

| Layer | Process | Module action |
| --- | --- | --- |
| Framework CTS service | `system` | `SystemServer.deviceHasConfigString()` is forced true for `config_defaultContextualSearchPackageName`; `ContextualSearchManagerService.getContextualSearchPackageName()` returns the Google package name; `enforcePermission()` only lets system and SystemUI through; `startContextualSearch(int)` clears the calling identity for trusted callers |
| Google app identity | `com.google.android.googlequicksearchbox` | Inside the process, `Build.MANUFACTURER/BRAND/MODEL/PRODUCT/DEVICE` are spoofed as Samsung SM-S928B (e3s), unlocking Circle to Search on the GSA side |
| Gesture trigger | `com.android.systemui` | A gesture-handle long press **first checks whether the default assistant is the Google app**: if so, it calls `IContextualSearchManager.startContextualSearch(2)` of the `contextual_search` service; if not (for example it is set to Breeno) or the service is unavailable, it **dispatches to the current default assistant** instead |

The scope therefore gains one entry: `com.google.android.googlequicksearchbox` (see `scope.list`).

Log keywords: `cts_device_has_config_string forced=true`, `cts_package_name`, `cts_enforce_permission`,
`cts_start_contextual_search entrypoint=2`, `google_app_identity_spoofed`, `circle_to_search_triggered`, `gesture_handle_assistant component=... circleToSearch=true|false` (the latter shows whether that gesture followed the default assistant or Google's Circle to Search);
when falling back to the assistant it logs `circle_to_search_unavailable` or `circle_to_search_failed`.

Relation to Oplus-Assistant-Hook: both modules do the same thing, so **do not enable both for the same gesture**. If you enable the other module's gesture-handle or power-key takeover (it hooks in the outer layers such as `OplusOcrScreenBusiness.onLongPressed` / `OplusSpeechHandler.handleMessage`), it takes over first and returns directly, and the corresponding hook of this module never runs.

## 9. Summary

OplusAssistant is a minimal libxposed API 102 module that restores AOSP default-assistant behaviour on
China-region ColorOS builds. The build keeps the AOSP assistant stack intact and only diverts each
entry point, so the module reconnects those four diversion points: the power-key funnel
(`PhoneWindowManagerExtImpl.startSpeech` in `system_server`), the gesture-handle long press
(`SpeedChassistMainBusiness.onLongPressed`), the corner-swipe availability signal
(`NavBarUtils.isAssistantAvailable`), and the region-gated `AssistManager.startAssist`, which
currently drops every assist request on a China build. Scope is limited to `system` and
`com.android.systemui`. The launcher side was verified from the device's OplusLauncher APK: it carries
no region gate of its own and enables the corner gesture purely from the availability flag SystemUI
sends, and the framework default for that gesture resolves to true on this build. Installing and
running the module on the device is still the remaining validation step.

A fourth, narrower gate sits in `SideGestureDetector`: the bottom-area motion events are only
handed to the gesture handle while `NavBarUtils.isSideGestureBarHide()` is false, so hiding the
gesture bar also removes the handle from the touch chain and the long press at that position does
nothing. The module answers that one call - and only the one made by the detector, leaving the
flag's other readers such as the transparent navigation-bar window untouched - with `false`, which
is the behaviour OxygenOS keeps. It can be turned off on the advanced page.

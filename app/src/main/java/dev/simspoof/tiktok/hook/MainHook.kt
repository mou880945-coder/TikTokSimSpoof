package dev.simspoof.tiktok.hook

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import dev.simspoof.tiktok.config.SpoofConfig
import java.util.Locale

/**
 * LSPosed hook entry point.
 *
 * Hooks all TelephonyManager methods that TikTok uses to determine
 * the device's SIM/network country and operator identity.
 *
 * Also hooks:
 *  - SubscriptionManager  (Android 5.1+)
 *  - Build fields          (some TikTok builds read Build.SERIAL / fingerprint)
 *  - Locale.getDefault()  (optional, controlled by config)
 */
class MainHook : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "SimSpoof"

        private val TIKTOK_PACKAGES = setOf(
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill",
            "com.zhiliaoapp.musically.go",
            "com.ss.android.ugc.aweme",
            "com.tiktok.business"
        )

        private const val MODULE_PACKAGE = "dev.simspoof.tiktok"
    }

    // Loaded once per process start of the target app
    private lateinit var cfg: SpoofConfig

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        if (lpparam.packageName !in TIKTOK_PACKAGES) return

        // Read config from module's private prefs — LSPosed reads via its own root access
        val prefs = XSharedPreferences(MODULE_PACKAGE, SpoofConfig.PREFS_NAME)
        cfg = SpoofConfig.fromPrefs(prefs)

        if (!cfg.enabled) {
            XposedBridge.log("$TAG: disabled for ${lpparam.packageName}, skipping")
            return
        }

        XposedBridge.log("$TAG: hooking ${lpparam.packageName} — country=${cfg.simCountryIso} op=${cfg.simOperatorName}")

        hookTelephonyManager(lpparam.classLoader)
        hookSubscriptionManager(lpparam.classLoader)
        if (cfg.spoofLocale) hookLocale(lpparam.classLoader)
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  TelephonyManager
    // ──────────────────────────────────────────────────────────────────────────

    private fun hookTelephonyManager(cl: ClassLoader) {
        val tm = "android.telephony.TelephonyManager"

        // getSimCountryIso()
        hookReturn(cl, tm, "getSimCountryIso") { cfg.simCountryIso }

        // getNetworkCountryIso()
        hookReturn(cl, tm, "getNetworkCountryIso") { cfg.networkCountryIso }

        // getSimOperator()  — returns MCC+MNC string e.g. "31026"
        hookReturn(cl, tm, "getSimOperator") { cfg.simOperatorMccMnc }

        // getNetworkOperator()
        hookReturn(cl, tm, "getNetworkOperator") { cfg.networkOperatorMccMnc }

        // getSimOperatorName()
        hookReturn(cl, tm, "getSimOperatorName") { cfg.simOperatorName }

        // getNetworkOperatorName()
        hookReturn(cl, tm, "getNetworkOperatorName") { cfg.networkOperatorName }

        // getSimState()
        hookReturn(cl, tm, "getSimState") { cfg.simState }

        // getPhoneType()
        hookReturn(cl, tm, "getPhoneType") { cfg.phoneType }

        // getNetworkType()  — deprecated in API 30, but TikTok still calls it
        hookReturn(cl, tm, "getNetworkType") { cfg.networkType }

        // getDataNetworkType()  — API 30+
        hookReturnSafe(cl, tm, "getDataNetworkType") { cfg.networkType }

        // getVoiceNetworkType()
        hookReturnSafe(cl, tm, "getVoiceNetworkType") { cfg.networkType }

        // getLine1Number()  — may be "" if blank in config
        if (cfg.line1Number.isNotBlank()) {
            hookReturn(cl, tm, "getLine1Number") { cfg.line1Number }
        }

        // getSubscriberId()  — IMSI
        if (cfg.subscriberId.isNotBlank()) {
            hookReturn(cl, tm, "getSubscriberId") { cfg.subscriberId }
        }

        // getSimSerialNumber() / getIccId()
        if (cfg.iccId.isNotBlank()) {
            hookReturnSafe(cl, tm, "getSimSerialNumber") { cfg.iccId }
            hookReturnSafe(cl, tm, "getIccId") { cfg.iccId }
        }

        // getMmsUserAgent() — can expose country
        hookReturnSafe(cl, tm, "getMmsUserAgent") { "Android/${cfg.networkCountryIso.uppercase()}" }
        hookReturnSafe(cl, tm, "getMmsUAProfUrl") { "" }

        // isNetworkRoaming()
        hookReturn(cl, tm, "isNetworkRoaming") { false }

        XposedBridge.log("$TAG: TelephonyManager hooks installed (with Dual SIM support)")
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  SubscriptionManager  (Android 5.1+, API 22)
    // ──────────────────────────────────────────────────────────────────────────

    private fun hookSubscriptionManager(cl: ClassLoader) {
        val sm = "android.telephony.SubscriptionManager"

        // getActiveSubscriptionInfoList() — we let this pass through;
        // individual TelephonyManager calls are already hooked above.
        // Hook getCountryIso() if present (API 29+)
        hookReturnSafe(cl, sm, "getCountryIso") { cfg.simCountryIso }

        XposedBridge.log("$TAG: SubscriptionManager hooks installed")
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Locale
    // ──────────────────────────────────────────────────────────────────────────

    @Suppress("UNUSED_PARAMETER")
    private fun hookLocale(cl: ClassLoader) {
        val fakeLocale = Locale(cfg.localeLanguage, cfg.localeCountry)

        XposedHelpers.findAndHookMethod(
            Locale::class.java,
            "getDefault",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    param.result = fakeLocale
                }
            }
        )

        XposedBridge.log("$TAG: Locale.getDefault() → $fakeLocale")
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Helpers (Modified for Dual SIM compatibility)
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Hook a method and replace its return value.
     * Automatically attempts to hook both the no-arg version (Single SIM)
     * and the Int-arg version (Dual SIM subId重载) to cover all bases.
     */
    private fun hookReturn(cl: ClassLoader, className: String, methodName: String, value: () -> Any?) {
        val callback = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                param.result = value()
            }
        }

        // 1. Hook 无参数方法 (标准单卡)
        try {
            XposedHelpers.findAndHookMethod(className, cl, methodName, callback)
        } catch (e: XposedHelpers.ClassNotFoundError) {
            XposedBridge.log("$TAG: class not found — $className")
            return
        } catch (e: NoSuchMethodError) {
            // 如果没找到无参方法，不直接崩溃，继续尝试 Hook 带参重载
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: hook error [No-Arg] $className.$methodName — ${e.message}")
        }

        // 2. Hook 带 Int 参数的方法 (双卡重载方法，如 getSimCountryIso(int subId))
        try {
            XposedHelpers.findAndHookMethod(className, cl, methodName, Int::class.javaPrimitiveType, callback)
        } catch (_: NoSuchMethodError) {
            // 很多方法在老版本 Android 上可能没有双卡重载，找不到很正常，静默跳过
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: hook error [Int-Arg] $className.$methodName — ${e.message}")
        }
    }

    /**
     * Same as hookReturn but silently swallows missing methods.
     * Use for APIs that may not exist on all Android versions.
     */
    private fun hookReturnSafe(cl: ClassLoader, className: String, methodName: String, value: () -> Any?) {
        runCatching { hookReturn(cl, className, methodName, value) }
    }
}

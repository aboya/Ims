package io.github.vvb2060.ims;

import static rikka.shizuku.ShizukuProvider.METHOD_GET_BINDER;

import android.annotation.NonNull;
import android.annotation.SuppressLint;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcel;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionManager;
import android.util.Log;
import android.telephony.TelephonyManager;
import android.view.ViewDebug;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class PrivilegedProcess extends Instrumentation {
    static final String TAG = "vvb";

    @Override
    public void onCreate(Bundle arguments) {
        var binder = new Binder() {
            @Override
            protected boolean onTransact(int code, @NonNull Parcel data, Parcel reply, int flags) throws RemoteException {
                if (code == 1) {
                    try {
                        var context = getContext();
                        var persistent = canPersistent(context);
                        overrideConfig(context, persistent);
                      //  Log.d("ims_debug", "persistent = " + persistent);
                    } catch (Exception e) {
                        Log.e(TAG, Log.getStackTraceString(e));
                    }
                    var handler = new Handler(Looper.getMainLooper());
                    handler.postDelayed(() -> finish(0, new Bundle()), 1000);
                    return true;
                }
                return super.onTransact(code, data, reply, flags);
            }
        };
        var extras = new Bundle();
        extras.putBinder("binder", binder);
        var cr = getContext().getContentResolver();
        cr.call(BuildConfig.APPLICATION_ID + ".shizuku", METHOD_GET_BINDER, null, extras);
    }

    @SuppressLint("PrivateApi")
    private static boolean canPersistent(Context context) {
        try {
            var gms = context.createPackageContext("com.android.phone",
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
            var clazz = gms.getClassLoader().loadClass("com.android.phone.CarrierConfigLoader");
            try {
                clazz.getDeclaredMethod("isSystemApp");
            } catch (NoSuchMethodException e) {
                return true;
            }
            clazz.getDeclaredMethod("secureOverrideConfig", PersistableBundle.class, boolean.class);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @SuppressLint("MissingPermission")
    private static void overrideConfig(Context context, boolean persistent) {
        var cm = context.getSystemService(CarrierConfigManager.class);
        var sm = context.getSystemService(SubscriptionManager.class);

        for (var subId : sm.getActiveSubscriptionIdList()) {
            var info = sm.getActiveSubscriptionInfo(subId);
            if (info == null) {
                Log.i(TAG, "subId=" + subId + " skipped: no subscription info");
                continue;
            }
            var mcc = info.getMccString();
            var mnc = info.getMncString();
            if (mcc == null || mnc == null) {
                // SIM ещё не отдала MCC/MNC. Применить сейчас — значит записать конфиг
                // без оператор-специфичной части, поэтому ждём следующего вызова.
                Log.i(TAG, "subId=" + subId + " skipped: SIM records not loaded");
                continue;
            }
            var mccmnc = mcc + mnc;

            var values = getConfig();
            values.putInt("vvb2060_config_version", BuildConfig.VERSION_CODE);
            if (mccmnc.equals("25001")) {
                values.putAll(GetMts());
            }

            var stale = staleKeys(cm.getConfigForSubId(subId), values);
            if (stale.isEmpty()) {
                Log.i(TAG, "subId=" + subId + " (" + mccmnc + ") up to date, "
                        + values.size() + " keys checked");
                continue;
            }
            Log.i(TAG, "subId=" + subId + " (" + mccmnc + ") applying persistent=" + persistent
                    + ", stale=" + stale);
            cm.overrideConfig(subId, values, persistent);
        }
    }

    /**
     * Ключи, которых в действующем конфиге нет или значение отличается.
     * Сверяем фактические значения, а не маркер версии: маркер не замечает ни правок
     * в getConfig() без бампа versionCode, ни чужих перезаписей поверх наших ключей.
     */
    private static List<String> staleKeys(PersistableBundle current, PersistableBundle wanted) {
        var stale = new ArrayList<String>();
        for (var key : wanted.keySet()) {
            var want = wanted.get(key);
            var have = current == null ? null : current.get(key);
            boolean same;
            if (want instanceof int[] a && have instanceof int[] b) {
                same = Arrays.equals(a, b);
            } else {
                same = want != null && want.equals(have);
            }
            if (!same) stale.add(key);
        }
        return stale;
    }
    private  static PersistableBundle GetMts() {
        var bundle = new PersistableBundle();


        // Разрешаем только 2G + LTE + NR, 3G (UMTS/HSPA/HSDPA/HSUPA/HSPAP) - исключаем
        long allowedNetworks =
                //TelephonyManager.NETWORK_TYPE_BITMASK_GSM    |  // 2G
                  //      TelephonyManager.NETWORK_TYPE_BITMASK_GPRS   |  // 2G
                    //    TelephonyManager.NETWORK_TYPE_BITMASK_EDGE   |  // 2G
                        TelephonyManager.NETWORK_TYPE_BITMASK_LTE    |  // 4G
                        TelephonyManager.NETWORK_TYPE_BITMASK_LTE_CA;   // 4G CA
                    //    TelephonyManager.NETWORK_TYPE_BITMASK_NR;       // 5G

        //bundle.putLong("allowed_network_types_bitmask_long", allowedNetworks);
       // bundle.putString("sim_country_iso_override_string", "us");
        //bundle.putString("carrier_name_string", "Mts");

        bundle.putString(CarrierConfigManager.KEY_SIM_COUNTRY_ISO_OVERRIDE_STRING, "us");
        bundle.putBoolean(CarrierConfigManager.KEY_CARRIER_NAME_OVERRIDE_BOOL, true);

        bundle.putString(CarrierConfigManager.KEY_CARRIER_NAME_STRING, "Mts");
        //bundle.putString(CarrierConfigManager.Key_allowed, "Mts");

        // Подмена имени работает (gsm.sim.operator.alpha = Mts), но в роуминге оно не
        // отрисовывается: display-condition из EF_SPN запрещает показ SPN вне домашней сети.
        // 3 = показывать и SPN, и PLMN. Плюс правило берёт роуминг из ServiceState, где
        // из-за KEY_FORCE_HOME_NETWORK_BOOL уже стоит HOME.
        bundle.putInt(CarrierConfigManager.KEY_SPN_DISPLAY_CONDITION_OVERRIDE_INT, 3);
        bundle.putBoolean(CarrierConfigManager.KEY_SPN_DISPLAY_RULE_USE_ROAMING_FROM_SERVICE_STATE_BOOL, true);
        return bundle;
    }

    private static PersistableBundle getConfig() {
        var bundle = new PersistableBundle();
        bundle.putBoolean(CarrierConfigManager.KEY_CARRIER_VOLTE_AVAILABLE_BOOL, true);
        bundle.putBoolean(CarrierConfigManager.KEY_CARRIER_SUPPORTS_SS_OVER_UT_BOOL, true);
        bundle.putBoolean(CarrierConfigManager.KEY_CARRIER_VT_AVAILABLE_BOOL, true);

        bundle.putBoolean(CarrierConfigManager.KEY_CARRIER_CROSS_SIM_IMS_AVAILABLE_BOOL, true);
        bundle.putBoolean(CarrierConfigManager.KEY_ENABLE_CROSS_SIM_CALLING_ON_OPPORTUNISTIC_DATA_BOOL, true);

        bundle.putBoolean(CarrierConfigManager.KEY_CARRIER_WFC_IMS_AVAILABLE_BOOL, true);
        bundle.putBoolean(CarrierConfigManager.KEY_CARRIER_WFC_SUPPORTS_WIFI_ONLY_BOOL, true);
        bundle.putBoolean(CarrierConfigManager.KEY_EDITABLE_WFC_MODE_BOOL, true);

        bundle.putBoolean(CarrierConfigManager.KEY_FORCE_HOME_NETWORK_BOOL, true);
        bundle.putBoolean(CarrierConfigManager.KEY_CARRIER_ALLOW_TURNOFF_IMS_BOOL, false);
        bundle.putBoolean(CarrierConfigManager.KEY_CARRIER_DEFAULT_WFC_IMS_ROAMING_ENABLED_BOOL, true);


        bundle.putBoolean(CarrierConfigManager.KEY_EDITABLE_WFC_ROAMING_MODE_BOOL, true);
        bundle.putBoolean(CarrierConfigManager.KEY_SHOW_WIFI_CALLING_ICON_IN_STATUS_BAR_BOOL, true);
        bundle.putInt(CarrierConfigManager.KEY_WFC_SPN_FORMAT_IDX_INT, 4);




        bundle.putBoolean(CarrierConfigManager.KEY_EDITABLE_ENHANCED_4G_LTE_BOOL, true);
        bundle.putBoolean(CarrierConfigManager.KEY_HIDE_ENHANCED_4G_LTE_BOOL, false);
        bundle.putBoolean(CarrierConfigManager.KEY_HIDE_LTE_PLUS_DATA_ICON_BOOL, false);




        bundle.putBoolean(CarrierConfigManager.KEY_VONR_ENABLED_BOOL, true);
        bundle.putBoolean(CarrierConfigManager.KEY_VONR_SETTING_VISIBILITY_BOOL, true);
        bundle.putIntArray(CarrierConfigManager.KEY_CARRIER_NR_AVAILABILITIES_INT_ARRAY,
                new int[]{CarrierConfigManager.CARRIER_NR_AVAILABILITY_NSA,
                        CarrierConfigManager.CARRIER_NR_AVAILABILITY_SA});
        bundle.putIntArray(CarrierConfigManager.KEY_5G_NR_SSRSRP_THRESHOLDS_INT_ARRAY,
                // Boundaries: [-140 dBm, -44 dBm]
                new int[]{
                        -128, /* SIGNAL_STRENGTH_POOR */
                        -118, /* SIGNAL_STRENGTH_MODERATE */
                        -108, /* SIGNAL_STRENGTH_GOOD */
                        -98,  /* SIGNAL_STRENGTH_GREAT */
                });
        return bundle;
    }
}

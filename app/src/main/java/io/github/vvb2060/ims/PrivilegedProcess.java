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
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionManager;
import android.util.Log;
import android.telephony.TelephonyManager;
import android.view.ViewDebug;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Pattern;

public class PrivilegedProcess extends Instrumentation {
    static final String TAG = "vvb";

    /** Сервис com.android.phone, у которого спрашиваем список файлов конфига. */
    private static final String CARRIER_CONFIG_SERVICE = "carrier_config";

    /** Маркер persistent-копии: carrierconfig-<пакет>-override-<ICCID>-<carrierId>.xml */
    private static final String OVERRIDE_MARK = "-override-";

    /** Сервис SubscriptionManagerService — единственный доступный источник ICCID. */
    private static final String SUBSCRIPTION_SERVICE = "isub";

    private static final String SUB_INFO_MARK = "[SubscriptionInfoInternal:";

    /** "[SubscriptionInfoInternal: id=5 iccId=897010182[****] simSlotIndex=0 …" */
    private static final Pattern SUB_ID_ICCID = Pattern.compile("[\\[ ]id=(\\d+) iccId=(\\d+)");

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
        // Один дамп на прогон, а не на SIM: он отдаёт список сразу по всем.
        var cached = persistent ? cachedConfigFiles() : null;
        var iccids = cached == null ? Map.<Integer, String>of() : iccidPrefixes();
        var activeSubIds = sm.getActiveSubscriptionIdList();

        for (var subId : activeSubIds) {
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

            // Два независимых повода переписать конфиг.
            //
            // 1. Значения разъехались — кто-то перетёр наши ключи.
            // 2. Persistent-копии нет на диске. CarrierConfigLoader сносит кэш фильтром
            //    по префиксу "carrierconfig-<пакет>-", под который попадает и наш
            //    "…-override-<ICCID>-<carrierId>.xml"; RAM-слой при этом не трогается.
            //    Файла уже нет, склеенный конфиг ещё верный — то есть staleKeys() пуст,
            //    и по нему одному пропажа не видна ничем до ближайшего ребута, после
            //    которого конфиг исчезает целиком.
            //
            // Триггеров у чистки три, и все три ведут к CARRIER_CONFIG_CHANGED:
            // notifyConfigChangedForSubId() от carrier-приложения, EVENT_PACKAGE_CHANGED
            // и смена build fingerprint (OTA).
            var stale = staleKeys(cm.getConfigForSubId(subId), values);
            var reason = stale.isEmpty() ? null : "stale=" + stale;
            if (reason == null && cached != null
                    && !hasPersistentCopy(cached, iccids, context, subId, activeSubIds)) {
                reason = "persistent copy missing";
            }
            if (reason == null) {
                Log.i(TAG, "subId=" + subId + " (" + mccmnc + ") up to date, "
                        + values.size() + " keys checked");
                continue;
            }
            Log.i(TAG, "subId=" + subId + " (" + mccmnc + ") applying persistent=" + persistent
                    + ", " + values.size() + " keys, " + reason);
            cm.overrideConfig(subId, values, persistent);
        }
    }

    /**
     * Строки дампа системного сервиса, прошедшие фильтр.
     * <p>
     * Прочитать каталог com.android.phone напрямую нельзя — нужен его uid, а делегированные
     * shell-права дают только permissions. Зато среди них есть android.permission.DUMP,
     * который CarrierConfigLoader.dump() и проверяет.
     *
     * @return строки либо null, если дамп не удался
     */
    private static List<String> dumpLines(String service, Predicate<String> keep) {
        try {
            var binder = ServiceManager.getService(service);
            if (binder == null) {
                Log.i(TAG, "no " + service + " service");
                return null;
            }
            var pipe = ParcelFileDescriptor.createPipe();
            var lines = new ArrayList<String>();
            // Читать обязательно параллельно: dump() синхронный, а пайп всего 64 КБ —
            // на дампе под 60 КБ сервис встанет в write(), и мы получим взаимоблокировку.
            var reader = new Thread(() -> {
                try (var in = new BufferedReader(new InputStreamReader(
                        new ParcelFileDescriptor.AutoCloseInputStream(pipe[0]),
                        StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = in.readLine()) != null) {
                        var trimmed = line.trim();
                        if (keep.test(trimmed)) lines.add(trimmed);
                    }
                } catch (Exception e) {
                    Log.e(TAG, Log.getStackTraceString(e));
                }
            });
            reader.start();
            try {
                binder.dump(pipe[1].getFileDescriptor(), new String[0]);
            } finally {
                pipe[1].close();
            }
            reader.join(5000);
            return lines;
        } catch (Exception e) {
            Log.e(TAG, Log.getStackTraceString(e));
            return null;
        }
    }

    /**
     * Файлы кэша carrier config. Секцию "Cached config files" отдельно не ищем: только её
     * строки начинаются с имени файла, в остальных местах дампа оно встречается внутри
     * текста ("Restored carrier config from cache. phoneId=1, xml=...").
     */
    private static List<String> cachedConfigFiles() {
        var files = dumpLines(CARRIER_CONFIG_SERVICE, line -> line.startsWith("carrierconfig-"));
        Log.i(TAG, "cached config files: " + (files == null ? "dump failed" : files.size()));
        return files;
    }

    /**
     * subId → видимая часть ICCID, из дампа SubscriptionManagerService.
     * <p>
     * Прямые API не отдают: getIccId() возвращает пустую строку, а getSimSerialNumber()
     * кидает SecurityException («uid 20448 does not meet the requirements to access device
     * identifiers») — редактирование идентификаторов смотрит на пакет вызывающего, а он
     * у нас sdk-sandbox. В дампе ICCID тоже урезан, но до 9 символов, а нам для сверки
     * с именем файла нужно 5.
     */
    private static Map<Integer, String> iccidPrefixes() {
        var lines = dumpLines(SUBSCRIPTION_SERVICE, line -> line.contains(SUB_INFO_MARK));
        if (lines == null) return Map.of();
        var result = new HashMap<Integer, String>();
        for (var line : lines) {
            var m = SUB_ID_ICCID.matcher(line);
            if (m.find()) result.put(Integer.parseInt(m.group(1)), m.group(2));
        }
        return result;
    }

    /**
     * Есть ли среди файлов наша persistent-копия для этой SIM.
     * <p>
     * Имена в дампе замаскированы: getFilePathForLogging() оставляет первые 5 символов
     * ICCID и дописывает фиксированную звёздочную строку. carrierId виден целиком, и в
     * имени он именно specific — см. saveConfigToXml().
     * <p>
     * Если ICCID известен, сверяем его вместе с carrierId — это точное совпадение. Если
     * нет, считаем файлы с нужным carrierId и требуем, чтобы их было не меньше, чем
     * активных SIM с таким же carrierId. Такой матч слабее: осадок от вынутой SIM с тем
     * же carrierId сойдёт за наш файл (у одного оператора все SIM делят carrierId). Зато
     * ошибка всегда в сторону «не переписать», а не «переписывать на каждый бродкаст».
     */
    private static boolean hasPersistentCopy(List<String> files, Map<Integer, String> iccids,
                                             Context context, int subId, int[] activeSubIds) {
        var carrierId = specificCarrierId(context, subId);
        var iccid = iccids.get(subId);

        var matches = 0;
        for (var entry : files) {
            var name = entry;
            var comma = name.indexOf(',');
            if (comma > 0) name = name.substring(0, comma);
            var mark = name.indexOf(OVERRIDE_MARK);
            if (mark < 0 || !name.endsWith(".xml")) continue;

            var body = name.substring(0, name.length() - ".xml".length());
            var sep = body.lastIndexOf('-');
            // carrierId бывает отрицательным ("…-89357019240129009303--1.xml"),
            // тогда настоящий разделитель — предыдущий дефис.
            if (sep > 0 && body.charAt(sep - 1) == '-') sep--;
            if (sep <= mark) continue;
            int cid;
            try {
                cid = Integer.parseInt(body.substring(sep + 1));
            } catch (NumberFormatException e) {
                continue;
            }
            if (cid != carrierId) continue;

            if (iccid == null) {
                matches++;
                continue;
            }
            var masked = body.substring(mark + OVERRIDE_MARK.length(), sep);
            var star = masked.indexOf('*');
            var prefix = star < 0 ? masked : masked.substring(0, star);
            if (iccid.startsWith(prefix)) return true;
        }

        if (iccid != null) {
            Log.i(TAG, "subId=" + subId + " no override file for carrierId=" + carrierId
                    + " among " + files.size() + " cached");
            return false;
        }

        var needed = 0;
        for (var other : activeSubIds) {
            if (specificCarrierId(context, other) == carrierId) needed++;
        }
        if (matches >= needed) return true;
        Log.i(TAG, "subId=" + subId + " carrierId=" + carrierId + ": " + matches
                + " override files, need " + needed + " (iccid unknown)");
        return false;
    }

    private static int specificCarrierId(Context context, int subId) {
        return context.getSystemService(TelephonyManager.class)
                .createForSubscriptionId(subId)
                .getSimSpecificCarrierId();
    }

    /**
     * Ключи, которых в действующем конфиге нет или значение отличается.
     * <p>
     * Один из двух поводов переписать конфиг; второй — пропавшая копия на диске
     * (см. комментарий в overrideConfig()).
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
    /**
     * Нейтральные значения оператор-специфичных ключей — те же, что у AOSP по умолчанию.
     * Нужны потому, что mOverrideConfigs / mPersistentOverrideConfigs в com.android.phone
     * индексируются phoneId (слотом), а не subId: при смене SIM в слоте наш override
     * остаётся и достаётся новой симке. Пишем ключи всегда, чтобы staleKeys() заметил
     * чужой остаток и перетёр его.
     */
    private static void putCarrierNameDefaults(PersistableBundle bundle) {
        bundle.putString(CarrierConfigManager.KEY_SIM_COUNTRY_ISO_OVERRIDE_STRING, "");
        bundle.putBoolean(CarrierConfigManager.KEY_CARRIER_NAME_OVERRIDE_BOOL, false);
        bundle.putString(CarrierConfigManager.KEY_CARRIER_NAME_STRING, "");
        bundle.putInt(CarrierConfigManager.KEY_SPN_DISPLAY_CONDITION_OVERRIDE_INT, -1);
        bundle.putBoolean(CarrierConfigManager.KEY_SPN_DISPLAY_RULE_USE_ROAMING_FROM_SERVICE_STATE_BOOL, false);
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
        putCarrierNameDefaults(bundle);
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

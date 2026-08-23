package io.github.vvb2060.ims;

import static io.github.vvb2060.ims.PrivilegedProcess.TAG;

import android.content.Context;
import android.util.Log;

/**
 * Один гейт на оба входа — ресивер и провайдер.
 * <p>
 * Решение «писать или нет» принимает не он, а {@link PrivilegedProcess}: там сверяются
 * значения и наличие persistent-копии на диске. Гейт только глушит пачки бродкастов
 * и держит предохранитель.
 * <p>
 * Состояние лежит на диске, а не в статиках: {@code am.startInstrumentation()} перед
 * запуском делает force-stop нашего же пакета («Killing …: stop … due to start instr»
 * в логе ActivityManager), поэтому процесс приложения умирает на каждом прогоне и любой
 * статик обнуляется. По той же причине гейт обязан быть общим: после force-stop процесс
 * поднимается заново, Shizuku снова присылает биндер в провайдер, и тот запускает прогон —
 * мимо любой защиты, живущей внутри ресивера.
 */
final class RunGuard {
    /** Пачку бродкастов при загрузке достаточно обработать один раз. */
    private static final long DEBOUNCE_MS = 5000;

    /** Тишина, после которой предохранитель считается взведённым заново. */
    private static final long QUIET_MS = 60_000;

    /**
     * Предохранитель. Срабатывать не должен: прогон, которому нечего делать, ничего не
     * пишет и потому не порождает нового CARRIER_CONFIG_CHANGED — цепочка обрывается
     * сама. Если сработал, значит бродкасты идут без нашего участия, и это повод смотреть
     * логи, а не поднимать лимит.
     */
    private static final int MAX_RUNS = 20;

    private static final String PREFS = "run_guard";
    private static final String KEY_LAST_RUN = "last_run";
    private static final String KEY_RUNS = "runs";

    private RunGuard() {
    }

    /**
     * Отмечает прогон и говорит, запускать ли его. Вызывать строго перед
     * {@link ShizukuProvider#startInstrument}: сразу после него нас убьют.
     */
    static boolean claim(Context context, String who) {
        // elapsedRealtime() не годится: между прогонами процесс умирает, а отсчёт от
        // загрузки не с чем сравнивать, если сама загрузка была между ними.
        var prefs = context.createDeviceProtectedStorageContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        var now = System.currentTimeMillis();
        var prev = prefs.getLong(KEY_LAST_RUN, 0);
        var runs = prefs.getInt(KEY_RUNS, 0);

        // now < prev — часы уехали назад (NTP при загрузке). Считаем это началом серии:
        // иначе разница уходит в минус, вечно проходит по debounce, и прогона не будет
        // уже никогда.
        var jumped = now < prev;
        if (prev != 0 && !jumped && now - prev < DEBOUNCE_MS) {
            Log.i(TAG, who + ": debounced");
            return false;
        }
        if (prev == 0 || jumped || now - prev > QUIET_MS) runs = 0;

        var run = runs + 1;
        // commit(), а не apply(): нас убьют сразу после startInstrument(),
        // и фоновая запись до диска не долетит.
        prefs.edit().putLong(KEY_LAST_RUN, now).putInt(KEY_RUNS, run).commit();

        if (run > MAX_RUNS) {
            Log.i(TAG, who + ": run limit reached (" + MAX_RUNS + "), skipping");
            return false;
        }
        Log.i(TAG, who + ": run " + run);
        return true;
    }
}

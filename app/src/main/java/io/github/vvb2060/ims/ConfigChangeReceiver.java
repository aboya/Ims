package io.github.vvb2060.ims;

import static io.github.vvb2060.ims.PrivilegedProcess.TAG;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import rikka.shizuku.Shizuku;

/**
 * Переприменяет конфиг при смене SIM.
 * <p>
 * Без этого приложение отрабатывает ровно один раз — когда Shizuku присылает биндер, —
 * и любая SIM, появившаяся позже (замена, переключение eSIM-профиля, смена carrierId),
 * остаётся без конфига до перезапуска Shizuku.
 */
public class ConfigChangeReceiver extends BroadcastReceiver {
    /** Ручной запуск: adb shell am broadcast -a io.github.vvb2060.ims.action.APPLY -n io.github.vvb2060.ims/.ConfigChangeReceiver */
    public static final String ACTION_APPLY = "io.github.vvb2060.ims.action.APPLY";

    /**
     * Сколько ждать биндер. Бродкасты одному ресиверу доставляются последовательно,
     * поэтому ожидание умножается на длину очереди — при загрузке их прилетает пачка.
     * Если Shizuku работает, биндер либо уже есть (sticky сработает сразу), либо
     * приходит почти мгновенно; если не работает, ждать смысла нет.
     */
    private static final long BINDER_TIMEOUT_MS = 2000;

    /** Пачку бродкастов при загрузке достаточно обработать один раз. */
    private static final long DEBOUNCE_MS = 5000;

    private static final AtomicLong lastRun = new AtomicLong();

    @Override
    public void onReceive(Context context, Intent intent) {
        // Шумит на каждый SIM- и carrier-бродкаст, а пока Shizuku не поднят — пачками.
        // Раскомментировать, когда снова понадобится ловить доставку бродкастов.
        // Log.i(TAG, "receiver: " + intent.getAction());

        var now = SystemClock.elapsedRealtime();
        var prev = lastRun.get();
        if (prev != 0 && now - prev < DEBOUNCE_MS) return;
        lastRun.set(now);

        var appContext = context.getApplicationContext();
        var pending = goAsync();
        var done = new AtomicBoolean();

        // Слушателя обязательно снимаем: пока Shizuku не запущен, бродкасты идут
        // пачками, и накопленные слушатели выстрелили бы все разом при его старте.
        var listener = new Shizuku.OnBinderReceivedListener[1];
        Runnable finish = () -> {
            if (done.getAndSet(true)) return;
            Shizuku.removeBinderReceivedListener(listener[0]);
            pending.finish();
        };

        listener[0] = () -> {
            try {
                if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    ShizukuProvider.startInstrument(appContext);
                } else {
                    Log.i(TAG, "receiver: shizuku permission not granted");
                }
            } catch (Exception e) {
                Log.e(TAG, Log.getStackTraceString(e));
            } finally {
                finish.run();
            }
        };
        Shizuku.addBinderReceivedListenerSticky(listener[0]);

        // Если биндер не пришёл, завершаем сами, иначе система ругнётся на
        // незавершённый goAsync(). Дальше отработает штатный путь через
        // ShizukuProvider, когда Shizuku пришлёт биндер сам.
        new Handler(Looper.getMainLooper()).postDelayed(finish::run, BINDER_TIMEOUT_MS);
    }
}

package io.github.vvb2060.ims;

import static io.github.vvb2060.ims.PrivilegedProcess.TAG;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

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

    /**
     * Запас сверх остатка окна debounce: гейт сверяет по часам, Handler — по uptime.
     * Вместе с ожиданием биндера goAsync() держится не дольше ~7.1 с — меньше таймаута
     * бродкаста (10 с для foreground-очереди).
     */
    private static final long RETRY_MARGIN_MS = 100;

    @Override
    public void onReceive(Context context, Intent intent) {
        // Шумит на каждый SIM- и carrier-бродкаст, а пока Shizuku не поднят — пачками.
        // Раскомментировать, когда снова понадобится ловить доставку бродкастов.
        // Log.i(TAG, "receiver: " + intent.getAction());

        var appContext = context.getApplicationContext();
        var pending = goAsync();
        var handler = new Handler(Looper.getMainLooper());
        var done = new AtomicBoolean();
        var fired = new AtomicBoolean();

        // Слушателя обязательно снимаем: пока Shizuku не запущен, бродкасты идут
        // пачками, и накопленные слушатели выстрелили бы все разом при его старте.
        var listener = new Shizuku.OnBinderReceivedListener[1];
        Runnable finish = () -> {
            if (done.getAndSet(true)) return;
            Shizuku.removeBinderReceivedListener(listener[0]);
            pending.finish();
        };

        listener[0] = () -> {
            // Биндер пришёл после таймаута — поздно, дальше штатный путь через провайдер.
            if (done.get() || fired.getAndSet(true)) return;
            handler.removeCallbacks(finish);
            Shizuku.removeBinderReceivedListener(listener[0]);

            var wait = tryRun(appContext, "receiver");
            if (wait > 0) {
                // Debounce: этот бродкаст мог нести то, чего не видел идущий прогон
                // (SIM догрузилась). Повторяем один раз по концу окна; goAsync() держим,
                // иначе процесс заморозят и Handler не сработает. Прочие бродкасты тем
                // временем стоят в очереди, а если повтор запустит прогон, force-stop
                // снесёт и нас, и её.
                handler.postDelayed(() -> {
                    tryRun(appContext, "receiver retry");
                    finish.run();
                }, wait + RETRY_MARGIN_MS);
            } else {
                finish.run();
            }
        };
        // Если биндер не пришёл, завершаем сами, иначе система ругнётся на
        // незавершённый goAsync(). Дальше отработает штатный путь через
        // ShizukuProvider, когда Shizuku пришлёт биндер сам.
        // Ставить до add: sticky может вызвать слушатель прямо внутри него, и тот
        // должен найти таймаут, чтобы снять, — иначе таймаут закроет goAsync()
        // посреди ожидания повтора.
        handler.postDelayed(finish, BINDER_TIMEOUT_MS);

        Shizuku.addBinderReceivedListenerSticky(listener[0]);
        // На main-потоке sticky зовёт слушатель прямо внутри add и только потом кладёт
        // его в список, так что снятие изнутри слушателя не сработало — снимаем здесь.
        if (fired.get()) Shizuku.removeBinderReceivedListener(listener[0]);
    }

    /**
     * Спросить гейт и, если разрешил, запустить прогон.
     *
     * @return ответ {@link RunGuard#claim}; {@link RunGuard#NEVER}, если спрашивать
     * было нельзя или что-то упало
     */
    private static long tryRun(Context context, String who) {
        try {
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, who + ": shizuku permission not granted");
                return RunGuard.NEVER;
            }
            // Гейт спрашиваем здесь, а не в начале onReceive: пока Shizuku лежит,
            // бродкасты идут пачками и впустую съедали бы и debounce, и лимит.
            var wait = RunGuard.claim(context, who);
            if (wait == RunGuard.RUN) {
                ShizukuProvider.startInstrument(context);
            }
            return wait;
        } catch (Exception e) {
            Log.e(TAG, Log.getStackTraceString(e));
            return RunGuard.NEVER;
        }
    }
}

package io.github.vvb2060.ims;

import static io.github.vvb2060.ims.PrivilegedProcess.TAG;

import android.app.ActivityManager;
import android.app.IActivityManager;
import android.app.UiAutomationConnection;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.Process;
import android.os.ServiceManager;
import android.system.Os;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

import org.lsposed.hiddenapibypass.LSPass;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuBinderWrapper;

public class ShizukuProvider extends rikka.shizuku.ShizukuProvider {
    static {
        LSPass.setHiddenApiExemptions("");
    }

    // Пишется на binder-потоке (GET_BINDER), читается на main (слушатель SEND_BINDER).
    private volatile boolean skip = false;

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        var sdkUid = Process.toSdkSandboxUid(Os.getuid());
        var callingUid = Binder.getCallingUid();
        if (callingUid != sdkUid && callingUid != Process.SHELL_UID) {
            return new Bundle();
        }

        if (METHOD_SEND_BINDER.equals(method)) {
            Shizuku.addBinderReceivedListener(() -> {
                if (!skip && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    // Через этот вход прогон приходит и после каждого force-stop: процесс
                    // поднялся заново, Shizuku прислал биндер. Без общего гейта получается
                    // вечный цикл запись → бродкаст → force-stop → биндер → запись.
                    if (RunGuard.claim(getContext(), "provider") == RunGuard.RUN) {
                        startInstrument(getContext());
                    }
                }
            });
        } else if (METHOD_GET_BINDER.equals(method) && callingUid == sdkUid && extras != null) {
            skip = true;
            runOnceWithBinder(() -> {
                var binder = extras.getBinder("binder");
                if (binder != null && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    startShellPermissionDelegate(binder, sdkUid);
                }
            });
        }
        return super.call(method, arg, extras);
    }

    /**
     * Выполнить один раз, как только есть биндер Shizuku, — в том числе если он пришёл раньше.
     * <p>
     * SEND_BINDER от Shizuku и GET_BINDER от sandbox приходят почти одновременно, на разные
     * binder-потоки. Обычный addBinderReceivedListener срабатывает только на будущую
     * рассылку: если SEND_BINDER обработался первым, слушатель не вызовется никогда,
     * sandbox не получит transact и повиснет, не написав в лог ни строки. Так потерялся
     * прогон 10.09.2026 при вставке SIM.
     * <p>
     * Sticky закрывает это почти целиком, но binderReady выставляется уже после рассылки —
     * узкое окно остаётся. Его закрывает собственная проверка биндера: он присваивается
     * до рассылки, так что либо мы его видим, либо рассылка ещё впереди и нас застанет.
     */
    private static void runOnceWithBinder(Runnable action) {
        var fired = new AtomicBoolean();
        var listener = new Shizuku.OnBinderReceivedListener[1];
        listener[0] = () -> {
            if (fired.getAndSet(true)) return;
            Shizuku.removeBinderReceivedListener(listener[0]);
            action.run();
        };
        Shizuku.addBinderReceivedListenerSticky(listener[0]);
        if (Shizuku.pingBinder()) {
            new Handler(Looper.getMainLooper()).post(listener[0]::onBinderReceived);
        }
    }

    private static void startShellPermissionDelegate(IBinder binder, int sdkUid) {
        try {
            var activity = ServiceManager.getService(Context.ACTIVITY_SERVICE);
            var am = IActivityManager.Stub.asInterface(new ShizukuBinderWrapper(activity));
            am.startDelegateShellPermissionIdentity(sdkUid, null);
            var data = Parcel.obtain();
            binder.transact(1, data, null, 0);
            data.recycle();
            am.stopDelegateShellPermissionIdentity();
        } catch (Exception e) {
            Log.e(TAG, Log.getStackTraceString(e));
        }
    }

    /**
     * Внимание: startInstrumentation() перед запуском делает force-stop целевого пакета,
     * то есть убивает и процесс приложения. Ничего в памяти между прогонами не живёт;
     * гейт вызывать до, а не после — см. {@link RunGuard}.
     */
    static void startInstrument(Context context) {
        try {
            var binder = ServiceManager.getService(Context.ACTIVITY_SERVICE);
            var am = IActivityManager.Stub.asInterface(new ShizukuBinderWrapper(binder));
            var name = new ComponentName(context, PrivilegedProcess.class);
            var flags = ActivityManager.INSTR_FLAG_DISABLE_HIDDEN_API_CHECKS;
            flags |= ActivityManager.INSTR_FLAG_INSTRUMENT_SDK_SANDBOX;
            var connection = new UiAutomationConnection();
            am.startInstrumentation(name, null, flags, new Bundle(), null, connection, 0, null);
        } catch (Exception e) {
            Log.e(TAG, Log.getStackTraceString(e));
        }
    }
}

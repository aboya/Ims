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
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.ServiceManager;
import android.system.Os;
import android.util.Log;

import org.lsposed.hiddenapibypass.LSPass;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuBinderWrapper;

public class ShizukuProvider extends rikka.shizuku.ShizukuProvider {
    static {
        LSPass.setHiddenApiExemptions("");
    }

    private boolean skip = false;

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
                    startInstrument(getContext());
                }
            });
        } else if (METHOD_GET_BINDER.equals(method) && callingUid == sdkUid && extras != null) {
            skip = true;
            Shizuku.addBinderReceivedListener(() -> {
                var binder = extras.getBinder("binder");
                if (binder != null && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    startShellPermissionDelegate(binder, sdkUid);
                }
            });
        }
        return super.call(method, arg, extras);
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

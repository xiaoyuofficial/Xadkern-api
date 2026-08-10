package xadkern.api;


import static androidx.annotation.RestrictTo.Scope.LIBRARY_GROUP_PREFIX;
import static xadkern.shared.XadkernApiConstant.server.TYPE_ENV;
import static xadkern.shared.ShizukuApiConstant.BINDER_TRANSACTION_transact;
import static xadkern.shared.ShizukuApiConstant.BIND_APPLICATION_PERMISSION_GRANTED;
import static xadkern.shared.ShizukuApiConstant.BIND_APPLICATION_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE;
import static xadkern.shared.ShizukuApiConstant.REQUEST_PERMISSION_REPLY_ALLOWED;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import xadkern.api.core.XadkernSettings;
import xadkern.server.Environment;
import xadkern.server.IXadkernService;
import xadkern.server.PluginInfo;
import xadkern.shared.XadkernApiConstant;
import moe.shizuku.server.IShizukuApplication;
import moe.shizuku.server.IShizukuService;

public class Xadkern {
    private static final List<ListenerHolder<OnBinderReceivedListener>> RECEIVED_LISTENERS = new ArrayList<>();
    private static final List<ListenerHolder<OnBinderDeadListener>> DEAD_LISTENERS = new ArrayList<>();
    private static final List<ListenerHolder<OnRequestPermissionResultListener>> PERMISSION_LISTENERS = new ArrayList<>();

    protected static String TAG = "XadkernApplication";
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static IBinder binder;
    private static IXadkernService service;
    private static XadkernInfo axeronInfo = null;
    private static boolean binderReady = false;

    private static boolean permissionGranted = false;
    private static boolean shouldShowRequestPermissionRationale = false;

    private static final IShizukuApplication SHIZUKU_APPLICATION = new IShizukuApplication.Stub() {

        @Override
        public void bindApplication(Bundle data) {
//            serverUid = data.getInt(BIND_APPLICATION_SERVER_UID, -1);
//            serverApiVersion = data.getInt(BIND_APPLICATION_SERVER_VERSION, -1);
//            serverPatchVersion = data.getInt(BIND_APPLICATION_SERVER_PATCH_VERSION, -1);
//            serverContext = data.getString(BIND_APPLICATION_SERVER_SECONTEXT);
            permissionGranted = data.getBoolean(BIND_APPLICATION_PERMISSION_GRANTED, false);
            shouldShowRequestPermissionRationale = data.getBoolean(BIND_APPLICATION_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE, false);

            scheduleBinderReceivedListeners();

            if (isFirstInit(false)
                    || XadkernSettings.getEnableIgniteRelog()) {
                Log.d(TAG, "igniteService");
                XadkernPluginService.igniteService();
            }
        }

        @Override
        public void dispatchRequestPermissionResult(int requestCode, Bundle data) {
            boolean allowed = data.getBoolean(REQUEST_PERMISSION_REPLY_ALLOWED, false);
            scheduleRequestPermissionResultListener(requestCode, allowed ? PackageManager.PERMISSION_GRANTED : PackageManager.PERMISSION_DENIED);
        }

        @Override
        public void showPermissionConfirmation(int requestUid, int requestPid, String requestPackageName, int requestCode) {
            // non-app
        }
    };

    public static int checkSelfPermission() {
        if (permissionGranted) return PackageManager.PERMISSION_GRANTED;
        try {
            permissionGranted = requireService().checkSelfPermission();
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
        return permissionGranted ? PackageManager.PERMISSION_GRANTED : PackageManager.PERMISSION_DENIED;
    }

    public static boolean shouldShowRequestPermissionRationale() {
        if (permissionGranted) return false;
        if (shouldShowRequestPermissionRationale) return true;
        try {
            shouldShowRequestPermissionRationale = requireService().shouldShowRequestPermissionRationale();
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
        return shouldShowRequestPermissionRationale;
    }

    public static void addRequestPermissionResultListener(@NonNull OnRequestPermissionResultListener listener) {
        addRequestPermissionResultListener(listener, null);
    }

    public static void addRequestPermissionResultListener(@NonNull OnRequestPermissionResultListener listener, @Nullable Handler handler) {
        synchronized (RECEIVED_LISTENERS) {
            PERMISSION_LISTENERS.add(new ListenerHolder<>(listener, handler));
        }
    }

    public static boolean removeRequestPermissionResultListener(@NonNull OnRequestPermissionResultListener listener) {
        synchronized (RECEIVED_LISTENERS) {
            return PERMISSION_LISTENERS.removeIf(holder -> holder.listener == listener);
        }
    }

    private static void scheduleRequestPermissionResultListener(int requestCode, int result) {
        synchronized (RECEIVED_LISTENERS) {
            for (ListenerHolder<OnRequestPermissionResultListener> holder : PERMISSION_LISTENERS) {
                if (holder.handler != null) {
                    holder.handler.post(() -> holder.listener.onRequestPermissionResult(requestCode, result));
                } else {
                    if (Looper.myLooper() == Looper.getMainLooper()) {
                        holder.listener.onRequestPermissionResult(requestCode, result);
                    } else {
                        MAIN_HANDLER.post(() -> holder.listener.onRequestPermissionResult(requestCode, result));
                    }
                }
            }
        }
    }

    public static void requestPermission(int requestCode) {
        try {
            requireService().requestPermission(requestCode);
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
    }

    private static void scheduleBinderDeadListeners() {
        synchronized (RECEIVED_LISTENERS) {
            for (ListenerHolder<OnBinderDeadListener> holder : DEAD_LISTENERS) {
                if (holder.handler != null) {
                    holder.handler.post(holder.listener::onBinderDead);
                } else {
                    if (Looper.myLooper() == Looper.getMainLooper()) {
                        holder.listener.onBinderDead();
                    } else {
                        MAIN_HANDLER.post(holder.listener::onBinderDead);
                    }
                }

            }
        }
    }

    public static void addBinderDeadListener(@NonNull OnBinderDeadListener listener) {
        addBinderDeadListener(listener, null);
    }

    public static void addBinderDeadListener(@NonNull OnBinderDeadListener listener, @Nullable Handler handler) {
        synchronized (RECEIVED_LISTENERS) {
            DEAD_LISTENERS.add(new ListenerHolder<>(listener, handler));
        }
    }

    public static boolean removeBinderDeadListener(@NonNull OnBinderDeadListener listener) {
        synchronized (RECEIVED_LISTENERS) {
            return DEAD_LISTENERS.removeIf(holder -> holder.listener == listener);
        }
    }

    private static void scheduleBinderReceivedListeners() {
        synchronized (RECEIVED_LISTENERS) {
            for (ListenerHolder<OnBinderReceivedListener> holder : RECEIVED_LISTENERS) {
                if (holder.handler != null) {
                    holder.handler.post(holder.listener::onBinderReceived);
                } else {
                    if (Looper.myLooper() == Looper.getMainLooper()) {
                        holder.listener.onBinderReceived();
                    } else {
                        MAIN_HANDLER.post(holder.listener::onBinderReceived);
                    }
                }
            }
        }
        binderReady = true;
    }

    public static void addBinderReceivedListener(@NonNull OnBinderReceivedListener listener) {
        addBinderReceivedListener(listener, null);
    }

    public static void addBinderReceivedListener(@NonNull OnBinderReceivedListener listener, @Nullable Handler handler) {
        addBinderReceivedListener(Objects.requireNonNull(listener), false, handler);
    }

    public static void addBinderReceivedListenerSticky(@NonNull OnBinderReceivedListener listener) {
        addBinderReceivedListenerSticky(Objects.requireNonNull(listener), null);
    }

    public static void addBinderReceivedListenerSticky(@NonNull OnBinderReceivedListener listener, @Nullable Handler handler) {
        addBinderReceivedListener(Objects.requireNonNull(listener), true, handler);
    }

    private static void addBinderReceivedListener(@NonNull OnBinderReceivedListener listener, boolean sticky, @Nullable Handler handler) {
        if (sticky && binderReady) {
            if (handler != null) {
                handler.post(listener::onBinderReceived);
            } else if (Looper.myLooper() == Looper.getMainLooper()) {
                listener.onBinderReceived();
            } else {
                MAIN_HANDLER.post(listener::onBinderReceived);
            }
        }
        synchronized (RECEIVED_LISTENERS) {
            RECEIVED_LISTENERS.add(new ListenerHolder<>(listener, handler));
        }
    }

    public static boolean removeBinderReceivedListener(@NonNull OnBinderReceivedListener listener) {
        synchronized (RECEIVED_LISTENERS) {
            return RECEIVED_LISTENERS.removeIf(holder -> holder.listener == listener);
        }
    }

    public static void onBinderReceived(IBinder newBinder, String packageName) {
        if (binder == newBinder) return;

        if (newBinder == null) {
            binder = null;
            service = null;
            axeronInfo = null;

            scheduleBinderDeadListeners();
        } else {
//            SharedPreferences prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE);

            if (pingBinder()) {
                binder.unlinkToDeath(DEATH_RECIPIENT, 0);
            }
            binder = newBinder;
            service = IXadkernService.Stub.asInterface(newBinder);

            try {
                binder.linkToDeath(DEATH_RECIPIENT, 0);
            } catch (Throwable e) {
                Log.i(TAG, "attachApplication");
            }

            try {
                attachApplication(binder, packageName);
                Log.i("ShizukuApplication", "attachApplication");
            } catch (Throwable e) {
                Log.w("ShizukuApplication", Log.getStackTraceString(e));
            }

        }
    }

    private static void attachApplication(IBinder binder, String packageName) throws RemoteException {

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(IXadkernService.DESCRIPTOR);
            data.writeStrongBinder(SHIZUKU_APPLICATION.asBinder());
            data.writeString(packageName);
            binder.transact(14, data, reply, 0);
            reply.readException();
        } finally {
            reply.recycle();
            data.recycle();
        }

    }

    public static IShizukuService getShizukuService() {
        try {
            if (service == null) return null;
            return service.getShizukuService();
        } catch (RemoteException e) {
            Log.e(TAG, "getShizukuService", e);
            return null;
        }
    }


    public static void enableShizukuService(boolean enable) {
        try {
            requireService().enableShizukuService(enable);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    protected static boolean isFirstInit(boolean markAsFirstInit) {
        try {
            return requireService().isFirstInit(markAsFirstInit);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    @NonNull
    protected static IXadkernService requireService() {
        if (service == null) {
            throw new IllegalStateException("binder haven't been received");
        }
        return service;
    }

    public static boolean pingBinder() {
        return binder != null && binder.pingBinder();
    }

    @Nullable
    public static IBinder getBinder() {
        return binder;
    }

    public static XadkernFileService newFileService() {
        try {
            return new XadkernFileService(requireService().getFileService());
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    public static XadkernNewProcess newProcess(@NonNull String[] cmd, @Nullable Environment env, @Nullable String dir) {
        try {

            return new XadkernNewProcess(requireService().newProcess(cmd, env != null ? env.getEnv() : null, dir));
        } catch (RemoteException | NullPointerException e) {
//            Log.d(TAG, "Failed to execute command", e);
            throw new RuntimeException("Failed to execute command", e);
        }
    }    private static final IBinder.DeathRecipient DEATH_RECIPIENT = () -> {
        binderReady = false;
        onBinderReceived(null, null);
    };

    public static XadkernNewProcess newProcess(@NonNull String cmd) {
        return newProcess(new String[]{"sh", "-c", cmd});
    }

    public static XadkernNewProcess newProcess(@NonNull String[] cmd) {
        return newProcess(cmd, Xadkern.getEnvironment(TYPE_ENV), null);
    }

    public static void destroy() {
        if (binder != null) {
            try {
                requireService().exit();
            } catch (RemoteException ignored) {
            }
            binder = null;
            service = null;
            axeronInfo = null;
            scheduleBinderDeadListeners();
        }
    }

    public static XadkernInfo getXadkernInfo() {
        if (axeronInfo != null) return axeronInfo;
        try {
            axeronInfo = new XadkernInfo(requireService().getServerInfo());
        } catch (Exception e) {
            return new XadkernInfo();
        }
        return axeronInfo;

    }

    public static List<PackageInfo> getPackages(int flags) {
        try {
            return requireService().getPackages(flags).getList();
        } catch (RemoteException ignored) {
        }
        return new ArrayList<>();
    }

    public static List<PluginInfo> getPlugins() {
        try {
            return requireService().getPlugins().getList();
        } catch (RemoteException ignored) {
        }
        return new ArrayList<>();
    }

    public static PluginInfo getPluginById(String id) {
        try {
            return requireService().getPluginById(id);
        } catch (RemoteException e) {
            return null;
        }
    }

    public static void transactRemote(@NonNull Parcel data, @Nullable Parcel reply, int flags) {
        try {
            requireService().asBinder().transact(BINDER_TRANSACTION_transact, data, reply, flags);
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException("Xadkern", e);
        }
    }

    public static Environment getEnvironment() {
        return getEnvironment(TYPE_ENV);
    }

    public static Environment getEnvironment(int envType) {
        try {
            return requireService().getEnvironment(envType);
        } catch (Exception e) {
            return null;
        }
    }

    public static void setNewEnvironment(Environment env) {
        try {
            requireService().setNewEnvironment(env);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    private static RuntimeException rethrowAsRuntimeException(String message, RemoteException e) {
        return new RuntimeException(message, e);
    }

    private static RuntimeException rethrowAsRuntimeException(RemoteException e) {
        return new RuntimeException(e);
    }

    public static int checkRemotePermission(String permission) {
        if (getXadkernInfo().isRoot()) return PackageManager.PERMISSION_GRANTED;
        try {
            return requireService().checkPermission(permission);
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
    }

    public static boolean isUpdated() {
        return XadkernApiConstant.server.VERSION_CODE <= Xadkern.getXadkernInfo().getServerInfo().getVersionCode();
    }

    public interface OnBinderReceivedListener {
        void onBinderReceived();
    }

    public interface OnBinderDeadListener {
        void onBinderDead();
    }

    @RestrictTo(LIBRARY_GROUP_PREFIX)
    public static void attachUserService(@NonNull IBinder binder, @NonNull Bundle options) {
        try {
            requireService().attachUserService(binder, options);
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
    }

    private record ListenerHolder<T>(T listener, Handler handler) {

        private ListenerHolder(@NonNull T listener, @Nullable Handler handler) {
            this.listener = listener;
            this.handler = handler;
        }

    }

    @RestrictTo(LIBRARY_GROUP_PREFIX)
    public static void dispatchPermissionConfirmationResult(int requestUid, int requestPid, int requestCode, @NonNull Bundle data) {
        try {
            requireService().dispatchPermissionConfirmationResult(requestUid, requestPid, requestCode, data);
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
    }

    @RestrictTo(LIBRARY_GROUP_PREFIX)
    public static int getFlagsForUid(int uid, int mask) {
        try {
            return requireService().getFlagsForUid(uid, mask);
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
    }

    @RestrictTo(LIBRARY_GROUP_PREFIX)
    public static void updateFlagsForUid(int uid, int mask, int value) {
        try {
            requireService().updateFlagsForUid(uid, mask, value);
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
    }

    public interface OnRequestPermissionResultListener {
        void onRequestPermissionResult(int requestCode, int grantResult);
    }



}

package com.mamoa.notifier;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.concurrent.TimeUnit;

/**
 * Worker que verifica periodicamente que el servicio de sincronizacion siga activo.
 * Usa WorkManager para sobrevivir a Doze mode y reinicios del sistema.
 */
public class ConnectionWatchdogWorker extends Worker {
    
    private static final String TAG = "MamoaWatchdog";
    private static final String WORK_NAME = "mamoa_connection_watchdog";
    private static final int CHECK_INTERVAL_MINUTES = 15;
    
    public ConnectionWatchdogWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }
    
    @NonNull
    @Override
    public Result doWork() {
        Log.d(TAG, "Watchdog ejecutandose");
        
        Context context = getApplicationContext();
        SharedPreferences prefs = context.getSharedPreferences("MamoaSettings", Context.MODE_PRIVATE);
        
        boolean serviceEnabled = prefs.getBoolean("service_enabled", false);
        
        if (!serviceEnabled) {
            Log.d(TAG, "Servicio deshabilitado por usuario, no reiniciar");
            return Result.success();
        }
        
        // Verificar si el servicio esta corriendo
        if (!isServiceRunning(context, NotificationSyncService.class)) {
            Log.w(TAG, "Servicio no esta corriendo, reiniciando...");
            startService(context);
            
            // Registrar el reinicio
            int restarts = prefs.getInt("watchdog_restarts", 0);
            prefs.edit()
                .putInt("watchdog_restarts", restarts + 1)
                .putLong("last_watchdog_restart", System.currentTimeMillis())
                .apply();
        } else {
            Log.d(TAG, "Servicio funcionando correctamente");
            
            // Verificar estado de conexion
            boolean lastConnected = prefs.getBoolean("last_is_connected", false);
            long lastActivity = prefs.getLong("last_notification_time", 0);
            long now = System.currentTimeMillis();
            
            // Si llevamos mas de 30 minutos sin conexion y el servicio cree que deberia estar conectado
            if (!lastConnected) {
                long disconnectedTime = now - prefs.getLong("last_disconnect_time", now);
                if (disconnectedTime > 30 * 60 * 1000) {
                    Log.w(TAG, "Desconectado por mas de 30 min, solicitando reconexion");
                    requestReconnect(context);
                }
            }
        }
        
        return Result.success();
    }
    
    private boolean isServiceRunning(Context context, Class<?> serviceClass) {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager == null) return false;
        
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.getName().equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }
    
    private void startService(Context context) {
        Intent serviceIntent = new Intent(context, NotificationSyncService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error iniciando servicio desde watchdog", e);
        }
    }
    
    private void requestReconnect(Context context) {
        Intent reconnectIntent = new Intent(context, NotificationSyncService.class);
        reconnectIntent.setAction(NotificationSyncService.ACTION_RECONNECT);
        try {
            context.startService(reconnectIntent);
        } catch (Exception e) {
            Log.e(TAG, "Error solicitando reconexion", e);
        }
    }
    
    // =====================================================================
    // API ESTATICA
    // =====================================================================
    
    /**
     * Programa el watchdog para ejecutarse periodicamente.
     * Debe llamarse cuando el servicio se inicia.
     */
    public static void schedule(Context context) {
        Log.d(TAG, "Programando watchdog cada " + CHECK_INTERVAL_MINUTES + " minutos");
        
        Constraints constraints = new Constraints.Builder()
                .setRequiresBatteryNotLow(false)
                .setRequiresCharging(false)
                .build();
        
        PeriodicWorkRequest watchdogWork = new PeriodicWorkRequest.Builder(
                ConnectionWatchdogWorker.class,
                CHECK_INTERVAL_MINUTES,
                TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setInitialDelay(5, TimeUnit.MINUTES)
                .build();
        
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                watchdogWork);
    }
    
    /**
     * Cancela el watchdog.
     * Debe llamarse cuando el usuario desactiva el servicio.
     */
    public static void cancel(Context context) {
        Log.d(TAG, "Cancelando watchdog");
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME);
    }
}

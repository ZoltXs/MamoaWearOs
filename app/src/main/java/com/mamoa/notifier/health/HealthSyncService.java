package com.mamoa.notifier.health;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Worker para sincronizar datos de salud con el servidor.
 * Se ejecuta cada 15 minutos cuando hay conexion de red.
 */
public class HealthSyncService extends Worker {
    private static final String TAG = "MamoaHealthSync";
    private static final String WORK_NAME = "mamoa_health_sync";
    private static final int SYNC_INTERVAL_MINUTES = 15;
    private static final int BATCH_SIZE = 100;
    
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    
    private final HealthDatabase database;
    private final OkHttpClient httpClient;
    private final Gson gson;
    private final SharedPreferences prefs;
    
    public HealthSyncService(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
        this.database = HealthDatabase.getInstance(context);
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
        this.gson = new GsonBuilder()
                .setDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                .create();
        this.prefs = context.getSharedPreferences("MamoaSettings", Context.MODE_PRIVATE);
    }
    
    @NonNull
    @Override
    public Result doWork() {
        Log.d(TAG, "Iniciando sincronizacion de datos de salud");
        
        String serverUrl = prefs.getString("health_sync_server_url", "");
        String deviceId = prefs.getString("device_id", "");
        String authToken = prefs.getString("health_sync_token", "");
        
        if (serverUrl.isEmpty()) {
            Log.d(TAG, "URL del servidor no configurada, saltando sync");
            return Result.success();
        }
        
        if (deviceId.isEmpty()) {
            deviceId = generateDeviceId();
            prefs.edit().putString("device_id", deviceId).apply();
        }
        
        try {
            int unsyncedCount = database.healthDataDao().getUnsyncedCount();
            Log.d(TAG, "Datos pendientes de sincronizar: " + unsyncedCount);
            
            if (unsyncedCount == 0) {
                return Result.success();
            }
            
            // Sincronizar en batches
            int totalSynced = 0;
            while (totalSynced < unsyncedCount) {
                List<HealthDataEntity> batch = database.healthDataDao().getUnsyncedData(BATCH_SIZE);
                if (batch.isEmpty()) break;
                
                boolean success = syncBatch(serverUrl, deviceId, authToken, batch);
                if (!success) {
                    Log.e(TAG, "Error sincronizando batch, reintentando despues");
                    return Result.retry();
                }
                
                totalSynced += batch.size();
            }
            
            Log.d(TAG, "Sincronizacion completada: " + totalSynced + " registros");
            
            // Limpiar datos viejos ya sincronizados (mas de 7 dias)
            long sevenDaysAgo = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L);
            database.healthDataDao().deleteOldSyncedData(sevenDaysAgo);
            
            return Result.success();
            
        } catch (Exception e) {
            Log.e(TAG, "Error en sincronizacion", e);
            return Result.retry();
        }
    }
    
    private boolean syncBatch(String serverUrl, String deviceId, String authToken, 
                              List<HealthDataEntity> batch) {
        try {
            // Crear payload
            SyncPayload payload = new SyncPayload();
            payload.deviceId = deviceId;
            payload.platform = "wearos";
            payload.appVersion = "3.0";
            payload.data = new ArrayList<>();
            
            for (HealthDataEntity entity : batch) {
                HealthDataPayload dataPayload = new HealthDataPayload();
                dataPayload.type = entity.type;
                dataPayload.value = entity.value;
                dataPayload.valueSecondary = entity.valueSecondary;
                dataPayload.unit = entity.unit;
                dataPayload.timestamp = entity.timestamp;
                dataPayload.timestampEnd = entity.timestampEnd;
                dataPayload.metadata = entity.metadata;
                dataPayload.source = entity.source;
                payload.data.add(dataPayload);
            }
            
            String jsonBody = gson.toJson(payload);
            
            Request.Builder requestBuilder = new Request.Builder()
                    .url(serverUrl + "/api/health/sync")
                    .post(RequestBody.create(jsonBody, JSON));
            
            if (!authToken.isEmpty()) {
                requestBuilder.addHeader("Authorization", "Bearer " + authToken);
            }
            
            Request request = requestBuilder.build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    // Marcar como sincronizados
                    List<Long> ids = new ArrayList<>();
                    for (HealthDataEntity entity : batch) {
                        ids.add(entity.id);
                    }
                    database.healthDataDao().markAsSynced(ids, System.currentTimeMillis());
                    
                    Log.d(TAG, "Batch sincronizado exitosamente: " + batch.size() + " registros");
                    return true;
                } else {
                    Log.e(TAG, "Error del servidor: " + response.code() + " - " + response.message());
                    return false;
                }
            }
            
        } catch (IOException e) {
            Log.e(TAG, "Error de red sincronizando batch", e);
            return false;
        }
    }
    
    private String generateDeviceId() {
        return "wearos_" + System.currentTimeMillis() + "_" + 
               (int)(Math.random() * 10000);
    }
    
    // =====================================================================
    // CLASES PARA SERIALIZAR PAYLOAD
    // =====================================================================
    
    private static class SyncPayload {
        String deviceId;
        String platform;
        String appVersion;
        List<HealthDataPayload> data;
    }
    
    private static class HealthDataPayload {
        String type;
        double value;
        double valueSecondary;
        String unit;
        long timestamp;
        long timestampEnd;
        String metadata;
        String source;
    }
    
    // =====================================================================
    // API ESTATICA
    // =====================================================================
    
    /**
     * Programa la sincronizacion periodica de datos de salud.
     */
    public static void schedule(Context context) {
        Log.d(TAG, "Programando sync de salud cada " + SYNC_INTERVAL_MINUTES + " minutos");
        
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build();
        
        PeriodicWorkRequest syncWork = new PeriodicWorkRequest.Builder(
                HealthSyncService.class,
                SYNC_INTERVAL_MINUTES,
                TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setInitialDelay(1, TimeUnit.MINUTES)
                .build();
        
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                syncWork);
    }
    
    /**
     * Cancela la sincronizacion periodica.
     */
    public static void cancel(Context context) {
        Log.d(TAG, "Cancelando sync de salud");
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME);
    }
    
    /**
     * Fuerza una sincronizacion inmediata.
     */
    public static void syncNow(Context context) {
        Log.d(TAG, "Forzando sync inmediata");
        androidx.work.OneTimeWorkRequest syncWork = new androidx.work.OneTimeWorkRequest.Builder(
                HealthSyncService.class)
                .setConstraints(new Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build())
                .build();
        
        WorkManager.getInstance(context).enqueue(syncWork);
    }
}

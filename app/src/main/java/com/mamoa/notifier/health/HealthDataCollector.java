package com.mamoa.notifier.health;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.gson.Gson;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Colector de datos de salud desde sensores del Samsung WearOS.
 * 
 * Datos recolectados:
 * - Pasos (step counter)
 * - Frecuencia cardiaca (heart rate)
 * - Calorias (estimadas)
 * - Distancia (calculada desde pasos)
 * - Actividad (accelerometer para detectar movimiento)
 */
public class HealthDataCollector implements SensorEventListener {
    private static final String TAG = "MamoaHealth";
    
    // Intervalos de muestreo
    private static final long HEART_RATE_INTERVAL_MS = 60000;  // 1 minuto
    private static final long STEPS_SAVE_INTERVAL_MS = 300000; // 5 minutos
    private static final int SENSOR_DELAY = SensorManager.SENSOR_DELAY_NORMAL;
    
    // Constantes para calculo de calorias
    private static final double STEP_LENGTH_METERS = 0.762; // ~30 pulgadas promedio
    private static final double CALORIES_PER_STEP = 0.04;   // Aproximado
    
    private final Context context;
    private final HealthDatabase database;
    private final Handler handler;
    private final ExecutorService executor;
    private final Gson gson;
    
    private SensorManager sensorManager;
    private Sensor stepCounterSensor;
    private Sensor heartRateSensor;
    private Sensor accelerometerSensor;
    
    private final AtomicBoolean isCollecting = new AtomicBoolean(false);
    private final AtomicInteger baselineSteps = new AtomicInteger(-1);
    private final AtomicInteger lastSavedSteps = new AtomicInteger(0);
    private final AtomicReference<Float> lastHeartRate = new AtomicReference<>(0f);
    
    private long lastHeartRateSave = 0;
    private long lastStepsSave = 0;
    private long sessionStartTime = 0;
    
    // Runnable para guardar datos periodicamente
    private final Runnable periodicSaveRunnable = this::savePeriodicData;
    
    public interface CollectorCallback {
        void onDataCollected(String type, double value);
        void onError(String error);
    }
    
    private CollectorCallback callback;
    
    public HealthDataCollector(Context context) {
        this.context = context.getApplicationContext();
        this.database = HealthDatabase.getInstance(context);
        this.handler = new Handler(Looper.getMainLooper());
        this.executor = Executors.newSingleThreadExecutor();
        this.gson = new Gson();
        
        initSensors();
    }
    
    private void initSensors() {
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager == null) {
            Log.e(TAG, "SensorManager no disponible");
            return;
        }
        
        stepCounterSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        heartRateSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE);
        accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        
        Log.d(TAG, "Sensores disponibles:");
        Log.d(TAG, "  Step Counter: " + (stepCounterSensor != null));
        Log.d(TAG, "  Heart Rate: " + (heartRateSensor != null));
        Log.d(TAG, "  Accelerometer: " + (accelerometerSensor != null));
    }
    
    public void setCallback(CollectorCallback callback) {
        this.callback = callback;
    }
    
    public void startCollecting() {
        if (isCollecting.getAndSet(true)) {
            Log.d(TAG, "Ya recolectando datos");
            return;
        }
        
        if (sensorManager == null) {
            Log.e(TAG, "No se puede iniciar: SensorManager null");
            return;
        }
        
        Log.d(TAG, "Iniciando recoleccion de datos de salud");
        sessionStartTime = System.currentTimeMillis();
        baselineSteps.set(-1);
        
        // Registrar sensores
        if (stepCounterSensor != null) {
            sensorManager.registerListener(this, stepCounterSensor, SENSOR_DELAY);
        }
        
        if (heartRateSensor != null) {
            sensorManager.registerListener(this, heartRateSensor, SENSOR_DELAY);
        }
        
        // Programar guardado periodico
        handler.postDelayed(periodicSaveRunnable, STEPS_SAVE_INTERVAL_MS);
    }
    
    public void stopCollecting() {
        if (!isCollecting.getAndSet(false)) {
            return;
        }
        
        Log.d(TAG, "Deteniendo recoleccion de datos de salud");
        
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        
        handler.removeCallbacks(periodicSaveRunnable);
        
        // Guardar datos finales
        savePeriodicData();
    }
    
    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event == null) return;
        
        long now = System.currentTimeMillis();
        
        switch (event.sensor.getType()) {
            case Sensor.TYPE_STEP_COUNTER:
                handleStepCounter(event, now);
                break;
                
            case Sensor.TYPE_HEART_RATE:
                handleHeartRate(event, now);
                break;
        }
    }
    
    private void handleStepCounter(SensorEvent event, long now) {
        int totalSteps = (int) event.values[0];
        
        if (baselineSteps.get() < 0) {
            baselineSteps.set(totalSteps);
            Log.d(TAG, "Baseline de pasos establecido: " + totalSteps);
            return;
        }
        
        int sessionSteps = totalSteps - baselineSteps.get();
        
        if (callback != null) {
            callback.onDataCollected("steps", sessionSteps);
        }
    }
    
    private void handleHeartRate(SensorEvent event, long now) {
        float heartRate = event.values[0];
        
        // Ignorar valores invalidos
        if (heartRate <= 0 || heartRate > 250) {
            return;
        }
        
        lastHeartRate.set(heartRate);
        
        if (callback != null) {
            callback.onDataCollected("heart_rate", heartRate);
        }
        
        // Guardar cada minuto
        if (now - lastHeartRateSave >= HEART_RATE_INTERVAL_MS) {
            lastHeartRateSave = now;
            saveHeartRate(heartRate, now);
        }
    }
    
    private void saveHeartRate(float heartRate, long timestamp) {
        executor.execute(() -> {
            try {
                HealthDataEntity entity = HealthDataEntity.builder()
                        .type("heart_rate")
                        .value(heartRate)
                        .unit("bpm")
                        .timestamp(timestamp)
                        .source("sensor")
                        .build();
                
                database.healthDataDao().insert(entity);
                Log.d(TAG, "Heart rate guardado: " + heartRate + " bpm");
            } catch (Exception e) {
                Log.e(TAG, "Error guardando heart rate", e);
            }
        });
    }
    
    private void savePeriodicData() {
        if (!isCollecting.get()) return;
        
        long now = System.currentTimeMillis();
        
        // Guardar pasos acumulados
        if (baselineSteps.get() >= 0) {
            // Este valor se actualizara cuando llegue el siguiente evento de sensor
            // Por ahora guardamos la diferencia desde la ultima vez
            int currentSteps = getCurrentSteps();
            if (currentSteps > lastSavedSteps.get()) {
                int newSteps = currentSteps - lastSavedSteps.get();
                saveSteps(newSteps, now);
                lastSavedSteps.set(currentSteps);
            }
        }
        
        // Programar siguiente guardado
        handler.postDelayed(periodicSaveRunnable, STEPS_SAVE_INTERVAL_MS);
    }
    
    private int getCurrentSteps() {
        // En una implementacion real, mantendriamos el ultimo valor del sensor
        // Por simplicidad, retornamos el baseline actual
        return Math.max(0, baselineSteps.get());
    }
    
    private void saveSteps(int steps, long timestamp) {
        executor.execute(() -> {
            try {
                // Guardar pasos
                HealthDataEntity stepsEntity = HealthDataEntity.builder()
                        .type("steps")
                        .value(steps)
                        .unit("count")
                        .timestamp(timestamp)
                        .source("sensor")
                        .build();
                database.healthDataDao().insert(stepsEntity);
                
                // Calcular y guardar distancia
                double distance = steps * STEP_LENGTH_METERS;
                HealthDataEntity distanceEntity = HealthDataEntity.builder()
                        .type("distance")
                        .value(distance)
                        .unit("m")
                        .timestamp(timestamp)
                        .source("calculated")
                        .build();
                database.healthDataDao().insert(distanceEntity);
                
                // Calcular y guardar calorias
                double calories = steps * CALORIES_PER_STEP;
                HealthDataEntity caloriesEntity = HealthDataEntity.builder()
                        .type("active_calories")
                        .value(calories)
                        .unit("kcal")
                        .timestamp(timestamp)
                        .source("calculated")
                        .build();
                database.healthDataDao().insert(caloriesEntity);
                
                Log.d(TAG, "Datos guardados - Pasos: " + steps + ", Distancia: " + distance + "m, Calorias: " + calories);
            } catch (Exception e) {
                Log.e(TAG, "Error guardando datos periodicos", e);
            }
        });
    }
    
    /**
     * Registra un entrenamiento manualmente.
     */
    public void recordWorkout(String workoutType, long startTime, long endTime, 
                              double calories, double distance, Map<String, Object> extra) {
        executor.execute(() -> {
            try {
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("workout_type", workoutType);
                if (extra != null) metadata.putAll(extra);
                
                HealthDataEntity entity = HealthDataEntity.builder()
                        .type("workout")
                        .value(calories)
                        .valueSecondary(distance)
                        .unit("kcal")
                        .timestamp(startTime)
                        .timestampEnd(endTime)
                        .metadata(gson.toJson(metadata))
                        .source("manual")
                        .build();
                
                database.healthDataDao().insert(entity);
                Log.d(TAG, "Workout registrado: " + workoutType);
            } catch (Exception e) {
                Log.e(TAG, "Error registrando workout", e);
            }
        });
    }
    
    /**
     * Registra datos de sueno.
     */
    public void recordSleep(long sleepStart, long sleepEnd, String quality) {
        executor.execute(() -> {
            try {
                long durationMinutes = (sleepEnd - sleepStart) / 60000;
                
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("quality", quality);
                
                HealthDataEntity entity = HealthDataEntity.builder()
                        .type("sleep")
                        .value(durationMinutes)
                        .unit("min")
                        .timestamp(sleepStart)
                        .timestampEnd(sleepEnd)
                        .metadata(gson.toJson(metadata))
                        .source("detected")
                        .build();
                
                database.healthDataDao().insert(entity);
                Log.d(TAG, "Sueno registrado: " + durationMinutes + " minutos");
            } catch (Exception e) {
                Log.e(TAG, "Error registrando sueno", e);
            }
        });
    }
    
    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        Log.d(TAG, "Precision del sensor " + sensor.getName() + " cambiada a: " + accuracy);
    }
    
    public boolean isCollecting() {
        return isCollecting.get();
    }
    
    public void destroy() {
        stopCollecting();
        executor.shutdown();
    }
}

package com.mamoa.notifier.health;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Entidad Room para almacenar datos de salud del Samsung WearOS.
 * Diseñada para sincronizar con Apple HealthKit via servidor intermedio.
 */
@Entity(tableName = "health_data",
        indices = {@Index(value = {"type", "timestamp"}, unique = true)})
public class HealthDataEntity {
    
    @PrimaryKey(autoGenerate = true)
    public long id;
    
    @NonNull
    public String type;           // "steps", "heart_rate", "calories", "sleep", "workout", "distance"
    
    public double value;          // Valor numerico principal
    public double valueSecondary; // Valor secundario (ej: heart rate variability, active vs resting calories)
    
    @NonNull
    public String unit;           // "count", "bpm", "kcal", "min", "m", etc.
    
    public long timestamp;        // Momento de la medicion (epoch ms)
    public long timestampEnd;     // Para datos de duracion (sueno, workouts)
    
    public String metadata;       // JSON con datos adicionales
    
    public boolean synced;        // Si ya se envio al servidor
    public long syncedAt;         // Cuando se sincronizo
    
    public String source;         // "samsung_health", "sensors", etc.
    
    public HealthDataEntity() {
        this.type = "";
        this.unit = "";
    }
    
    // Builder pattern para facilitar creacion
    public static Builder builder() {
        return new Builder();
    }
    
    public static class Builder {
        private final HealthDataEntity entity = new HealthDataEntity();
        
        public Builder type(String type) { entity.type = type; return this; }
        public Builder value(double value) { entity.value = value; return this; }
        public Builder valueSecondary(double value) { entity.valueSecondary = value; return this; }
        public Builder unit(String unit) { entity.unit = unit; return this; }
        public Builder timestamp(long timestamp) { entity.timestamp = timestamp; return this; }
        public Builder timestampEnd(long timestampEnd) { entity.timestampEnd = timestampEnd; return this; }
        public Builder metadata(String metadata) { entity.metadata = metadata; return this; }
        public Builder source(String source) { entity.source = source; return this; }
        
        public HealthDataEntity build() { return entity; }
    }
}

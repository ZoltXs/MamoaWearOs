package com.mamoa.notifier.health;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

/**
 * Base de datos Room para datos de salud.
 */
@Database(entities = {HealthDataEntity.class}, version = 1, exportSchema = true)
public abstract class HealthDatabase extends RoomDatabase {
    
    private static volatile HealthDatabase INSTANCE;
    private static final String DATABASE_NAME = "mamoa_health_db";
    
    public abstract HealthDataDao healthDataDao();
    
    public static HealthDatabase getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (HealthDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                            context.getApplicationContext(),
                            HealthDatabase.class,
                            DATABASE_NAME)
                            .fallbackToDestructiveMigration()
                            .build();
                }
            }
        }
        return INSTANCE;
    }
}

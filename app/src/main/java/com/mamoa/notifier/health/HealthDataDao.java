package com.mamoa.notifier.health;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

/**
 * DAO para acceso a datos de salud.
 */
@Dao
public interface HealthDataDao {
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insert(HealthDataEntity data);
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<HealthDataEntity> dataList);
    
    @Update
    void update(HealthDataEntity data);
    
    @Query("SELECT * FROM health_data WHERE synced = 0 ORDER BY timestamp ASC LIMIT :limit")
    List<HealthDataEntity> getUnsyncedData(int limit);
    
    @Query("SELECT COUNT(*) FROM health_data WHERE synced = 0")
    int getUnsyncedCount();
    
    @Query("UPDATE health_data SET synced = 1, syncedAt = :syncedAt WHERE id IN (:ids)")
    void markAsSynced(List<Long> ids, long syncedAt);
    
    @Query("SELECT * FROM health_data WHERE type = :type ORDER BY timestamp DESC LIMIT 1")
    HealthDataEntity getLatest(String type);
    
    @Query("SELECT * FROM health_data WHERE type = :type AND timestamp >= :from AND timestamp <= :to ORDER BY timestamp ASC")
    List<HealthDataEntity> getByTypeAndTimeRange(String type, long from, long to);
    
    @Query("SELECT SUM(value) FROM health_data WHERE type = :type AND timestamp >= :from AND timestamp <= :to")
    double getSumByTypeAndTimeRange(String type, long from, long to);
    
    @Query("SELECT AVG(value) FROM health_data WHERE type = :type AND timestamp >= :from AND timestamp <= :to")
    double getAverageByTypeAndTimeRange(String type, long from, long to);
    
    @Query("DELETE FROM health_data WHERE synced = 1 AND syncedAt < :before")
    void deleteOldSyncedData(long before);
    
    @Query("SELECT * FROM health_data WHERE type = 'workout' ORDER BY timestamp DESC LIMIT :limit")
    List<HealthDataEntity> getRecentWorkouts(int limit);
    
    @Query("SELECT * FROM health_data WHERE type = 'sleep' ORDER BY timestamp DESC LIMIT :limit")
    List<HealthDataEntity> getRecentSleep(int limit);
}

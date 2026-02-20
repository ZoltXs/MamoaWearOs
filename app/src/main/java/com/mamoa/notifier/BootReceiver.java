package com.mamoa.notifier;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    
    private static final String TAG = "MamoaBootReceiver";
    
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Log.d(TAG, "Dispositivo iniciado, verificando si debe iniciar servicio");
            
            SharedPreferences prefs = context.getSharedPreferences("MamoaSettings", 
                                                                  Context.MODE_PRIVATE);
            boolean serviceEnabled = prefs.getBoolean("service_enabled", false);
            
            if (serviceEnabled) {
                Log.d(TAG, "Iniciando servicio de sincronización");
                Intent serviceIntent = new Intent(context, NotificationSyncService.class);
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
            } else {
                Log.d(TAG, "Servicio no habilitado, no se inicia automáticamente");
            }
        }
    }
}

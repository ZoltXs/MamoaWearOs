package com.mamoa.notifier;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class NotificationActionReceiver extends BroadcastReceiver {
    
    private static final String TAG = "MamoaActionReceiver";
    public static final String ACTION_REPLY = "com.mamoa.notifier.REPLY_ACTION";
    public static final String ACTION_DISMISS = "com.mamoa.notifier.DISMISS_ACTION";
    public static final String EXTRA_NOTIFICATION_KEY = "notification_key";
    public static final String EXTRA_REPLY_TEXT = "reply_text";
    
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) {
            return;
        }
        
        String notificationKey = intent.getStringExtra(EXTRA_NOTIFICATION_KEY);
        if (notificationKey == null) {
            Log.w(TAG, "No se proporcionó clave de notificación");
            return;
        }
        
        NotificationListener listener = NotificationListener.getInstance();
        if (listener == null) {
            Log.w(TAG, "NotificationListener no está activo");
            return;
        }
        
        switch (action) {
            case ACTION_REPLY:
                String replyText = intent.getStringExtra(EXTRA_REPLY_TEXT);
                if (replyText != null && !replyText.isEmpty()) {
                    Log.d(TAG, "Respondiendo a notificación: " + replyText);
                    listener.replyToNotification(notificationKey, replyText);
                } else {
                    Log.w(TAG, "Texto de respuesta vacío");
                }
                break;
                
            case ACTION_DISMISS:
                Log.d(TAG, "Descartando notificación");
                listener.dismissNotification(notificationKey);
                break;
                
            default:
                Log.w(TAG, "Acción desconocida: " + action);
        }
    }
}

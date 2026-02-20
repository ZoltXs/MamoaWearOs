package com.mamoa.notifier;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Intent;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import java.util.HashMap;
import java.util.Map;

public class NotificationListener extends NotificationListenerService {
    
    private static final String TAG = "MamoaNotifListener";
    private static NotificationListener instance;
    private Map<String, StatusBarNotification> activeNotifications = new HashMap<>();
    
    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        Log.d(TAG, "NotificationListener creado");
    }
    
    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        String packageName = sbn.getPackageName();
        Notification notification = sbn.getNotification();
        
        // Filtrar notificaciones propias
        if (packageName.equals(getPackageName())) {
            return;
        }
        
        // Extraer información de la notificación
        Bundle extras = notification.extras;
        String title = extras.getString(Notification.EXTRA_TITLE, "");
        String text = extras.getCharSequence(Notification.EXTRA_TEXT, "").toString();
        
        Log.d(TAG, "Nueva notificación de " + packageName + ": " + title);
        
        // Guardar referencia a la notificación para poder responder después
        String key = sbn.getKey();
        activeNotifications.put(key, sbn);
        
        // Verificar si la notificación tiene acciones de respuesta
        boolean hasReplyAction = checkForReplyAction(notification);
        
        // Enviar notificación al watch con información de si se puede responder
        NotificationData data = new NotificationData(
            key,
            title,
            text,
            packageName,
            hasReplyAction,
            System.currentTimeMillis()
        );
        
        sendToWatch(data);
    }
    
    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        String key = sbn.getKey();
        activeNotifications.remove(key);
        Log.d(TAG, "Notificación eliminada: " + key);
    }
    
    private boolean checkForReplyAction(Notification notification) {
        if (notification.actions == null) {
            return false;
        }
        
        for (Notification.Action action : notification.actions) {
            RemoteInput[] remoteInputs = action.getRemoteInputs();
            if (remoteInputs != null && remoteInputs.length > 0) {
                return true;
            }
        }
        return false;
    }
    
    public void replyToNotification(String notificationKey, String replyText) {
        StatusBarNotification sbn = activeNotifications.get(notificationKey);
        if (sbn == null) {
            Log.w(TAG, "Notificación no encontrada: " + notificationKey);
            return;
        }
        
        Notification notification = sbn.getNotification();
        if (notification.actions == null) {
            Log.w(TAG, "La notificación no tiene acciones");
            return;
        }
        
        // Buscar la acción de respuesta
        for (Notification.Action action : notification.actions) {
            RemoteInput[] remoteInputs = action.getRemoteInputs();
            if (remoteInputs != null && remoteInputs.length > 0) {
                // Encontramos la acción de respuesta
                try {
                    Intent intent = new Intent();
                    Bundle bundle = new Bundle();
                    
                    // Añadir el texto de respuesta
                    for (RemoteInput remoteInput : remoteInputs) {
                        bundle.putCharSequence(remoteInput.getResultKey(), replyText);
                    }
                    
                    RemoteInput.addResultsToIntent(remoteInputs, intent, bundle);
                    
                    // Enviar la respuesta
                    action.actionIntent.send(this, 0, intent);
                    
                    Log.d(TAG, "Respuesta enviada: " + replyText);
                    
                    // Notificar al usuario en el watch que la respuesta se envió
                    sendReplyConfirmationToWatch(notificationKey);
                    
                    return;
                    
                } catch (PendingIntent.CanceledException e) {
                    Log.e(TAG, "Error al enviar respuesta", e);
                }
            }
        }
        
        Log.w(TAG, "No se encontró acción de respuesta en la notificación");
    }
    
    public void dismissNotification(String notificationKey) {
        StatusBarNotification sbn = activeNotifications.get(notificationKey);
        if (sbn != null) {
            try {
                cancelNotification(sbn.getKey());
                activeNotifications.remove(notificationKey);
                Log.d(TAG, "Notificación descartada: " + notificationKey);
            } catch (Exception e) {
                Log.e(TAG, "Error al descartar notificación", e);
            }
        }
    }
    
    private void sendToWatch(NotificationData data) {
        // Obtener referencia al servicio de sincronización
        Intent intent = new Intent(this, NotificationSyncService.class);
        intent.putExtra("notification_data", data);
        startService(intent);
    }
    
    private void sendReplyConfirmationToWatch(String notificationKey) {
        // Enviar confirmación al watch de que la respuesta se envió
        Intent intent = new Intent(this, NotificationSyncService.class);
        intent.putExtra("reply_sent", notificationKey);
        startService(intent);
    }
    
    public static NotificationListener getInstance() {
        return instance;
    }
    
    // Clase interna para datos de notificación
    public static class NotificationData implements java.io.Serializable {
        public String key;
        public String title;
        public String text;
        public String packageName;
        public boolean canReply;
        public long timestamp;
        
        public NotificationData(String key, String title, String text, 
                               String packageName, boolean canReply, long timestamp) {
            this.key = key;
            this.title = title;
            this.text = text;
            this.packageName = packageName;
            this.canReply = canReply;
            this.timestamp = timestamp;
        }
    }
}

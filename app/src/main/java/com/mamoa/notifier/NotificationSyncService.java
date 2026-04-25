package com.mamoa.notifier;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadata;
import android.media.VolumeProvider;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataClient;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.Wearable;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Servicio principal de sincronizacion de notificaciones ANCS.
 * Usa BluetoothConnectionManager para conexion BLE robusta.
 */
public class NotificationSyncService extends Service implements BluetoothConnectionManager.ConnectionCallback {
    private static final String TAG = "MamoaANCS";
    private static final String CHANNEL_ID = "mamoa_service_channel";
    private static final String CHANNEL_GROUP_ID = "mamoa_iphone_apps";
    private static final int NOTIFICATION_ID = 1001;

    public static final String ACTION_MUSIC_UPDATE = "com.mamoa.notifier.MUSIC_UPDATE";
    public static final String ACTION_MUSIC_COMMAND = "com.mamoa.notifier.MUSIC_COMMAND";
    public static final String ACTION_RECONNECT = "com.mamoa.notifier.RECONNECT";
    public static final String ACTION_CONNECTION_STATE = "com.mamoa.notifier.CONNECTION_STATE";
    public static final String EXTRA_MUSIC_COMMAND_ID = "command_id";
    public static final String EXTRA_IS_CONNECTED = "is_connected";
    public static final String EXTRA_STATUS_MESSAGE = "status_message";

    private BluetoothConnectionManager connectionManager;
    private SharedPreferences prefs;
    private AppIconCache iconCache;
    private DataClient dataClient;
    private MediaSession mediaSession;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final AtomicInteger notificationCounter = new AtomicInteger(2000);

    private ByteArrayOutputStream dataSourceBuffer = new ByteArrayOutputStream();
    private final Map<Byte, String> currentTrackInfo = new HashMap<>();
    private String lastArtworkUrl = "";
    private final Map<Integer, Integer> uidCategoryMap = new HashMap<>();

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Servicio iniciando...");
        
        prefs = getSharedPreferences("MamoaSettings", MODE_PRIVATE);
        iconCache = AppIconCache.getInstance(this);
        dataClient = Wearable.getDataClient(this);
        
        // Inicializar gestor de conexion BLE
        connectionManager = new BluetoothConnectionManager(this, this);
        
        setupMediaSession();
        createNotificationChannels();
        
        // Iniciar como foreground service
        Notification serviceNotification = createServiceNotification("Servicio activo");
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, serviceNotification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIFICATION_ID, serviceNotification);
        }
        
        // Programar watchdog
        ConnectionWatchdogWorker.schedule(this);
        
        // Iniciar conexion
        connectionManager.connect();
        
        // Listener para comandos de musica desde Wear
        Wearable.getMessageClient(this).addListener(event -> {
            if ("/music_command".equals(event.getPath()) && event.getData().length > 0) {
                connectionManager.sendMusicCommand(event.getData()[0]);
            }
        });
    }

    private void setupMediaSession() {
        mediaSession = new MediaSession(this, "Mamoa ANCS");
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { connectionManager.sendMusicCommand((byte) 0); }
            @Override public void onPause() { connectionManager.sendMusicCommand((byte) 1); }
            @Override public void onSkipToNext() { connectionManager.sendMusicCommand((byte) 3); }
            @Override public void onSkipToPrevious() { connectionManager.sendMusicCommand((byte) 4); }
        });
        mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setPlaybackToRemote(new VolumeProvider(VolumeProvider.VOLUME_CONTROL_RELATIVE, 100, 50) {
            @Override public void onAdjustVolume(int direction) {
                if (direction > 0) connectionManager.sendMusicCommand((byte) 5);
                else if (direction < 0) connectionManager.sendMusicCommand((byte) 6);
            }
        });
        mediaSession.setActive(true);
        updatePlaybackState(0);
    }

    private void updatePlaybackState(int amsState) {
        int state = (amsState == 1) ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED;
        PlaybackState pbState = new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | 
                           PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                .build();
        mediaSession.setPlaybackState(pbState);
    }

    private void createNotificationChannels() {
        NotificationManager nm = (NotificationManager) getSystemService(NotificationManager.class);
        nm.createNotificationChannelGroup(new NotificationChannelGroup(CHANNEL_GROUP_ID, "Notificaciones iPhone"));
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "Mamoa Sync", NotificationManager.IMPORTANCE_LOW));
    }

    private Notification createServiceNotification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Mamoa Notifier")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_launcher)
                .setOngoing(true)
                .build();
    }

    // =====================================================================
    // CALLBACKS DE BluetoothConnectionManager
    // =====================================================================

    @Override
    public void onConnectionStateChanged(boolean connected, String message) {
        Log.d(TAG, "Estado conexion: " + (connected ? "Conectado" : "Desconectado") + " - " + message);
        
        // Guardar estado
        prefs.edit()
            .putBoolean("last_is_connected", connected)
            .putString("last_status_message", message)
            .putLong(connected ? "last_connect_time" : "last_disconnect_time", System.currentTimeMillis())
            .apply();
        
        // Actualizar notificacion del servicio
        NotificationManager nm = (NotificationManager) getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, createServiceNotification(message));
        }
        
        // Broadcast a la UI
        broadcastConnectionState(connected, message);
    }

    @Override
    public void onServicesReady() {
        Log.d(TAG, "Servicios ANCS/AMS listos");
    }

    @Override
    public void onNotificationSourceChanged(byte[] data) {
        handleNotificationSource(data);
    }

    @Override
    public void onDataSourceChanged(byte[] data) {
        handleDataSource(data);
    }

    @Override
    public void onEntityUpdateChanged(byte[] data) {
        handleEntityUpdate(data);
    }

    @Override
    public void onError(String error) {
        Log.e(TAG, "Error BLE: " + error);
        broadcastConnectionState(false, error);
    }

    // =====================================================================
    // PROCESAMIENTO ANCS
    // =====================================================================

    private void handleNotificationSource(byte[] data) {
        if (data == null || data.length < 8) return;
        
        int eventId = data[0] & 0xFF;
        int categoryId = data[2] & 0xFF;
        int uid = ByteBuffer.wrap(data, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        
        // EventID: 0 = Added, 1 = Modified, 2 = Removed
        if (eventId == 0 || eventId == 1) {
            uidCategoryMap.put(uid, categoryId);
            connectionManager.requestNotificationDetails(uid);
            
            // Registrar actividad
            prefs.edit().putLong("last_notification_time", System.currentTimeMillis()).apply();
        }
    }

    private void handleDataSource(byte[] data) {
        if (data == null || data.length == 0) return;
        dataSourceBuffer.write(data, 0, data.length);
        tryParseDataSourceBuffer();
    }

    private void tryParseDataSourceBuffer() {
        byte[] acc = dataSourceBuffer.toByteArray();
        if (acc.length < 5) return;
        
        int uid = ByteBuffer.wrap(acc, 1, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        int i = 5;
        String bundleId = null, title = null, message = null;
        
        while (i < acc.length) {
            try {
                if (i + 3 > acc.length) return; // Datos incompletos
                
                int attrId = acc[i] & 0xFF;
                int len = ((acc[i + 2] & 0xFF) << 8) | (acc[i + 1] & 0xFF);
                int end = i + 3 + len;
                
                if (end > acc.length) return; // Datos incompletos
                
                String val = new String(acc, i + 3, len, "UTF-8");
                
                switch (attrId) {
                    case 0: bundleId = val; break;
                    case 1: title = val; break;
                    case 3: message = val; break;
                }
                
                i = end;
            } catch (Exception e) {
                Log.e(TAG, "Error parseando datos", e);
                dataSourceBuffer.reset();
                return;
            }
        }
        
        // Mostrar notificacion
        int category = uidCategoryMap.getOrDefault(uid, 0);
        showAppNotification(bundleId, title, message, category);
        dataSourceBuffer.reset();
    }

    private void showAppNotification(String bundleId, String title, String message, int category) {
        // Registrar app conocida
        NotificationFilterActivity.registerKnownApp(prefs, bundleId, bundleId);
        
        // Verificar filtro
        if (NotificationFilterActivity.isAppBlocked(prefs, bundleId)) {
            Log.d(TAG, "Notificacion filtrada: " + bundleId);
            return;
        }
        
        String channelId = getOrCreateAppChannel(bundleId);
        String groupKey = "mamoa_group_" + (bundleId != null ? bundleId : "unknown");
        NotificationManager nm = (NotificationManager) getSystemService(NotificationManager.class);
        if (nm == null) return;
        
        int notificationId = notificationCounter.incrementAndGet();

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, channelId)
                .setContentTitle(title != null ? title : bundleId)
                .setContentText(message != null ? message : "")
                .setSmallIcon(R.drawable.ic_launcher)
                .setGroup(groupKey)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(message));
        
        // Intentar obtener icono de cache
        Bitmap cachedIcon = iconCache.getIconSync(bundleId);
        if (cachedIcon != null) {
            builder.setLargeIcon(cachedIcon);
        }
        
        nm.notify(notificationId, builder.build());
        sendNotificationToWear(notificationId, title, message, bundleId, groupKey, cachedIcon);

        // Si no hay icono en cache, buscarlo
        if (cachedIcon == null) {
            iconCache.getIcon(bundleId, icon -> {
                if (icon != null) {
                    builder.setLargeIcon(icon);
                    nm.notify(notificationId, builder.build());
                    sendNotificationToWear(notificationId, title, message, bundleId, groupKey, icon);
                }
            });
        }
    }

    private void sendNotificationToWear(int nid, String title, String message, String bundleId, String groupKey, Bitmap icon) {
        PutDataMapRequest request = PutDataMapRequest.create("/notification/" + nid);
        request.getDataMap().putString("title", title != null ? title : "");
        request.getDataMap().putString("message", message != null ? message : "");
        request.getDataMap().putString("bundle_id", bundleId != null ? bundleId : "");
        request.getDataMap().putString("group_key", groupKey);
        request.getDataMap().putLong("timestamp", System.currentTimeMillis());
        
        if (icon != null) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            icon.compress(Bitmap.CompressFormat.PNG, 100, bos);
            request.getDataMap().putAsset("icon", Asset.createFromBytes(bos.toByteArray()));
        }
        
        dataClient.putDataItem(request.asPutDataRequest());
    }

    private String getOrCreateAppChannel(String bundleId) {
        String channelId = "mamoa_app_" + (bundleId != null ? bundleId.replace('.', '_') : "unknown");
        NotificationManager nm = (NotificationManager) getSystemService(NotificationManager.class);
        
        if (nm != null && nm.getNotificationChannel(channelId) == null) {
            NotificationChannel channel = new NotificationChannel(
                    channelId, 
                    bundleId != null ? bundleId : "Unknown App", 
                    NotificationManager.IMPORTANCE_HIGH);
            channel.setGroup(CHANNEL_GROUP_ID);
            nm.createNotificationChannel(channel);
        }
        
        return channelId;
    }

    // =====================================================================
    // PROCESAMIENTO AMS (MUSICA)
    // =====================================================================

    private void handleEntityUpdate(byte[] data) {
        if (data == null || data.length < 3) return;
        
        if (data[0] == 2) { // Track info
            byte attributeId = data[1];
            String value = (data.length > 3) ? new String(data, 3, data.length - 3) : "";
            currentTrackInfo.put(attributeId, value);
            updateWearMusicMetadata();
            
            // Buscar artwork cuando cambia artista o titulo
            String artist = currentTrackInfo.get((byte) 0);
            String track = currentTrackInfo.get((byte) 2);
            if (artist != null && track != null && (attributeId == 0 || attributeId == 2)) {
                fetchArtwork(artist, track);
            }
        } else if (data[0] == 0 && data[1] == 1) { // Player state
            try {
                String[] parts = new String(data, 3, data.length - 3).split(",");
                if (parts.length > 0) {
                    updatePlaybackState(Integer.parseInt(parts[0]));
                }
            } catch (Exception ignored) {}
        }
    }

    private void updateWearMusicMetadata() {
        String artist = currentTrackInfo.getOrDefault((byte) 0, "Artista");
        String track = currentTrackInfo.getOrDefault((byte) 2, "Desconocido");
        
        MediaMetadata current = mediaSession.getController().getMetadata();
        MediaMetadata.Builder builder = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, track)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist);
        
        if (current != null && current.getBitmap(MediaMetadata.METADATA_KEY_ART) != null) {
            builder.putBitmap(MediaMetadata.METADATA_KEY_ART, current.getBitmap(MediaMetadata.METADATA_KEY_ART));
        }
        
        mediaSession.setMetadata(builder.build());
        
        // Enviar a Wear
        PutDataMapRequest request = PutDataMapRequest.create("/music_info");
        request.getDataMap().putString("title", track);
        request.getDataMap().putString("artist", artist);
        request.getDataMap().putLong("timestamp", System.currentTimeMillis());
        dataClient.putDataItem(request.asPutDataRequest());
        
        // Broadcast local
        Intent musicIntent = new Intent(ACTION_MUSIC_UPDATE);
        musicIntent.setPackage(getPackageName());
        musicIntent.putExtra("title", track);
        musicIntent.putExtra("artist", artist);
        sendBroadcast(musicIntent);
    }

    private void fetchArtwork(String artist, String track) {
        new Thread(() -> {
            try {
                String query = (artist + " " + track).replace(" ", "+");
                URL url = new URL("https://itunes.apple.com/search?term=" + query + "&entity=song&limit=1");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                InputStream is = conn.getInputStream();
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    bos.write(buffer, 0, bytesRead);
                }
                
                String artworkUrl = extractJsonStringValue(bos.toString(), "artworkUrl100");
                if (artworkUrl != null && !artworkUrl.equals(lastArtworkUrl)) {
                    lastArtworkUrl = artworkUrl;
                    Bitmap artwork = BitmapFactory.decodeStream(new URL(artworkUrl).openStream());
                    if (artwork != null) {
                        updateMediaSessionArtwork(artwork);
                        sendArtworkToWear(artwork);
                    }
                }
            } catch (Exception e) {
                Log.d(TAG, "Error obteniendo artwork: " + e.getMessage());
            }
        }).start();
    }

    private void updateMediaSessionArtwork(Bitmap artwork) {
        handler.post(() -> {
            MediaMetadata current = mediaSession.getController().getMetadata();
            MediaMetadata.Builder builder = current != null 
                    ? new MediaMetadata.Builder(current) 
                    : new MediaMetadata.Builder();
            builder.putBitmap(MediaMetadata.METADATA_KEY_ART, artwork);
            mediaSession.setMetadata(builder.build());
        });
    }

    private String extractJsonStringValue(String json, String key) {
        String searchKey = "\"" + key + "\":\"";
        int start = json.indexOf(searchKey);
        if (start == -1) return null;
        start += searchKey.length();
        int end = json.indexOf("\"", start);
        return (end != -1) ? json.substring(start, end) : null;
    }

    private void sendArtworkToWear(Bitmap artwork) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        artwork.compress(Bitmap.CompressFormat.PNG, 100, bos);
        
        PutDataMapRequest request = PutDataMapRequest.create("/music_artwork");
        request.getDataMap().putAsset("artwork", Asset.createFromBytes(bos.toByteArray()));
        request.getDataMap().putLong("timestamp", System.currentTimeMillis());
        dataClient.putDataItem(request.asPutDataRequest());
    }

    // =====================================================================
    // LIFECYCLE
    // =====================================================================

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            
            if (ACTION_RECONNECT.equals(action)) {
                Log.d(TAG, "Reconexion solicitada");
                connectionManager.reconnect();
                
            } else if (ACTION_MUSIC_COMMAND.equals(action)) {
                byte commandId = intent.getByteExtra(EXTRA_MUSIC_COMMAND_ID, (byte) 2);
                connectionManager.sendMusicCommand(commandId);
            }
        }
        
        return START_STICKY;
    }

    private void broadcastConnectionState(boolean connected, String message) {
        Intent intent = new Intent(ACTION_CONNECTION_STATE);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_IS_CONNECTED, connected);
        intent.putExtra(EXTRA_STATUS_MESSAGE, message);
        sendBroadcast(intent);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "Servicio destruyendose");
        
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
        }
        
        if (connectionManager != null) {
            connectionManager.disconnect();
        }
        
        handler.removeCallbacksAndMessages(null);
        
        prefs.edit()
            .putBoolean("last_is_connected", false)
            .putString("last_status_message", "Servicio detenido")
            .apply();
    }
}

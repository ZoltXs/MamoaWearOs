package com.mamoa.notifier;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
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
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public class NotificationSyncService extends Service {
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

    private static final UUID ANCS_SERVICE_UUID = UUID.fromString("7905F431-B5CE-4E99-A40F-4B1E122D00D0");
    private static final UUID NOTIFICATION_SOURCE_UUID = UUID.fromString("9FBF120D-6301-42D9-8C58-25E699A21DBD");
    private static final UUID CONTROL_POINT_UUID = UUID.fromString("69D1D8F3-45E1-49A8-9821-9BBDFDAAD9D9");
    private static final UUID DATA_SOURCE_UUID = UUID.fromString("22EAC6E9-24D6-4BB5-BE44-B36ACE7C7BFB");
    private static final UUID AMS_SERVICE_UUID = UUID.fromString("89D3502B-0F36-433A-8EF4-C502AD55F8DC");
    private static final UUID REMOTE_COMMAND_UUID = UUID.fromString("9B3C81D8-57B1-4A8A-B8DF-0E56F7CA51C2");
    private static final UUID ENTITY_UPDATE_UUID = UUID.fromString("2F7CABCE-808D-411F-9A0C-BB92BA96C102");
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothGatt bluetoothGatt;
    private SharedPreferences prefs;
    private AppIconCache iconCache;
    private DataClient dataClient;
    private MediaSession mediaSession;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean isConnected = false;
    private final AtomicInteger notificationCounter = new AtomicInteger(2000);

    private final Queue<Runnable> bleOperationQueue = new LinkedList<>();
    private boolean bleOperationInProgress = false;

    private ByteArrayOutputStream dataSourceBuffer = new ByteArrayOutputStream();
    private final Map<Byte, String> currentTrackInfo = new HashMap<>();
    private String lastArtworkUrl = "";
    private final Map<Integer, Integer> uidCategoryMap = new HashMap<>();

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                isConnected = true;
                broadcastConnectionState(false, "Conectado. Negociando enlace...");
                try {
                    handler.postDelayed(() -> {
                        try {
                            if (bluetoothGatt != null) bluetoothGatt.requestMtu(185);
                        } catch (SecurityException ignored) {}
                    }, 600);
                } catch (Exception e) {
                    Log.e(TAG, "Connection error", e);
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                isConnected = false;
                broadcastConnectionState(false, "Desconectado. Reintentando...");
                bleOperationInProgress = false;
                bleOperationQueue.clear();
                closeGatt();
                handler.postDelayed(() -> connectToIPhone(), 5000);
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            handler.postDelayed(() -> {
                try {
                    if (bluetoothGatt != null) bluetoothGatt.discoverServices();
                } catch (SecurityException ignored) {}
            }, 600);
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) return;
            broadcastConnectionState(false, "Configurando servicios ANCS...");
            setupAncs(gatt);
            setupAms(gatt);
            enqueueBleOperation(() -> broadcastConnectionState(true, "Enlace ANCS Activo"));
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            bleOperationInProgress = false;
            processNextBleOperation();
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            bleOperationInProgress = false;
            processNextBleOperation();
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            UUID uuid = characteristic.getUuid();
            byte[] data = characteristic.getValue();
            if (NOTIFICATION_SOURCE_UUID.equals(uuid)) handleNotificationSource(data);
            else if (DATA_SOURCE_UUID.equals(uuid)) handleDataSource(data);
            else if (ENTITY_UPDATE_UUID.equals(uuid)) handleEntityUpdate(data);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm != null) bluetoothAdapter = bm.getAdapter();
        
        prefs = getSharedPreferences("MamoaSettings", MODE_PRIVATE);
        iconCache = AppIconCache.getInstance(this);
        dataClient = Wearable.getDataClient(this);
        setupMediaSession();
        createNotificationChannels();
        
        Notification serviceNotification = createServiceNotification("Servicio activo");
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, serviceNotification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIFICATION_ID, serviceNotification);
        }
        
        connectToIPhone();
        Wearable.getMessageClient(this).addListener(event -> {
            if ("/music_command".equals(event.getPath())) sendMusicCommand(event.getData()[0]);
        });
    }

    private void setupMediaSession() {
        mediaSession = new MediaSession(this, "Mamoa ANCS");
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { sendMusicCommand((byte) 0); }
            @Override public void onPause() { sendMusicCommand((byte) 1); }
            @Override public void onSkipToNext() { sendMusicCommand((byte) 3); }
            @Override public void onSkipToPrevious() { sendMusicCommand((byte) 4); }
        });
        mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setPlaybackToRemote(new VolumeProvider(VolumeProvider.VOLUME_CONTROL_RELATIVE, 100, 50) {
            @Override public void onAdjustVolume(int direction) {
                if (direction > 0) sendMusicCommand((byte) 5);
                else if (direction < 0) sendMusicCommand((byte) 6);
            }
        });
        mediaSession.setActive(true);
        updatePlaybackState(0);
    }

    private void updatePlaybackState(int amsState) {
        int state = (amsState == 1) ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED;
        PlaybackState pbState = new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f).build();
        mediaSession.setPlaybackState(pbState);
    }

    private void createNotificationChannels() {
        NotificationManager nm = (NotificationManager) getSystemService(NotificationManager.class);
        nm.createNotificationChannelGroup(new NotificationChannelGroup(CHANNEL_GROUP_ID, "Notificaciones iPhone"));
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "Mamoa Sync", NotificationManager.IMPORTANCE_LOW));
    }

    private Notification createServiceNotification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Mamoa Notifier").setContentText(text)
                .setSmallIcon(R.drawable.ic_launcher).setOngoing(true).build();
    }

    private synchronized void enqueueBleOperation(Runnable operation) {
        bleOperationQueue.add(operation);
        if (!bleOperationInProgress) processNextBleOperation();
    }

    private synchronized void processNextBleOperation() {
        if (bleOperationInProgress || bleOperationQueue.isEmpty()) return;
        Runnable next = bleOperationQueue.poll();
        if (next != null) {
            bleOperationInProgress = true;
            handler.post(next);
            handler.postDelayed(() -> {
                if (bleOperationInProgress) {
                    bleOperationInProgress = false;
                    processNextBleOperation();
                }
            }, 2000);
        }
    }

    private void setupAncs(BluetoothGatt gatt) {
        BluetoothGattService service = gatt.getService(ANCS_SERVICE_UUID);
        if (service == null) return;
        BluetoothGattCharacteristic ds = service.getCharacteristic(DATA_SOURCE_UUID);
        if (ds != null) enqueueBleOperation(() -> subscribe(ds));
        BluetoothGattCharacteristic ns = service.getCharacteristic(NOTIFICATION_SOURCE_UUID);
        if (ns != null) enqueueBleOperation(() -> subscribe(ns));
    }

    private void setupAms(BluetoothGatt gatt) {
        BluetoothGattService service = gatt.getService(AMS_SERVICE_UUID);
        if (service == null) return;
        BluetoothGattCharacteristic rc = service.getCharacteristic(REMOTE_COMMAND_UUID);
        if (rc != null) enqueueBleOperation(() -> subscribe(rc));
        BluetoothGattCharacteristic eu = service.getCharacteristic(ENTITY_UPDATE_UUID);
        if (eu != null) {
            enqueueBleOperation(() -> subscribe(eu));
            enqueueBleOperation(() -> writeCharacteristic(eu, new byte[]{2, 0, 1, 2, 3}));
            enqueueBleOperation(() -> writeCharacteristic(eu, new byte[]{0, 1}));
        }
    }

    private void writeCharacteristic(BluetoothGattCharacteristic c, byte[] value) {
        try {
            c.setValue(value);
            if (bluetoothGatt == null || !bluetoothGatt.writeCharacteristic(c)) {
                bleOperationInProgress = false;
                processNextBleOperation();
            }
        } catch (SecurityException e) {
            bleOperationInProgress = false;
            processNextBleOperation();
        }
    }

    private void subscribe(BluetoothGattCharacteristic c) {
        if (c == null || bluetoothGatt == null) { bleOperationInProgress = false; processNextBleOperation(); return; }
        try {
            bluetoothGatt.setCharacteristicNotification(c, true);
            BluetoothGattDescriptor d = c.getDescriptor(CCCD_UUID);
            if (d != null) {
                d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                if (!bluetoothGatt.writeDescriptor(d)) {
                    bleOperationInProgress = false;
                    processNextBleOperation();
                }
            } else { bleOperationInProgress = false; processNextBleOperation(); }
        } catch (Exception e) { bleOperationInProgress = false; processNextBleOperation(); }
    }

    private void handleNotificationSource(byte[] data) {
        if (data == null || data.length < 8) return;
        int eventId = data[0] & 0xFF;
        int categoryId = data[2] & 0xFF;
        int uid = ByteBuffer.wrap(data, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (eventId == 0 || eventId == 1) {
            uidCategoryMap.put(uid, categoryId);
            requestDetails(uid);
        }
    }

    private void requestDetails(int uid) {
        if (bluetoothGatt == null) return;
        BluetoothGattService s = bluetoothGatt.getService(ANCS_SERVICE_UUID);
        if (s == null) return;
        BluetoothGattCharacteristic cp = s.getCharacteristic(CONTROL_POINT_UUID);
        if (cp == null) return;
        ByteBuffer b = ByteBuffer.allocate(14).order(ByteOrder.LITTLE_ENDIAN);
        b.put((byte) 0).putInt(uid).put((byte) 0).put((byte) 1).putShort((short) 64).put((byte) 3).putShort((short) 256);
        enqueueBleOperation(() -> writeCharacteristic(cp, b.array()));
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
                if (i + 3 > acc.length) return;
                int attrId = acc[i] & 0xFF;
                int len = ((acc[i + 2] & 0xFF) << 8) | (acc[i + 1] & 0xFF);
                int end = i + 3 + len;
                if (end > acc.length) return;
                String val = new String(acc, i + 3, len, "UTF-8");
                if (attrId == 0) bundleId = val;
                else if (attrId == 1) title = val;
                else if (attrId == 3) message = val;
                i = end;
            } catch (Exception e) { dataSourceBuffer.reset(); return; }
        }
        showAppNotification(bundleId, title, message, uidCategoryMap.remove(uid) != null ? 0 : 0);
        dataSourceBuffer.reset();
    }

    private void showAppNotification(String bundleId, String title, String message, int cat) {
        NotificationFilterActivity.registerKnownApp(prefs, bundleId, bundleId);
        if (NotificationFilterActivity.isAppBlocked(prefs, bundleId)) return;
        String cid = getOrCreateAppChannel(bundleId);
        String gkey = "mamoa_group_" + (bundleId != null ? bundleId : "unknown");
        NotificationManager nm = (NotificationManager) getSystemService(NotificationManager.class);
        if (nm == null) return;
        int nid = notificationCounter.incrementAndGet();

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, cid)
                .setContentTitle(title != null ? title : bundleId).setContentText(message != null ? message : "")
                .setSmallIcon(R.drawable.ic_launcher).setGroup(gkey).setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT).setStyle(new NotificationCompat.BigTextStyle().bigText(message));
        
        Bitmap cached = iconCache.getIconSync(bundleId);
        if (cached != null) b.setLargeIcon(cached);
        nm.notify(nid, b.build());
        sendNotificationToWear(nid, title, message, bundleId, gkey, cached);

        if (cached == null) {
            iconCache.getIcon(bundleId, icon -> {
                if (icon != null) {
                    b.setLargeIcon(icon);
                    nm.notify(nid, b.build());
                    sendNotificationToWear(nid, title, message, bundleId, gkey, icon);
                }
            });
        }
    }

    private void sendNotificationToWear(int nid, String t, String m, String bid, String gk, Bitmap ic) {
        PutDataMapRequest r = PutDataMapRequest.create("/notification/" + nid);
        r.getDataMap().putString("title", t); r.getDataMap().putString("message", m);
        r.getDataMap().putString("bundle_id", bid); r.getDataMap().putString("group_key", gk);
        if (ic != null) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ic.compress(Bitmap.CompressFormat.PNG, 100, bos);
            r.getDataMap().putAsset("icon", Asset.createFromBytes(bos.toByteArray()));
        }
        Wearable.getDataClient(this).putDataItem(r.asPutDataRequest());
    }

    private String getOrCreateAppChannel(String bid) {
        String cid = "mamoa_app_" + (bid != null ? bid.replace('.', '_') : "unknown");
        NotificationManager nm = (NotificationManager) getSystemService(NotificationManager.class);
        if (nm != null && nm.getNotificationChannel(cid) == null) {
            nm.createNotificationChannel(new NotificationChannel(cid, bid, NotificationManager.IMPORTANCE_HIGH));
        }
        return cid;
    }

    private void handleEntityUpdate(byte[] data) {
        if (data == null || data.length < 3) return;
        if (data[0] == 2) {
            currentTrackInfo.put(data[1], (data.length > 3) ? new String(data, 3, data.length - 3) : "");
            updateWearMusicMetadata();
            String a = currentTrackInfo.get((byte) 0), t = currentTrackInfo.get((byte) 2);
            if (a != null && t != null && (data[1] == 0 || data[1] == 2)) fetchArtwork(a, t);
        } else if (data[0] == 0 && data[1] == 1) {
            try {
                String[] p = new String(data, 3, data.length - 3).split(",");
                if (p.length > 0) updatePlaybackState(Integer.parseInt(p[0]));
            } catch (Exception ignored) {}
        }
    }

    private void updateWearMusicMetadata() {
        String a = currentTrackInfo.getOrDefault((byte) 0, "Artista"), t = currentTrackInfo.getOrDefault((byte) 2, "Desconocido");
        MediaMetadata current = mediaSession.getController().getMetadata();
        MediaMetadata.Builder b = new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, t).putString(MediaMetadata.METADATA_KEY_ARTIST, a);
        if (current != null && current.getBitmap(MediaMetadata.METADATA_KEY_ART) != null) b.putBitmap(MediaMetadata.METADATA_KEY_ART, current.getBitmap(MediaMetadata.METADATA_KEY_ART));
        mediaSession.setMetadata(b.build());
        PutDataMapRequest r = PutDataMapRequest.create("/music_info");
        r.getDataMap().putString("title", t); r.getDataMap().putString("artist", a); r.getDataMap().putLong("timestamp", System.currentTimeMillis());
        Wearable.getDataClient(this).putDataItem(r.asPutDataRequest());
    }

    private void fetchArtwork(String a, String t) {
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL("https://itunes.apple.com/search?term=" + (a + " " + t).replace(" ", "+") + "&entity=song&limit=1").openConnection();
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                InputStream is = c.getInputStream(); byte[] buf = new byte[4096]; int r;
                while ((r = is.read(buf)) != -1) bos.write(buf, 0, r);
                String url = extractJsonStringValue(bos.toString(), "artworkUrl100");
                if (url != null && !url.equals(lastArtworkUrl)) {
                    lastArtworkUrl = url; Bitmap b = BitmapFactory.decodeStream(new URL(url).openStream());
                    if (b != null) { updateMediaSessionArtwork(b); sendArtworkToWear(b); }
                }
            } catch (Exception ignored) {}
        }).start();
    }

    private void updateMediaSessionArtwork(Bitmap b) {
        MediaMetadata m = mediaSession.getController().getMetadata();
        MediaMetadata.Builder builder = m != null ? new MediaMetadata.Builder(m) : new MediaMetadata.Builder();
        builder.putBitmap(MediaMetadata.METADATA_KEY_ART, b); mediaSession.setMetadata(builder.build());
    }

    private String extractJsonStringValue(String j, String key) {
        String sk = "\"" + key + "\":\""; int s = j.indexOf(sk);
        if (s == -1) return null; s += sk.length(); int e = j.indexOf("\"", s);
        return (e != -1) ? j.substring(s, e) : null;
    }

    private void sendArtworkToWear(Bitmap b) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.PNG, 100, bos);
        PutDataMapRequest r = PutDataMapRequest.create("/music_artwork");
        r.getDataMap().putAsset("artwork", Asset.createFromBytes(bos.toByteArray()));
        r.getDataMap().putLong("timestamp", System.currentTimeMillis());
        Wearable.getDataClient(this).putDataItem(r.asPutDataRequest());
    }

    private void sendMusicCommand(byte cid) {
        if (bluetoothGatt == null) return;
        BluetoothGattService s = bluetoothGatt.getService(AMS_SERVICE_UUID);
        if (s == null) return;
        BluetoothGattCharacteristic rc = s.getCharacteristic(REMOTE_COMMAND_UUID);
        if (rc != null) enqueueBleOperation(() -> writeCharacteristic(rc, new byte[]{cid}));
    }

    private void connectToIPhone() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) return;
        try {
            for (BluetoothDevice d : bluetoothAdapter.getBondedDevices()) {
                String deviceName = null;
                try {
                    deviceName = d.getName();
                } catch (SecurityException ignored) {}
                
                if (deviceName != null && deviceName.toLowerCase().contains("iphone")) {
                    closeGatt();
                    try {
                        bluetoothGatt = d.connectGatt(this, true, gattCallback, BluetoothDevice.TRANSPORT_LE);
                    } catch (SecurityException ignored) {}
                    return;
                }
            }
        } catch (Exception ignored) {}
    }

    private void closeGatt() {
        if (bluetoothGatt != null) { 
            try { 
                bluetoothGatt.close(); 
            } catch (SecurityException ignored) {} 
            bluetoothGatt = null; 
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_RECONNECT.equals(intent.getAction())) {
            bleOperationInProgress = false; bleOperationQueue.clear(); closeGatt();
            handler.postDelayed(this::connectToIPhone, 500);
        }
        return START_STICKY;
    }

    private void broadcastConnectionState(boolean connected, String message) {
        if (prefs != null) {
            prefs.edit()
                .putBoolean("last_is_connected", connected)
                .putString("last_status_message", message)
                .apply();
        }
        Intent intent = new Intent(ACTION_CONNECTION_STATE);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_IS_CONNECTED, connected);
        intent.putExtra(EXTRA_STATUS_MESSAGE, message);
        sendBroadcast(intent);
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mediaSession != null) { mediaSession.setActive(false); mediaSession.release(); }
        handler.removeCallbacksAndMessages(null); closeGatt();
        if (prefs != null) {
            prefs.edit()
                .putBoolean("last_is_connected", false)
                .putString("last_status_message", "Servicio detenido")
                .apply();
        }
    }
}

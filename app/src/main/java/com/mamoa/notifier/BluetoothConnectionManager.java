package com.mamoa.notifier;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Gestor robusto de conexion Bluetooth LE para ANCS/AMS.
 * 
 * Caracteristicas:
 * - Backoff exponencial en reconexiones (1s, 2s, 4s, 8s, max 30s)
 * - Deteccion por UUID ANCS (no solo por nombre)
 * - Cola de operaciones BLE con reintentos
 * - Watchdog para detectar conexiones zombies
 * - Escaneo activo cuando no hay dispositivo emparejado
 */
public class BluetoothConnectionManager {
    private static final String TAG = "MamoaBLEManager";
    
    // ANCS/AMS UUIDs
    public static final UUID ANCS_SERVICE_UUID = UUID.fromString("7905F431-B5CE-4E99-A40F-4B1E122D00D0");
    public static final UUID NOTIFICATION_SOURCE_UUID = UUID.fromString("9FBF120D-6301-42D9-8C58-25E699A21DBD");
    public static final UUID CONTROL_POINT_UUID = UUID.fromString("69D1D8F3-45E1-49A8-9821-9BBDFDAAD9D9");
    public static final UUID DATA_SOURCE_UUID = UUID.fromString("22EAC6E9-24D6-4BB5-BE44-B36ACE7C7BFB");
    public static final UUID AMS_SERVICE_UUID = UUID.fromString("89D3502B-0F36-433A-8EF4-C502AD55F8DC");
    public static final UUID REMOTE_COMMAND_UUID = UUID.fromString("9B3C81D8-57B1-4A8A-B8DF-0E56F7CA51C2");
    public static final UUID ENTITY_UPDATE_UUID = UUID.fromString("2F7CABCE-808D-411F-9A0C-BB92BA96C102");
    public static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    
    // Backoff config
    private static final long INITIAL_BACKOFF_MS = 1000;
    private static final long MAX_BACKOFF_MS = 30000;
    private static final int MAX_OPERATION_RETRIES = 3;
    private static final long OPERATION_TIMEOUT_MS = 3000;
    private static final long WATCHDOG_INTERVAL_MS = 30000;
    private static final long CONNECTION_TIMEOUT_MS = 15000;
    
    private final Context context;
    private final Handler handler;
    private final ConnectionCallback callback;
    
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bleScanner;
    private BluetoothGatt bluetoothGatt;
    private BluetoothDevice targetDevice;
    
    private final AtomicBoolean isConnected = new AtomicBoolean(false);
    private final AtomicBoolean isConnecting = new AtomicBoolean(false);
    private final AtomicBoolean servicesReady = new AtomicBoolean(false);
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
    
    private long currentBackoff = INITIAL_BACKOFF_MS;
    private long lastDataReceived = 0;
    
    // Cola de operaciones BLE
    private final Queue<BleOperation> operationQueue = new LinkedList<>();
    private BleOperation currentOperation = null;
    private final AtomicBoolean operationInProgress = new AtomicBoolean(false);
    
    // Runnable para watchdog y timeouts
    private final Runnable watchdogRunnable = this::checkConnectionHealth;
    private final Runnable connectionTimeoutRunnable = this::onConnectionTimeout;
    private final Runnable operationTimeoutRunnable = this::onOperationTimeout;
    
    public interface ConnectionCallback {
        void onConnectionStateChanged(boolean connected, String message);
        void onServicesReady();
        void onNotificationSourceChanged(byte[] data);
        void onDataSourceChanged(byte[] data);
        void onEntityUpdateChanged(byte[] data);
        void onError(String error);
    }
    
    private static class BleOperation {
        enum Type { SUBSCRIBE, WRITE }
        final Type type;
        final BluetoothGattCharacteristic characteristic;
        final byte[] data;
        int retries = 0;
        
        BleOperation(Type type, BluetoothGattCharacteristic characteristic, byte[] data) {
            this.type = type;
            this.characteristic = characteristic;
            this.data = data;
        }
    }
    
    public BluetoothConnectionManager(Context context, ConnectionCallback callback) {
        this.context = context.getApplicationContext();
        this.callback = callback;
        this.handler = new Handler(Looper.getMainLooper());
        
        BluetoothManager bm = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm != null) {
            bluetoothAdapter = bm.getAdapter();
            if (bluetoothAdapter != null) {
                bleScanner = bluetoothAdapter.getBluetoothLeScanner();
            }
        }
    }
    
    // =====================================================================
    // CONEXION
    // =====================================================================
    
    public void connect() {
        if (isConnecting.get() || isConnected.get()) {
            Log.d(TAG, "Ya conectando o conectado, ignorando");
            return;
        }
        
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            callback.onError("Bluetooth no disponible");
            return;
        }
        
        isConnecting.set(true);
        callback.onConnectionStateChanged(false, "Buscando iPhone...");
        
        // Primero buscar en dispositivos emparejados
        BluetoothDevice pairedIPhone = findPairedIPhone();
        if (pairedIPhone != null) {
            targetDevice = pairedIPhone;
            connectToDevice(pairedIPhone);
        } else {
            // Si no hay emparejado, escanear por UUID ANCS
            startAncsScanning();
        }
    }
    
    private BluetoothDevice findPairedIPhone() {
        try {
            for (BluetoothDevice device : bluetoothAdapter.getBondedDevices()) {
                String name = device.getName();
                if (name != null && name.toLowerCase().contains("iphone")) {
                    Log.d(TAG, "iPhone emparejado encontrado: " + name);
                    return device;
                }
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Sin permisos para obtener dispositivos emparejados", e);
        }
        return null;
    }
    
    private void startAncsScanning() {
        if (bleScanner == null) {
            callback.onError("Escaner BLE no disponible");
            isConnecting.set(false);
            return;
        }
        
        callback.onConnectionStateChanged(false, "Escaneando dispositivos ANCS...");
        
        try {
            List<ScanFilter> filters = new ArrayList<>();
            filters.add(new ScanFilter.Builder()
                    .setServiceUuid(new ParcelUuid(ANCS_SERVICE_UUID))
                    .build());
            
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                    .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                    .build();
            
            bleScanner.startScan(filters, settings, scanCallback);
            
            // Timeout para escaneo
            handler.postDelayed(() -> {
                stopScanning();
                if (!isConnected.get() && targetDevice == null) {
                    callback.onError("No se encontro dispositivo ANCS");
                    scheduleReconnect();
                }
            }, 15000);
            
        } catch (SecurityException e) {
            Log.e(TAG, "Sin permisos para escanear", e);
            callback.onError("Sin permisos de Bluetooth");
            isConnecting.set(false);
        }
    }
    
    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            Log.d(TAG, "Dispositivo ANCS encontrado: " + device.getAddress());
            stopScanning();
            targetDevice = device;
            connectToDevice(device);
        }
        
        @Override
        public void onScanFailed(int errorCode) {
            Log.e(TAG, "Escaneo fallido: " + errorCode);
            callback.onError("Error de escaneo BLE: " + errorCode);
            isConnecting.set(false);
        }
    };
    
    private void stopScanning() {
        try {
            if (bleScanner != null) {
                bleScanner.stopScan(scanCallback);
            }
        } catch (SecurityException ignored) {}
    }
    
    private void connectToDevice(BluetoothDevice device) {
        callback.onConnectionStateChanged(false, "Conectando a " + (device.getName() != null ? device.getName() : device.getAddress()) + "...");
        
        // Timeout de conexion
        handler.postDelayed(connectionTimeoutRunnable, CONNECTION_TIMEOUT_MS);
        
        try {
            closeGatt();
            bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
        } catch (SecurityException e) {
            Log.e(TAG, "Sin permisos para conectar", e);
            callback.onError("Sin permisos de Bluetooth");
            isConnecting.set(false);
        }
    }
    
    private void onConnectionTimeout() {
        if (isConnecting.get() && !isConnected.get()) {
            Log.w(TAG, "Timeout de conexion");
            callback.onConnectionStateChanged(false, "Timeout de conexion");
            disconnect();
            scheduleReconnect();
        }
    }
    
    public void disconnect() {
        handler.removeCallbacks(watchdogRunnable);
        handler.removeCallbacks(connectionTimeoutRunnable);
        handler.removeCallbacks(operationTimeoutRunnable);
        stopScanning();
        closeGatt();
        
        isConnected.set(false);
        isConnecting.set(false);
        servicesReady.set(false);
        operationQueue.clear();
        currentOperation = null;
        operationInProgress.set(false);
    }
    
    public void reconnect() {
        disconnect();
        currentBackoff = INITIAL_BACKOFF_MS;
        reconnectAttempts.set(0);
        handler.postDelayed(this::connect, 500);
    }
    
    private void scheduleReconnect() {
        isConnecting.set(false);
        isConnected.set(false);
        
        int attempts = reconnectAttempts.incrementAndGet();
        Log.d(TAG, "Programando reconexion #" + attempts + " en " + currentBackoff + "ms");
        
        callback.onConnectionStateChanged(false, "Reconectando en " + (currentBackoff / 1000) + "s...");
        
        handler.postDelayed(() -> {
            connect();
        }, currentBackoff);
        
        // Backoff exponencial
        currentBackoff = Math.min(currentBackoff * 2, MAX_BACKOFF_MS);
    }
    
    private void closeGatt() {
        if (bluetoothGatt != null) {
            try {
                bluetoothGatt.disconnect();
                bluetoothGatt.close();
            } catch (SecurityException ignored) {}
            bluetoothGatt = null;
        }
    }
    
    // =====================================================================
    // GATT CALLBACK
    // =====================================================================
    
    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            handler.removeCallbacks(connectionTimeoutRunnable);
            
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "Conectado, solicitando MTU");
                isConnected.set(true);
                isConnecting.set(false);
                reconnectAttempts.set(0);
                currentBackoff = INITIAL_BACKOFF_MS;
                
                callback.onConnectionStateChanged(false, "Conectado. Negociando MTU...");
                
                handler.postDelayed(() -> {
                    try {
                        if (bluetoothGatt != null) {
                            bluetoothGatt.requestMtu(185);
                        }
                    } catch (SecurityException e) {
                        Log.e(TAG, "Error solicitando MTU", e);
                    }
                }, 500);
                
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "Desconectado, status: " + status);
                
                boolean wasConnected = isConnected.getAndSet(false);
                servicesReady.set(false);
                operationQueue.clear();
                currentOperation = null;
                operationInProgress.set(false);
                
                handler.removeCallbacks(watchdogRunnable);
                closeGatt();
                
                if (wasConnected || isConnecting.get()) {
                    callback.onConnectionStateChanged(false, "Desconectado. Reintentando...");
                    scheduleReconnect();
                }
            }
        }
        
        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            Log.d(TAG, "MTU cambiado a " + mtu + ", descubriendo servicios");
            callback.onConnectionStateChanged(false, "Descubriendo servicios ANCS...");
            
            handler.postDelayed(() -> {
                try {
                    if (bluetoothGatt != null) {
                        bluetoothGatt.discoverServices();
                    }
                } catch (SecurityException e) {
                    Log.e(TAG, "Error descubriendo servicios", e);
                }
            }, 500);
        }
        
        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Error descubriendo servicios: " + status);
                callback.onError("Error descubriendo servicios");
                return;
            }
            
            Log.d(TAG, "Servicios descubiertos, configurando ANCS/AMS");
            callback.onConnectionStateChanged(false, "Configurando ANCS...");
            
            setupAncsService(gatt);
            setupAmsService(gatt);
            
            // Operacion final para marcar como listo
            enqueueOperation(new BleOperation(BleOperation.Type.SUBSCRIBE, null, null) {
                @Override
                public String toString() {
                    return "FINALIZE_SETUP";
                }
            });
        }
        
        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            handler.removeCallbacks(operationTimeoutRunnable);
            
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Escritura exitosa: " + characteristic.getUuid());
                onOperationComplete(true);
            } else {
                Log.e(TAG, "Error escritura: " + status);
                onOperationComplete(false);
            }
        }
        
        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            handler.removeCallbacks(operationTimeoutRunnable);
            
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Suscripcion exitosa: " + descriptor.getCharacteristic().getUuid());
                onOperationComplete(true);
            } else {
                Log.e(TAG, "Error suscripcion: " + status);
                onOperationComplete(false);
            }
        }
        
        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            lastDataReceived = System.currentTimeMillis();
            UUID uuid = characteristic.getUuid();
            byte[] data = characteristic.getValue();
            
            if (NOTIFICATION_SOURCE_UUID.equals(uuid)) {
                callback.onNotificationSourceChanged(data);
            } else if (DATA_SOURCE_UUID.equals(uuid)) {
                callback.onDataSourceChanged(data);
            } else if (ENTITY_UPDATE_UUID.equals(uuid)) {
                callback.onEntityUpdateChanged(data);
            }
        }
    };
    
    // =====================================================================
    // SETUP ANCS/AMS
    // =====================================================================
    
    private void setupAncsService(BluetoothGatt gatt) {
        BluetoothGattService service = gatt.getService(ANCS_SERVICE_UUID);
        if (service == null) {
            Log.w(TAG, "Servicio ANCS no encontrado");
            return;
        }
        
        // Suscribir a Data Source primero
        BluetoothGattCharacteristic ds = service.getCharacteristic(DATA_SOURCE_UUID);
        if (ds != null) {
            enqueueOperation(new BleOperation(BleOperation.Type.SUBSCRIBE, ds, null));
        }
        
        // Suscribir a Notification Source
        BluetoothGattCharacteristic ns = service.getCharacteristic(NOTIFICATION_SOURCE_UUID);
        if (ns != null) {
            enqueueOperation(new BleOperation(BleOperation.Type.SUBSCRIBE, ns, null));
        }
    }
    
    private void setupAmsService(BluetoothGatt gatt) {
        BluetoothGattService service = gatt.getService(AMS_SERVICE_UUID);
        if (service == null) {
            Log.d(TAG, "Servicio AMS no disponible (musica desactivada en iOS?)");
            return;
        }
        
        // Remote Command
        BluetoothGattCharacteristic rc = service.getCharacteristic(REMOTE_COMMAND_UUID);
        if (rc != null) {
            enqueueOperation(new BleOperation(BleOperation.Type.SUBSCRIBE, rc, null));
        }
        
        // Entity Update
        BluetoothGattCharacteristic eu = service.getCharacteristic(ENTITY_UPDATE_UUID);
        if (eu != null) {
            enqueueOperation(new BleOperation(BleOperation.Type.SUBSCRIBE, eu, null));
            // Solicitar info del track actual
            enqueueOperation(new BleOperation(BleOperation.Type.WRITE, eu, new byte[]{2, 0, 1, 2, 3}));
            // Solicitar estado de reproduccion
            enqueueOperation(new BleOperation(BleOperation.Type.WRITE, eu, new byte[]{0, 1}));
        }
    }
    
    // =====================================================================
    // COLA DE OPERACIONES BLE
    // =====================================================================
    
    private synchronized void enqueueOperation(BleOperation operation) {
        operationQueue.add(operation);
        if (!operationInProgress.get()) {
            processNextOperation();
        }
    }
    
    private synchronized void processNextOperation() {
        if (operationInProgress.get() || bluetoothGatt == null) {
            return;
        }
        
        currentOperation = operationQueue.poll();
        if (currentOperation == null) {
            // Todas las operaciones completadas
            if (!servicesReady.get()) {
                servicesReady.set(true);
                callback.onConnectionStateChanged(true, "Enlace ANCS Activo");
                callback.onServicesReady();
                startWatchdog();
            }
            return;
        }
        
        operationInProgress.set(true);
        handler.postDelayed(operationTimeoutRunnable, OPERATION_TIMEOUT_MS);
        
        boolean success = false;
        
        try {
            if (currentOperation.type == BleOperation.Type.SUBSCRIBE) {
                if (currentOperation.characteristic != null) {
                    success = subscribeToCharacteristic(currentOperation.characteristic);
                } else {
                    // Operacion dummy para finalizar setup
                    success = true;
                    handler.removeCallbacks(operationTimeoutRunnable);
                    onOperationComplete(true);
                }
            } else if (currentOperation.type == BleOperation.Type.WRITE) {
                success = writeCharacteristicInternal(currentOperation.characteristic, currentOperation.data);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error en operacion BLE", e);
        }
        
        if (!success && currentOperation.characteristic != null) {
            handler.removeCallbacks(operationTimeoutRunnable);
            onOperationComplete(false);
        }
    }
    
    private boolean subscribeToCharacteristic(BluetoothGattCharacteristic c) {
        if (c == null || bluetoothGatt == null) return false;
        
        try {
            bluetoothGatt.setCharacteristicNotification(c, true);
            BluetoothGattDescriptor descriptor = c.getDescriptor(CCCD_UUID);
            if (descriptor != null) {
                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                return bluetoothGatt.writeDescriptor(descriptor);
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Sin permisos para suscribir", e);
        }
        return false;
    }
    
    private boolean writeCharacteristicInternal(BluetoothGattCharacteristic c, byte[] data) {
        if (c == null || bluetoothGatt == null || data == null) return false;
        
        try {
            c.setValue(data);
            return bluetoothGatt.writeCharacteristic(c);
        } catch (SecurityException e) {
            Log.e(TAG, "Sin permisos para escribir", e);
        }
        return false;
    }
    
    private void onOperationComplete(boolean success) {
        if (!success && currentOperation != null && currentOperation.retries < MAX_OPERATION_RETRIES) {
            currentOperation.retries++;
            Log.d(TAG, "Reintentando operacion (intento " + currentOperation.retries + ")");
            operationQueue.add(currentOperation);
        }
        
        operationInProgress.set(false);
        currentOperation = null;
        
        // Delay entre operaciones para estabilidad BLE
        handler.postDelayed(this::processNextOperation, 150);
    }
    
    private void onOperationTimeout() {
        Log.w(TAG, "Timeout de operacion BLE");
        onOperationComplete(false);
    }
    
    // =====================================================================
    // WATCHDOG
    // =====================================================================
    
    private void startWatchdog() {
        lastDataReceived = System.currentTimeMillis();
        handler.removeCallbacks(watchdogRunnable);
        handler.postDelayed(watchdogRunnable, WATCHDOG_INTERVAL_MS);
    }
    
    private void checkConnectionHealth() {
        if (!isConnected.get()) return;
        
        // Verificar que el GATT sigue activo
        if (bluetoothGatt == null) {
            Log.w(TAG, "Watchdog: GATT nulo, reconectando");
            scheduleReconnect();
            return;
        }
        
        // Verificar que recibimos datos recientemente (opcional, puede haber periodos sin notificaciones)
        long timeSinceData = System.currentTimeMillis() - lastDataReceived;
        if (timeSinceData > 300000) { // 5 minutos sin datos
            Log.d(TAG, "Watchdog: 5 min sin datos, verificando conexion");
            // Podemos intentar leer una caracteristica para verificar
        }
        
        // Programar siguiente chequeo
        handler.postDelayed(watchdogRunnable, WATCHDOG_INTERVAL_MS);
    }
    
    // =====================================================================
    // API PUBLICA
    // =====================================================================
    
    public boolean isConnected() {
        return isConnected.get() && servicesReady.get();
    }
    
    public void requestNotificationDetails(int uid) {
        if (bluetoothGatt == null || !servicesReady.get()) return;
        
        BluetoothGattService service = bluetoothGatt.getService(ANCS_SERVICE_UUID);
        if (service == null) return;
        
        BluetoothGattCharacteristic cp = service.getCharacteristic(CONTROL_POINT_UUID);
        if (cp == null) return;
        
        // GetNotificationAttributes command
        byte[] command = new byte[14];
        command[0] = 0; // CommandID: GetNotificationAttributes
        command[1] = (byte) (uid & 0xFF);
        command[2] = (byte) ((uid >> 8) & 0xFF);
        command[3] = (byte) ((uid >> 16) & 0xFF);
        command[4] = (byte) ((uid >> 24) & 0xFF);
        command[5] = 0; // AppIdentifier
        command[6] = 1; // Title
        command[7] = 64; // Max length low
        command[8] = 0;  // Max length high
        command[9] = 3;  // Message
        command[10] = 0; // Max length low (256)
        command[11] = 1; // Max length high
        // Bytes 12-13 quedan en 0
        
        enqueueOperation(new BleOperation(BleOperation.Type.WRITE, cp, command));
    }
    
    public void sendMusicCommand(byte commandId) {
        if (bluetoothGatt == null || !servicesReady.get()) return;
        
        BluetoothGattService service = bluetoothGatt.getService(AMS_SERVICE_UUID);
        if (service == null) return;
        
        BluetoothGattCharacteristic rc = service.getCharacteristic(REMOTE_COMMAND_UUID);
        if (rc == null) return;
        
        enqueueOperation(new BleOperation(BleOperation.Type.WRITE, rc, new byte[]{commandId}));
    }
    
    public BluetoothDevice getTargetDevice() {
        return targetDevice;
    }
}

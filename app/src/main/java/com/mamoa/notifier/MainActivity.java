package com.mamoa.notifier;

import android.app.ActivityManager;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.Set;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_BLUETOOTH_PERMISSIONS = 3;

    private BluetoothAdapter bluetoothAdapter;
    private SharedPreferences prefs;

    // Views - Card 1: Main Control
    private TextView statusText;
    private TextView connectionText;
    private TextView connectionSubtext;
    private View connectionDot;
    private Switch serviceSwitch;
    private Switch syncSwitch;
    private Switch musicSwitch;
    private Button reconnectButton;
    private Button filterButton;

    // Views - Card 2: Media Player
    private LinearLayout mediaPlayerCard;
    private TextView trackTitle;
    private TextView trackArtist;
    private ImageButton btnPrev;
    private ImageButton btnPlayPause;
    private ImageButton btnNext;

    // Views - Card 4: Status Progress
    private LinearLayout statusProgressCard;
    private TextView statusTitle;
    private TextView statusSubtitle;
    private ProgressBar connectionProgress;
    private Button btnStop;

    // --- BroadcastReceiver para actualizaciones de musica ---
    private final BroadcastReceiver musicUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (NotificationSyncService.ACTION_MUSIC_UPDATE.equals(intent.getAction())) {
                String title = intent.getStringExtra("title");
                String artist = intent.getStringExtra("artist");
                if (title != null) trackTitle.setText(title);
                if (artist != null) trackArtist.setText(artist);
            }
        }
    };

    // --- BroadcastReceiver para estado de conexion BLE ---
    private final BroadcastReceiver connectionStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (NotificationSyncService.ACTION_CONNECTION_STATE.equals(intent.getAction())) {
                boolean connected = intent.getBooleanExtra(
                        NotificationSyncService.EXTRA_IS_CONNECTED, false);
                String message = intent.getStringExtra(
                        NotificationSyncService.EXTRA_STATUS_MESSAGE);
                updateUIFromConnectionState(connected, message);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("MamoaSettings", MODE_PRIVATE);
        bluetoothAdapter = ((BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE)).getAdapter();

        initViews();
        checkBasicPermissions();
        setupListeners();
        syncServiceState();
    }

    private void initViews() {
        // Card 1: Main Control
        statusText = findViewById(R.id.statusText);
        connectionText = findViewById(R.id.connectionText);
        connectionSubtext = findViewById(R.id.connectionSubtext);
        connectionDot = findViewById(R.id.connectionDot);
        serviceSwitch = findViewById(R.id.serviceSwitch);
        syncSwitch = findViewById(R.id.syncSwitch);
        musicSwitch = findViewById(R.id.musicSwitch);
        reconnectButton = findViewById(R.id.reconnectButton);
        filterButton = findViewById(R.id.filterButton);

        // Card 2: Media Player
        mediaPlayerCard = findViewById(R.id.mediaPlayerCard);
        trackTitle = findViewById(R.id.trackTitle);
        trackArtist = findViewById(R.id.trackArtist);
        btnPrev = findViewById(R.id.btnPrev);
        btnPlayPause = findViewById(R.id.btnPlayPause);
        btnNext = findViewById(R.id.btnNext);

        // Card 4: Status Progress
        statusProgressCard = findViewById(R.id.statusProgressCard);
        statusTitle = findViewById(R.id.statusTitle);
        statusSubtitle = findViewById(R.id.statusSubtitle);
        connectionProgress = findViewById(R.id.connectionProgress);
        btnStop = findViewById(R.id.btnStop);

        // Restore saved states
        musicSwitch.setChecked(prefs.getBoolean("music_control_enabled", false));
        syncSwitch.setChecked(prefs.getBoolean("sync_enabled", true));

        // Forzar ocultación del reproductor
        updateMediaCardVisibility();
    }

    private void syncServiceState() {
        boolean serviceRunning = isServiceRunning(NotificationSyncService.class);
        boolean prefEnabled = prefs.getBoolean("service_enabled", false);

        if (serviceRunning && !prefEnabled) {
            prefs.edit().putBoolean("service_enabled", true).apply();
        }
        if (!serviceRunning && prefEnabled) {
            startNotificationService();
        }
        serviceSwitch.setChecked(serviceRunning);
        
        // RECUPERAR ÚLTIMO ESTADO GUARDADO
        boolean lastConnected = prefs.getBoolean("last_is_connected", false);
        String lastMessage = prefs.getString("last_status_message", "Esperando estado...");
        
        if (serviceRunning) {
            updateUIFromConnectionState(lastConnected, lastMessage);
        } else {
            updateConnectionStatus();
        }
        
        updateStatusProgressCard(serviceRunning);
    }

    private void setupListeners() {
        // --- Service switch ---
        serviceSwitch.setOnClickListener(v -> {
            if (serviceSwitch.isChecked()) {
                if (!isServiceRunning(NotificationSyncService.class)) {
                    showActivationDialog();
                }
            } else {
                prefs.edit().putBoolean("service_enabled", false).apply();
                stopNotificationService();
                updateConnectionStatus();
                updateStatusProgressCard(false);
            }
        });

        // --- Sync switch ---
        syncSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("sync_enabled", isChecked).apply();
            if (isChecked && isServiceRunning(NotificationSyncService.class)) {
                // Reconectar para reactivar sync
                reconnectBluetooth();
            }
        });

        // --- Music control switch ---
        musicSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("music_control_enabled", isChecked).apply();
            updateMediaCardVisibility();
            if (isServiceRunning(NotificationSyncService.class)) {
                reconnectBluetooth();
            }
        });

        // --- Filter button ---
        filterButton.setOnClickListener(v -> {
            startActivity(new Intent(this, NotificationFilterActivity.class));
        });

        // --- Reconnect button: usar Intent en vez de stop/start ---
        reconnectButton.setOnClickListener(v -> {
            reconnectBluetooth();
        });

        // --- Stop service button ---
        btnStop.setOnClickListener(v -> {
            serviceSwitch.setChecked(false);
            prefs.edit().putBoolean("service_enabled", false).apply();
            stopNotificationService();
            updateConnectionStatus();
            updateStatusProgressCard(false);
        });

        // --- Media player controls (AMS commands: 0=Play, 1=Pause, 2=Toggle, 3=Next, 4=Prev) ---
        btnPlayPause.setOnClickListener(v -> sendMusicCommand((byte) 2));
        btnNext.setOnClickListener(v -> sendMusicCommand((byte) 3));
        btnPrev.setOnClickListener(v -> sendMusicCommand((byte) 4));
    }

    private void sendMusicCommand(byte commandId) {
        Intent intent = new Intent(this, NotificationSyncService.class);
        intent.setAction(NotificationSyncService.ACTION_MUSIC_COMMAND);
        intent.putExtra(NotificationSyncService.EXTRA_MUSIC_COMMAND_ID, commandId);
        startService(intent);
    }

    private void showActivationDialog() {
        new AlertDialog.Builder(this)
            .setTitle("Activar Sincronizacion")
            .setMessage("Deseas conectar con tu iPhone para recibir notificaciones?")
            .setPositiveButton("Conectar", (dialog, which) -> {
                prefs.edit().putBoolean("service_enabled", true).apply();
                startNotificationService();
                updateConnectionStatus();
                updateStatusProgressCard(true);
            })
            .setNegativeButton("Cancelar", (dialog, which) -> {
                serviceSwitch.setChecked(false);
            })
            .setCancelable(false)
            .show();
    }

    private void checkBasicPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            String[] permissions = {
                "android.permission.BLUETOOTH_CONNECT",
                "android.permission.BLUETOOTH_SCAN",
                "android.permission.ACCESS_FINE_LOCATION",
                "android.permission.POST_NOTIFICATIONS"
            };

            for (String permission : permissions) {
                if (ContextCompat.checkSelfPermission(this, permission) != 0) {
                    ActivityCompat.requestPermissions(this, permissions, REQUEST_BLUETOOTH_PERMISSIONS);
                    break;
                }
            }
        }
    }

    private boolean isNotificationServiceEnabled() {
        String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        if (flat == null || flat.isEmpty()) {
            return false;
        }
        return flat.contains(getPackageName() + "/");
    }

    private boolean isServiceRunning(Class<?> serviceClass) {
        ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (manager == null) return false;
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.getName().equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

    private void startNotificationService() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            serviceSwitch.setChecked(false);
            Toast.makeText(this, "Bluetooth no disponible", Toast.LENGTH_SHORT).show();
            return;
        }
        startForegroundService(new Intent(this, NotificationSyncService.class));
        updateStatusProgressCard(true);
    }

    private void stopNotificationService() {
        stopService(new Intent(this, NotificationSyncService.class));
    }

    /**
     * Reconexion via Intent al servicio en vez de reiniciar el servicio entero.
     * Esto es mas limpio y evita perder el estado del servicio foreground.
     */
    private void reconnectBluetooth() {
        if (!isServiceRunning(NotificationSyncService.class)) {
            // Si el servicio no esta corriendo, arrancarlo
            if (serviceSwitch.isChecked()) {
                startNotificationService();
            }
            return;
        }
        // Enviar intent de reconexion al servicio
        Intent reconnectIntent = new Intent(this, NotificationSyncService.class);
        reconnectIntent.setAction(NotificationSyncService.ACTION_RECONNECT);
        startService(reconnectIntent);
        statusTitle.setText("Reconectando...");
        statusSubtitle.setText("Cerrando conexion anterior...");
        connectionProgress.setVisibility(View.VISIBLE);
    }

    // --- Actualizar UI desde broadcast de estado de conexion ---
    private void updateUIFromConnectionState(boolean connected, String message) {
        runOnUiThread(() -> {
            if (connected) {
                connectionText.setText("iPhone Conectado");
                connectionSubtext.setText(message != null ? message : "Conectado");
                connectionDot.setBackgroundResource(R.drawable.bg_status_dot_green);
                statusTitle.setText("Conectado");
                statusSubtitle.setText(message != null ? message : "Enlace ANCS activo");
                connectionProgress.setVisibility(View.GONE);
            } else {
                connectionText.setText("Conectando...");
                connectionSubtext.setText(message != null ? message : "Buscando iPhone...");
                connectionDot.setBackgroundResource(R.drawable.bg_status_dot_red);
                statusTitle.setText("Buscando iPhone...");
                statusSubtitle.setText(message != null ? message : "Estableciendo enlace...");
                connectionProgress.setVisibility(View.VISIBLE);
            }
        });
    }

    private void updateConnectionStatus() {
        if (bluetoothAdapter == null) {
            statusText.setText("Bluetooth no disponible");
            connectionText.setText("Desconectado");
            connectionSubtext.setText("Sin adaptador Bluetooth");
            connectionDot.setBackgroundResource(R.drawable.bg_status_dot_red);
            return;
        }

        try {
            if (!bluetoothAdapter.isEnabled()) {
                statusText.setText("Bluetooth desactivado");
                connectionText.setText("Desconectado");
                connectionSubtext.setText("Activa el Bluetooth");
                connectionDot.setBackgroundResource(R.drawable.bg_status_dot_red);
                return;
            }

            Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
            boolean iphoneFound = false;

            for (BluetoothDevice device : pairedDevices) {
                String deviceName = null;
                try {
                    deviceName = device.getName();
                } catch (SecurityException ignored) {}
                
                if (deviceName != null && deviceName.toLowerCase().contains("iphone")) {
                    iphoneFound = true;
                    statusText.setText("iPhone vinculado: " + deviceName);

                    if (isServiceRunning(NotificationSyncService.class)) {
                        connectionText.setText("Servicio activo");
                        connectionSubtext.setText("Esperando estado...");
                        connectionDot.setBackgroundResource(R.drawable.bg_status_dot_green);
                    } else {
                        connectionText.setText("Servicio detenido");
                        connectionSubtext.setText("Activa el servicio para conectar");
                        connectionDot.setBackgroundResource(R.drawable.bg_status_dot_red);
                    }
                    break;
                }
            }

            if (!iphoneFound) {
                statusText.setText("iPhone no encontrado");
                connectionText.setText("No emparejado");
                connectionSubtext.setText("Vincula tu iPhone en ajustes Bluetooth");
                connectionDot.setBackgroundResource(R.drawable.bg_status_dot_red);
            }
        } catch (SecurityException e) {
            statusText.setText("Sin permisos de Bluetooth");
            connectionText.setText("Error de permisos");
            connectionSubtext.setText("Otorga permisos de Bluetooth");
            connectionDot.setBackgroundResource(R.drawable.bg_status_dot_red);
        }
    }

    private void updateMediaCardVisibility() {
        if (mediaPlayerCard != null) {
            mediaPlayerCard.setVisibility(View.GONE);
        }
    }

    private void updateStatusProgressCard(boolean serviceRunning) {
        if (serviceRunning) {
            statusProgressCard.setVisibility(View.VISIBLE);
            // No cambiamos los textos aquí si ya están configurados por updateUIFromConnectionState
        } else {
            statusProgressCard.setVisibility(View.VISIBLE);
            statusTitle.setText("Servicio detenido");
            statusSubtitle.setText("Activa el servicio para conectar");
            connectionProgress.setVisibility(View.GONE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Registrar receivers con filtros (package-scoped para Android 14+)
        IntentFilter musicFilter = new IntentFilter(NotificationSyncService.ACTION_MUSIC_UPDATE);
        IntentFilter connectionFilter = new IntentFilter(NotificationSyncService.ACTION_CONNECTION_STATE);

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(musicUpdateReceiver, musicFilter, Context.RECEIVER_NOT_EXPORTED);
            registerReceiver(connectionStateReceiver, connectionFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(musicUpdateReceiver, musicFilter);
            registerReceiver(connectionStateReceiver, connectionFilter);
        }
        syncServiceState();
        updateMediaCardVisibility();
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(musicUpdateReceiver);
        unregisterReceiver(connectionStateReceiver);
    }
}

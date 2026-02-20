package com.mamoa.notifier;

import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pantalla de configuracion para filtrar que apps de iPhone envian
 * notificaciones al reloj. Muestra las apps que se han detectado
 * (que alguna vez enviaron una notificacion) con su icono real
 * y un switch para activar/desactivar cada una.
 */
public class NotificationFilterActivity extends AppCompatActivity {

    private static final String TAG = "MamoaFilter";
    private static final String PREFS_NAME = "MamoaSettings";
    private static final String KEY_BLOCKED_APPS = "blocked_apps";
    private static final String KEY_KNOWN_APPS = "known_apps";

    private LinearLayout appListContainer;
    private TextView emptyText;
    private SharedPreferences prefs;
    private AppIconCache iconCache;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_notification_filter);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        iconCache = AppIconCache.getInstance(this);

        appListContainer = findViewById(R.id.appListContainer);
        emptyText = findViewById(R.id.emptyText);

        ImageView backButton = findViewById(R.id.backButton);
        backButton.setOnClickListener(v -> finish());

        loadAppList();
    }

    private void loadAppList() {
        appListContainer.removeAllViews();

        // Obtener las apps conocidas (las que alguna vez enviaron notificacion)
        Set<String> knownApps = prefs.getStringSet(KEY_KNOWN_APPS, new HashSet<>());
        Set<String> blockedApps = getBlockedApps();

        if (knownApps.isEmpty()) {
            emptyText.setVisibility(View.VISIBLE);
            appListContainer.setVisibility(View.GONE);
            return;
        }

        emptyText.setVisibility(View.GONE);
        appListContainer.setVisibility(View.VISIBLE);

        // Ordenar alfabeticamente por nombre de display
        List<String> sortedApps = new ArrayList<>(knownApps);
        Collections.sort(sortedApps, (a, b) -> {
            String nameA = getDisplayName(a);
            String nameB = getDisplayName(b);
            return nameA.compareToIgnoreCase(nameB);
        });

        for (String bundleId : sortedApps) {
            addAppRow(bundleId, !blockedApps.contains(bundleId));
        }
    }

    private void addAppRow(String bundleId, boolean isEnabled) {
        View row = LayoutInflater.from(this).inflate(R.layout.item_app_filter, appListContainer, false);

        ImageView iconView = row.findViewById(R.id.appIcon);
        TextView nameText = row.findViewById(R.id.appName);
        TextView bundleText = row.findViewById(R.id.appBundleId);
        Switch enableSwitch = row.findViewById(R.id.appSwitch);

        String displayName = getDisplayName(bundleId);
        nameText.setText(displayName);
        bundleText.setText(bundleId);
        enableSwitch.setChecked(isEnabled);

        // Cargar icono real desde cache
        Bitmap cachedIcon = iconCache.getIconSync(bundleId);
        if (cachedIcon != null) {
            iconView.setImageBitmap(cachedIcon);
        } else {
            // Usar icono generico mientras se descarga
            iconView.setImageResource(R.drawable.ic_notif_iphone);
            // Descargar en background
            iconCache.getIcon(bundleId, icon -> {
                if (icon != null) {
                    iconView.setImageBitmap(icon);
                }
            });
        }

        enableSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Set<String> blocked = getBlockedApps();
            if (isChecked) {
                blocked.remove(bundleId);
            } else {
                blocked.add(bundleId);
            }
            saveBlockedApps(blocked);
            Log.d(TAG, "App " + bundleId + " " + (isChecked ? "activada" : "bloqueada"));
        });

        appListContainer.addView(row);
    }

    private String getDisplayName(String bundleId) {
        // Buscar nombre guardado en prefs (guardado por NotificationSyncService)
        String savedName = prefs.getString("app_name_" + bundleId, null);
        if (savedName != null && !savedName.isEmpty()) return savedName;

        // Fallback: extraer del bundle ID
        int lastDot = bundleId.lastIndexOf('.');
        if (lastDot >= 0 && lastDot < bundleId.length() - 1) {
            return bundleId.substring(lastDot + 1);
        }
        return bundleId;
    }

    // --- Gestion de apps bloqueadas ---

    private Set<String> getBlockedApps() {
        return new HashSet<>(prefs.getStringSet(KEY_BLOCKED_APPS, new HashSet<>()));
    }

    private void saveBlockedApps(Set<String> blocked) {
        prefs.edit().putStringSet(KEY_BLOCKED_APPS, blocked).apply();
    }

    /**
     * Metodo estatico para verificar si una app esta bloqueada.
     * Usado desde NotificationSyncService.
     */
    public static boolean isAppBlocked(SharedPreferences prefs, String bundleId) {
        if (bundleId == null) return false;
        Set<String> blocked = prefs.getStringSet(KEY_BLOCKED_APPS, new HashSet<>());
        return blocked.contains(bundleId);
    }

    /**
     * Registra una app como conocida (para que aparezca en la lista de filtros).
     * Tambien guarda su nombre legible.
     */
    public static void registerKnownApp(SharedPreferences prefs, String bundleId, String displayName) {
        if (bundleId == null || bundleId.isEmpty()) return;

        Set<String> known = new HashSet<>(prefs.getStringSet(KEY_KNOWN_APPS, new HashSet<>()));
        known.add(bundleId);

        SharedPreferences.Editor editor = prefs.edit();
        editor.putStringSet(KEY_KNOWN_APPS, known);
        if (displayName != null && !displayName.isEmpty()) {
            editor.putString("app_name_" + bundleId, displayName);
        }
        editor.apply();
    }
}

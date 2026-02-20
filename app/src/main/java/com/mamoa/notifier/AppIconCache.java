package com.mamoa.notifier;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Descarga y cachea los iconos reales de apps iOS usando la iTunes Search API.
 * Los iconos se guardan en disco para persistir entre reinicios y en memoria LRU
 * para acceso rapido durante la sesion.
 */
public class AppIconCache {

    private static final String TAG = "MamoaIconCache";
    private static final String CACHE_DIR = "app_icons";
    private static final int ICON_SIZE = 64; // px para el icono de notificacion (largeIcon)
    private static final String ITUNES_LOOKUP_URL = "https://itunes.apple.com/lookup?bundleId=";
    private static final String ITUNES_SEARCH_URL = "https://itunes.apple.com/search?term=";
    private static final long FAILED_RETRY_INTERVAL_MS = 24 * 60 * 60 * 1000; // Reintentar después de 24h

    private static AppIconCache instance;

    private final Context context;
    private final LruCache<String, Bitmap> memoryCache;
    private final ExecutorService executor;
    private final Handler mainHandler;
    private final File diskCacheDir;
    // Registro de descargas fallidas para no reintentar constantemente
    private final Map<String, Long> failedDownloads = new ConcurrentHashMap<>();

    public interface IconCallback {
        void onIconReady(Bitmap icon);
    }

    private AppIconCache(Context context) {
        this.context = context.getApplicationContext();
        this.executor = Executors.newFixedThreadPool(2);
        this.mainHandler = new Handler(Looper.getMainLooper());

        // LRU cache de 4MB para iconos en memoria
        int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        int cacheSize = maxMemory / 8;
        this.memoryCache = new LruCache<String, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                return bitmap.getByteCount() / 1024;
            }
        };

        // Directorio de cache en disco
        this.diskCacheDir = new File(context.getCacheDir(), CACHE_DIR);
        if (!diskCacheDir.exists()) {
            diskCacheDir.mkdirs();
        }
    }

    public static synchronized AppIconCache getInstance(Context context) {
        if (instance == null) {
            instance = new AppIconCache(context);
        }
        return instance;
    }

    /**
     * Obtiene el icono de una app iOS. Primero busca en memoria, luego en disco,
     * y si no existe lo descarga de iTunes API.
     */
    public void getIcon(String bundleId, IconCallback callback) {
        if (bundleId == null || bundleId.isEmpty()) {
            callback.onIconReady(null);
            return;
        }

        // 1. Buscar en memoria
        Bitmap cached = memoryCache.get(bundleId);
        if (cached != null) {
            callback.onIconReady(cached);
            return;
        }

        // 2. Buscar en disco y si no, descargar (en background)
        executor.execute(() -> {
            Bitmap diskIcon = loadFromDisk(bundleId);
            if (diskIcon != null) {
                memoryCache.put(bundleId, diskIcon);
                mainHandler.post(() -> callback.onIconReady(diskIcon));
                return;
            }

            // 3. Descargar de iTunes API
            Bitmap downloadedIcon = downloadIcon(bundleId);
            if (downloadedIcon != null) {
                saveToDisk(bundleId, downloadedIcon);
                memoryCache.put(bundleId, downloadedIcon);
                mainHandler.post(() -> callback.onIconReady(downloadedIcon));
            } else {
                mainHandler.post(() -> callback.onIconReady(null));
            }
        });
    }

    /**
     * Obtiene el icono de forma sincrona (para usar desde background threads).
     * Retorna null si no esta en cache.
     */
    public Bitmap getIconSync(String bundleId) {
        if (bundleId == null || bundleId.isEmpty()) return null;

        Bitmap cached = memoryCache.get(bundleId);
        if (cached != null) return cached;

        Bitmap disk = loadFromDisk(bundleId);
        if (disk != null) {
            memoryCache.put(bundleId, disk);
            return disk;
        }

        return null;
    }

    /**
     * Descarga el icono de una app y lo guarda. Para usar en background.
     * Incluye control de reintentos para no saturar la red con apps sin icono.
     */
    public Bitmap downloadAndCache(String bundleId) {
        if (bundleId == null || bundleId.isEmpty()) return null;

        // Primero verificar cache
        Bitmap cached = getIconSync(bundleId);
        if (cached != null) return cached;

        // Verificar si ya falló recientemente (no reintentar antes de 24h)
        Long lastFailed = failedDownloads.get(bundleId);
        if (lastFailed != null && (System.currentTimeMillis() - lastFailed) < FAILED_RETRY_INTERVAL_MS) {
            Log.d(TAG, "Saltando descarga (fallo reciente): " + bundleId);
            return null;
        }

        Bitmap icon = downloadIcon(bundleId);
        if (icon != null) {
            saveToDisk(bundleId, icon);
            memoryCache.put(bundleId, icon);
            failedDownloads.remove(bundleId); // Limpiar si ahora tuvo éxito
        } else {
            failedDownloads.put(bundleId, System.currentTimeMillis());
            Log.d(TAG, "Descarga fallida, se reintentará en 24h: " + bundleId);
        }
        return icon;
    }

    private Bitmap downloadIcon(String bundleId) {
        // Estrategia 1: Buscar por bundle ID exacto (más preciso)
        Bitmap icon = downloadIconFromUrl(ITUNES_LOOKUP_URL + bundleId, bundleId);
        if (icon != null) return icon;

        // Estrategia 2: Buscar por nombre de la app (extraído del bundle ID)
        // Ej: "com.example.MyApp" → buscar "MyApp"
        String appName = extractAppNameFromBundleId(bundleId);
        if (appName != null && !appName.isEmpty()) {
            Log.d(TAG, "Lookup por bundleId falló, buscando por nombre: " + appName);
            String searchUrl = ITUNES_SEARCH_URL + appName.replace(" ", "+") + "&entity=software&limit=1";
            icon = downloadIconFromUrl(searchUrl, bundleId);
            if (icon != null) return icon;
        }

        // Estrategia 3: Buscar con el bundle ID como término de búsqueda
        Log.d(TAG, "Buscando por bundleId como texto: " + bundleId);
        String searchUrl2 = ITUNES_SEARCH_URL + bundleId.replace(".", "+") + "&entity=software&limit=1";
        return downloadIconFromUrl(searchUrl2, bundleId);
    }

    /**
     * Extrae un nombre legible del bundle ID.
     * "com.facebook.Messenger" → "Messenger"
     * "ph.telegra.Telegraph" → "Telegraph"
     */
    private String extractAppNameFromBundleId(String bundleId) {
        if (bundleId == null) return null;
        int lastDot = bundleId.lastIndexOf('.');
        if (lastDot >= 0 && lastDot < bundleId.length() - 1) {
            return bundleId.substring(lastDot + 1);
        }
        return bundleId;
    }

    private Bitmap downloadIconFromUrl(String apiUrl, String bundleId) {
        try {
            Log.d(TAG, "Buscando icono para: " + bundleId + " URL: " + apiUrl);

            HttpURLConnection conn = (HttpURLConnection) new URL(apiUrl).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestMethod("GET");

            if (conn.getResponseCode() != 200) {
                conn.disconnect();
                return null;
            }

            // Leer respuesta JSON
            InputStream is = conn.getInputStream();
            byte[] buffer = new byte[8192];
            StringBuilder sb = new StringBuilder();
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                sb.append(new String(buffer, 0, bytesRead, "UTF-8"));
            }
            is.close();
            conn.disconnect();

            String json = sb.toString();

            // Verificar que hay resultados (resultCount > 0)
            if (json.contains("\"resultCount\":0") || json.contains("\"resultCount\": 0")) {
                Log.d(TAG, "Sin resultados para: " + bundleId);
                return null;
            }

            // Extraer URL del icono
            String iconUrl = extractIconUrl(json);
            if (iconUrl == null) {
                Log.d(TAG, "No se encontro icono en respuesta para: " + bundleId);
                return null;
            }

            // Descargar la imagen del icono
            Log.d(TAG, "Descargando icono: " + iconUrl);
            HttpURLConnection imgConn = (HttpURLConnection) new URL(iconUrl).openConnection();
            imgConn.setConnectTimeout(8000);
            imgConn.setReadTimeout(8000);

            if (imgConn.getResponseCode() != 200) {
                imgConn.disconnect();
                return null;
            }

            InputStream imgStream = imgConn.getInputStream();
            Bitmap original = BitmapFactory.decodeStream(imgStream);
            imgStream.close();
            imgConn.disconnect();

            if (original == null) return null;

            // Redimensionar al tamano adecuado para notificaciones
            Bitmap scaled = Bitmap.createScaledBitmap(original, ICON_SIZE, ICON_SIZE, true);
            if (scaled != original) {
                original.recycle();
            }

            Log.d(TAG, "Icono descargado exitosamente para: " + bundleId);
            return scaled;

        } catch (Exception e) {
            Log.e(TAG, "Error descargando icono para " + bundleId + " desde " + apiUrl, e);
            return null;
        }
    }

    /**
     * Extrae la URL del icono del JSON de respuesta de iTunes API.
     * Parseo manual simple para evitar dependencia de JSONObject en el hilo de red.
     */
    private String extractIconUrl(String json) {
        // Buscar artworkUrl100 primero (mejor calidad), luego artworkUrl60
        String url = extractJsonStringValue(json, "artworkUrl100");
        if (url == null) {
            url = extractJsonStringValue(json, "artworkUrl60");
        }
        if (url == null) {
            url = extractJsonStringValue(json, "artworkUrl512");
        }
        return url;
    }

    private String extractJsonStringValue(String json, String key) {
        String searchKey = "\"" + key + "\":\"";
        int startIdx = json.indexOf(searchKey);
        if (startIdx == -1) return null;

        startIdx += searchKey.length();
        int endIdx = json.indexOf("\"", startIdx);
        if (endIdx == -1) return null;

        return json.substring(startIdx, endIdx);
    }

    private Bitmap loadFromDisk(String bundleId) {
        try {
            File file = new File(diskCacheDir, sanitizeFilename(bundleId) + ".png");
            if (!file.exists()) return null;

            FileInputStream fis = new FileInputStream(file);
            Bitmap bitmap = BitmapFactory.decodeStream(fis);
            fis.close();
            return bitmap;
        } catch (Exception e) {
            Log.e(TAG, "Error leyendo icono de disco: " + bundleId, e);
            return null;
        }
    }

    private void saveToDisk(String bundleId, Bitmap bitmap) {
        try {
            File file = new File(diskCacheDir, sanitizeFilename(bundleId) + ".png");
            FileOutputStream fos = new FileOutputStream(file);
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.flush();
            fos.close();
        } catch (Exception e) {
            Log.e(TAG, "Error guardando icono en disco: " + bundleId, e);
        }
    }

    /**
     * Verifica si un icono ya esta en cache (memoria o disco).
     */
    public boolean hasIcon(String bundleId) {
        if (bundleId == null) return false;
        if (memoryCache.get(bundleId) != null) return true;
        File file = new File(diskCacheDir, sanitizeFilename(bundleId) + ".png");
        return file.exists();
    }

    private String sanitizeFilename(String bundleId) {
        return bundleId.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}

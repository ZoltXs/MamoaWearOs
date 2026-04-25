# Mamoa - Samsung WearOS to iPhone Sync

Sistema completo para sincronizar notificaciones y datos de salud entre Samsung WearOS y iPhone.

## Arquitectura

```
┌─────────────────┐         ┌─────────────────┐         ┌─────────────────┐
│  Samsung Watch  │ ──BLE── │     iPhone      │         │     iPhone      │
│    (WearOS)     │  ANCS   │  (Notificaciones)│         │  (MamoaBridge)  │
│                 │         │                 │         │                 │
│  Mamoa Notifier │         │   Sistema iOS   │         │   Apple Health  │
└────────┬────────┘         └─────────────────┘         └────────▲────────┘
         │                                                       │
         │ HTTP (datos de salud)                                │
         ▼                                                       │
┌─────────────────┐                                              │
│   Backend API   │ ──────────────────────────────────────────────
│    (Vercel)     │  HTTP (sync datos)
└─────────────────┘
```

## Componentes

### 1. App WearOS (app/)

App Android para Samsung Galaxy Watch con WearOS.

**Archivos principales mejorados:**

- `BluetoothConnectionManager.java` - Gestor robusto de conexion BLE con:
  - Backoff exponencial en reconexiones (1s → 2s → 4s → ... → 30s max)
  - Deteccion de iPhone por UUID ANCS (no solo por nombre)
  - Cola de operaciones BLE con reintentos (max 3)
  - Watchdog para detectar conexiones zombies
  - Escaneo BLE activo cuando no hay dispositivo emparejado

- `ConnectionWatchdogWorker.java` - Worker de WorkManager que:
  - Verifica cada 15 min que el servicio siga activo
  - Reinicia el servicio si fue matado por el sistema
  - Solicita reconexion si lleva mucho tiempo desconectado

- `NotificationSyncService.java` - Servicio principal refactorizado para usar el nuevo gestor BLE

- `health/HealthDataCollector.java` - Colector de datos de salud:
  - Pasos (step counter sensor)
  - Frecuencia cardiaca (heart rate sensor)
  - Calorias (calculadas)
  - Distancia (calculada desde pasos)
  - Sueno y workouts (registro manual)

- `health/HealthSyncService.java` - Worker para sincronizar datos con el servidor cada 15 min

- `health/HealthDatabase.java` - Base de datos Room para persistencia local

### 2. Backend API (backend/)

API Next.js desplegable en Vercel.

**Endpoints:**

- `POST /api/health/sync` - Recibe datos de salud del WearOS
- `GET /api/health/sync?deviceId=xxx` - Obtiene datos para iOS
- `GET /api/health/latest?deviceId=xxx` - Ultimos valores de cada tipo
- `POST /api/devices/link` - Vincula dispositivos WearOS/iOS
- `GET /api/devices?userId=xxx` - Lista dispositivos de un usuario

### 3. App iOS MamoaBridge (ios/)

App SwiftUI para iPhone.

**Funcionalidades:**

- Vinculacion con WearOS mediante codigo de 6 digitos
- Sincronizacion de datos de salud desde el servidor
- Escritura a Apple HealthKit:
  - Pasos
  - Frecuencia cardiaca
  - Calorias activas
  - Distancia
  - Sueno
  - Entrenamientos

- Background App Refresh cada 15 min
- Notification Service Extension (solo para notificaciones propias)

## Instalacion

### WearOS

1. Abrir en Android Studio
2. Configurar `health_sync_server_url` en SharedPreferences
3. Compilar e instalar en el reloj

### Backend

```bash
cd backend
npm install
npm run dev
```

Para produccion, desplegar en Vercel.

### iOS

1. Abrir `ios/MamoaBridge` en Xcode
2. Configurar el Bundle ID y equipo de desarrollo
3. Habilitar HealthKit en Signing & Capabilities
4. Compilar e instalar

## Flujo de Notificaciones

Las notificaciones del iPhone llegan al Samsung Watch via **ANCS** (Apple Notification Center Service), un protocolo BLE nativo de Apple. La app WearOS:

1. Se conecta al iPhone emparejado via BLE
2. Suscribe al servicio ANCS (UUID: 7905F431-B5CE-4E99-A40F-4B1E122D00D0)
3. Recibe eventos de nuevas notificaciones
4. Solicita detalles (app, titulo, mensaje)
5. Muestra notificacion local en el reloj

**Limitacion importante:** Apple no permite a apps de terceros interceptar notificaciones de otras apps. ANCS es la unica forma oficial de recibir notificaciones de iPhone en dispositivos no-Apple.

## Flujo de Datos de Salud

```
Samsung Watch → Sensores → HealthDataCollector → Room DB
                                                    ↓
                                            HealthSyncService (cada 15 min)
                                                    ↓
                                              Backend API
                                                    ↓
                                            iOS SyncManager (cada 15 min)
                                                    ↓
                                              HealthKitManager → Apple Health
```

## Permisos Requeridos

### WearOS
- BLUETOOTH, BLUETOOTH_CONNECT, BLUETOOTH_SCAN
- BODY_SENSORS, ACTIVITY_RECOGNITION
- INTERNET, WAKE_LOCK
- FOREGROUND_SERVICE_CONNECTED_DEVICE

### iOS
- HealthKit (read/write)
- Background App Refresh
- Internet

## Configuracion

### WearOS SharedPreferences

```java
prefs.putString("health_sync_server_url", "https://tu-backend.vercel.app");
prefs.putString("device_id", "wearos_xxx");  // auto-generado
```

### iOS UserDefaults

```swift
UserDefaults.standard.set("https://tu-backend.vercel.app", forKey: "serverURL")
```

## Notas Tecnicas

- El backend usa almacenamiento en memoria (Map). Para produccion, conectar a Supabase/Postgres.
- La sincronizacion es unidireccional: WearOS → Backend → iOS
- Los datos de salud se guardan con `externalUUID` para evitar duplicados en HealthKit
- El watchdog WorkManager sobrevive a Doze mode y reinicios del sistema

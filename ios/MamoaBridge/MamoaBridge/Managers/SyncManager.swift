import Foundation
import HealthKit
import Combine

/// Gestor de sincronizacion entre el servidor y HealthKit
@MainActor
class SyncManager: ObservableObject {
    static let shared = SyncManager()
    
    // Configuracion
    private var serverURL: String {
        UserDefaults.standard.string(forKey: "serverURL") ?? "https://mamoa-health-backend.vercel.app"
    }
    
    // Estado
    @Published var isLinked = false
    @Published var linkedDeviceId: String?
    @Published var isSyncing = false
    @Published var lastSyncDate: Date?
    @Published var lastError: String?
    
    // Datos de hoy
    @Published var todaySteps: Int = 0
    @Published var averageHeartRate: Double = 0
    @Published var todayCalories: Double = 0
    @Published var todayDistance: Double = 0
    
    private let healthManager = HealthKitManager.shared
    private var lastSyncTimestamp: TimeInterval = 0
    
    private init() {
        loadStoredState()
    }
    
    // MARK: - State Management
    
    private func loadStoredState() {
        linkedDeviceId = UserDefaults.standard.string(forKey: "linkedDeviceId")
        isLinked = linkedDeviceId != nil
        lastSyncTimestamp = UserDefaults.standard.double(forKey: "lastSyncTimestamp")
        if lastSyncTimestamp > 0 {
            lastSyncDate = Date(timeIntervalSince1970: lastSyncTimestamp / 1000)
        }
    }
    
    private func saveState() {
        UserDefaults.standard.set(linkedDeviceId, forKey: "linkedDeviceId")
        UserDefaults.standard.set(lastSyncTimestamp, forKey: "lastSyncTimestamp")
    }
    
    // MARK: - Device Linking
    
    func linkDevice(code: String) async throws {
        let url = URL(string: "\(serverURL)/api/devices/link")!
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        
        let userId = getOrCreateUserId()
        let iosDeviceId = getOrCreateDeviceId()
        
        let body: [String: Any] = [
            "iosDeviceId": iosDeviceId,
            "userId": userId,
            "linkCode": code
        ]
        
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        
        let (data, response) = try await URLSession.shared.data(for: request)
        
        guard let httpResponse = response as? HTTPURLResponse else {
            throw SyncError.invalidResponse
        }
        
        if httpResponse.statusCode == 200 {
            let result = try JSONDecoder().decode(LinkResponse.self, from: data)
            linkedDeviceId = result.link.wearosDeviceId
            isLinked = true
            saveState()
        } else if httpResponse.statusCode == 404 {
            throw SyncError.deviceNotFound
        } else {
            throw SyncError.serverError(httpResponse.statusCode)
        }
    }
    
    // MARK: - Health Sync
    
    func syncHealthData() async {
        guard isLinked, let deviceId = linkedDeviceId else {
            lastError = "Dispositivo no vinculado"
            return
        }
        
        isSyncing = true
        lastError = nil
        
        do {
            // Obtener datos del servidor
            let healthData = try await fetchHealthData(deviceId: deviceId)
            
            // Escribir a HealthKit
            var stepsTotal = 0
            var heartRates: [Double] = []
            var caloriesTotal = 0.0
            var distanceTotal = 0.0
            
            for record in healthData {
                try await writeRecordToHealthKit(record)
                
                // Acumular para mostrar en UI
                let recordDate = Date(timeIntervalSince1970: Double(record.timestamp) / 1000)
                if Calendar.current.isDateInToday(recordDate) {
                    switch record.type {
                    case "steps":
                        stepsTotal += Int(record.value)
                    case "heart_rate":
                        heartRates.append(record.value)
                    case "active_calories":
                        caloriesTotal += record.value
                    case "distance":
                        distanceTotal += record.value
                    default:
                        break
                    }
                }
            }
            
            // Actualizar UI
            todaySteps = stepsTotal
            averageHeartRate = heartRates.isEmpty ? 0 : heartRates.reduce(0, +) / Double(heartRates.count)
            todayCalories = caloriesTotal
            todayDistance = distanceTotal
            
            lastSyncTimestamp = Double(Date().timeIntervalSince1970 * 1000)
            lastSyncDate = Date()
            saveState()
            
        } catch {
            lastError = error.localizedDescription
            print("Sync error: \(error)")
        }
        
        isSyncing = false
    }
    
    private func fetchHealthData(deviceId: String) async throws -> [HealthRecord] {
        var urlComponents = URLComponents(string: "\(serverURL)/api/health/sync")!
        urlComponents.queryItems = [
            URLQueryItem(name: "deviceId", value: deviceId),
            URLQueryItem(name: "since", value: String(Int(lastSyncTimestamp)))
        ]
        
        let (data, response) = try await URLSession.shared.data(from: urlComponents.url!)
        
        guard let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode == 200 else {
            throw SyncError.serverError((response as? HTTPURLResponse)?.statusCode ?? 0)
        }
        
        let result = try JSONDecoder().decode(HealthDataResponse.self, from: data)
        return result.data
    }
    
    private func writeRecordToHealthKit(_ record: HealthRecord) async throws {
        let date = Date(timeIntervalSince1970: Double(record.timestamp) / 1000)
        
        switch record.type {
        case "steps":
            try await healthManager.writeSteps(count: record.value, date: date)
            
        case "heart_rate":
            try await healthManager.writeHeartRate(bpm: record.value, date: date)
            
        case "active_calories":
            try await healthManager.writeActiveCalories(kcal: record.value, date: date)
            
        case "distance":
            try await healthManager.writeDistance(meters: record.value, date: date)
            
        case "sleep":
            if let endTimestamp = record.timestampEnd {
                let endDate = Date(timeIntervalSince1970: Double(endTimestamp) / 1000)
                try await healthManager.writeSleep(start: date, end: endDate)
            }
            
        case "workout":
            if let endTimestamp = record.timestampEnd {
                let endDate = Date(timeIntervalSince1970: Double(endTimestamp) / 1000)
                let workoutType = parseWorkoutType(from: record.metadata)
                try await healthManager.writeWorkout(
                    type: workoutType,
                    start: date,
                    end: endDate,
                    calories: record.value,
                    distance: record.valueSecondary
                )
            }
            
        default:
            print("Unknown record type: \(record.type)")
        }
    }
    
    private func parseWorkoutType(from metadata: String?) -> HKWorkoutActivityType {
        guard let metadata = metadata,
              let data = metadata.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let workoutTypeString = json["workout_type"] as? String else {
            return .other
        }
        return HealthKitManager.workoutType(from: workoutTypeString)
    }
    
    // MARK: - Helpers
    
    private func getOrCreateUserId() -> String {
        if let userId = UserDefaults.standard.string(forKey: "userId") {
            return userId
        }
        let newId = "ios_user_\(UUID().uuidString.prefix(8))"
        UserDefaults.standard.set(newId, forKey: "userId")
        return newId
    }
    
    private func getOrCreateDeviceId() -> String {
        if let deviceId = UserDefaults.standard.string(forKey: "iosDeviceId") {
            return deviceId
        }
        let newId = "ios_\(UUID().uuidString.prefix(12))"
        UserDefaults.standard.set(newId, forKey: "iosDeviceId")
        return newId
    }
}

// MARK: - Models

struct HealthRecord: Codable {
    let id: String
    let deviceId: String
    let type: String
    let value: Double
    let valueSecondary: Double?
    let unit: String
    let timestamp: Int
    let timestampEnd: Int?
    let metadata: String?
    let source: String?
    let receivedAt: Int
}

struct HealthDataResponse: Codable {
    let success: Bool
    let deviceId: String
    let count: Int
    let data: [HealthRecord]
}

struct LinkResponse: Codable {
    let success: Bool
    let link: DeviceLink
}

struct DeviceLink: Codable {
    let wearosDeviceId: String
    let iosDeviceId: String?
    let userId: String
    let linkedAt: Int
}

// MARK: - Errors

enum SyncError: LocalizedError {
    case invalidResponse
    case deviceNotFound
    case serverError(Int)
    case healthKitError(Error)
    
    var errorDescription: String? {
        switch self {
        case .invalidResponse:
            return "Respuesta invalida del servidor"
        case .deviceNotFound:
            return "Dispositivo WearOS no encontrado. Verifica el codigo."
        case .serverError(let code):
            return "Error del servidor: \(code)"
        case .healthKitError(let error):
            return "Error de HealthKit: \(error.localizedDescription)"
        }
    }
}

import Foundation
import HealthKit
import Combine

/// Gestor de HealthKit para escribir datos de salud desde WearOS a Apple Health
@MainActor
class HealthKitManager: ObservableObject {
    static let shared = HealthKitManager()
    
    private let healthStore = HKHealthStore()
    
    @Published var isAuthorized = false
    @Published var authorizationError: String?
    
    // Tipos de datos que vamos a escribir
    private let writeTypes: Set<HKSampleType> = [
        HKQuantityType(.stepCount),
        HKQuantityType(.heartRate),
        HKQuantityType(.activeEnergyBurned),
        HKQuantityType(.distanceWalkingRunning),
        HKCategoryType(.sleepAnalysis),
        HKQuantityType(.appleExerciseTime)
    ]
    
    // Tipos de datos que vamos a leer (para comparar)
    private let readTypes: Set<HKObjectType> = [
        HKQuantityType(.stepCount),
        HKQuantityType(.heartRate),
        HKQuantityType(.activeEnergyBurned),
        HKQuantityType(.distanceWalkingRunning)
    ]
    
    private init() {}
    
    // MARK: - Authorization
    
    func requestAuthorization() {
        guard HKHealthStore.isHealthDataAvailable() else {
            authorizationError = "HealthKit no disponible en este dispositivo"
            return
        }
        
        healthStore.requestAuthorization(toShare: writeTypes, read: readTypes) { [weak self] success, error in
            Task { @MainActor in
                if success {
                    self?.isAuthorized = true
                    self?.authorizationError = nil
                } else {
                    self?.isAuthorized = false
                    self?.authorizationError = error?.localizedDescription ?? "Error de autorizacion"
                }
            }
        }
    }
    
    // MARK: - Write Data
    
    /// Escribe pasos a HealthKit
    func writeSteps(count: Double, date: Date) async throws {
        let type = HKQuantityType(.stepCount)
        let quantity = HKQuantity(unit: .count(), doubleValue: count)
        let sample = HKQuantitySample(
            type: type,
            quantity: quantity,
            start: date,
            end: date,
            metadata: [HKMetadataKeyExternalUUID: "mamoa_wearos_steps_\(date.timeIntervalSince1970)"]
        )
        
        try await healthStore.save(sample)
    }
    
    /// Escribe frecuencia cardiaca a HealthKit
    func writeHeartRate(bpm: Double, date: Date) async throws {
        let type = HKQuantityType(.heartRate)
        let unit = HKUnit.count().unitDivided(by: .minute())
        let quantity = HKQuantity(unit: unit, doubleValue: bpm)
        let sample = HKQuantitySample(
            type: type,
            quantity: quantity,
            start: date,
            end: date,
            metadata: [HKMetadataKeyExternalUUID: "mamoa_wearos_hr_\(date.timeIntervalSince1970)"]
        )
        
        try await healthStore.save(sample)
    }
    
    /// Escribe calorias activas a HealthKit
    func writeActiveCalories(kcal: Double, date: Date) async throws {
        let type = HKQuantityType(.activeEnergyBurned)
        let quantity = HKQuantity(unit: .kilocalorie(), doubleValue: kcal)
        let sample = HKQuantitySample(
            type: type,
            quantity: quantity,
            start: date,
            end: date,
            metadata: [HKMetadataKeyExternalUUID: "mamoa_wearos_cal_\(date.timeIntervalSince1970)"]
        )
        
        try await healthStore.save(sample)
    }
    
    /// Escribe distancia a HealthKit
    func writeDistance(meters: Double, date: Date) async throws {
        let type = HKQuantityType(.distanceWalkingRunning)
        let quantity = HKQuantity(unit: .meter(), doubleValue: meters)
        let sample = HKQuantitySample(
            type: type,
            quantity: quantity,
            start: date,
            end: date,
            metadata: [HKMetadataKeyExternalUUID: "mamoa_wearos_dist_\(date.timeIntervalSince1970)"]
        )
        
        try await healthStore.save(sample)
    }
    
    /// Escribe datos de sueno a HealthKit
    func writeSleep(start: Date, end: Date, quality: String = "asleep") async throws {
        let type = HKCategoryType(.sleepAnalysis)
        
        let value: HKCategoryValueSleepAnalysis
        switch quality.lowercased() {
        case "deep": value = .asleepDeep
        case "rem": value = .asleepREM
        case "core", "light": value = .asleepCore
        case "awake": value = .awake
        default: value = .asleepUnspecified
        }
        
        let sample = HKCategorySample(
            type: type,
            value: value.rawValue,
            start: start,
            end: end,
            metadata: [HKMetadataKeyExternalUUID: "mamoa_wearos_sleep_\(start.timeIntervalSince1970)"]
        )
        
        try await healthStore.save(sample)
    }
    
    /// Escribe un entrenamiento a HealthKit
    func writeWorkout(
        type: HKWorkoutActivityType,
        start: Date,
        end: Date,
        calories: Double,
        distance: Double?
    ) async throws {
        var samples: [HKSample] = []
        
        // Calorias del workout
        let calorieType = HKQuantityType(.activeEnergyBurned)
        let calorieQuantity = HKQuantity(unit: .kilocalorie(), doubleValue: calories)
        let calorieSample = HKQuantitySample(type: calorieType, quantity: calorieQuantity, start: start, end: end)
        samples.append(calorieSample)
        
        // Distancia si aplica
        if let distance = distance, distance > 0 {
            let distanceType = HKQuantityType(.distanceWalkingRunning)
            let distanceQuantity = HKQuantity(unit: .meter(), doubleValue: distance)
            let distanceSample = HKQuantitySample(type: distanceType, quantity: distanceQuantity, start: start, end: end)
            samples.append(distanceSample)
        }
        
        let workout = HKWorkout(
            activityType: type,
            start: start,
            end: end,
            workoutEvents: nil,
            totalEnergyBurned: HKQuantity(unit: .kilocalorie(), doubleValue: calories),
            totalDistance: distance != nil ? HKQuantity(unit: .meter(), doubleValue: distance!) : nil,
            metadata: [HKMetadataKeyExternalUUID: "mamoa_wearos_workout_\(start.timeIntervalSince1970)"]
        )
        
        try await healthStore.save(workout)
        
        // Asociar muestras al workout
        try await healthStore.addSamples(samples, to: workout)
    }
    
    // MARK: - Batch Write
    
    /// Escribe multiples muestras en batch
    func writeBatch(_ samples: [HKSample]) async throws {
        try await healthStore.save(samples)
    }
    
    // MARK: - Check for Duplicates
    
    /// Verifica si ya existe un registro con el mismo external UUID
    func sampleExists(type: HKSampleType, externalUUID: String) async -> Bool {
        let predicate = HKQuery.predicateForObjects(withMetadataKey: HKMetadataKeyExternalUUID, operatorType: .equalTo, value: externalUUID)
        
        return await withCheckedContinuation { continuation in
            let query = HKSampleQuery(sampleType: type, predicate: predicate, limit: 1, sortDescriptors: nil) { _, samples, _ in
                continuation.resume(returning: (samples?.count ?? 0) > 0)
            }
            healthStore.execute(query)
        }
    }
}

// MARK: - Workout Type Mapping

extension HealthKitManager {
    static func workoutType(from string: String) -> HKWorkoutActivityType {
        switch string.lowercased() {
        case "running", "run": return .running
        case "walking", "walk": return .walking
        case "cycling", "bike": return .cycling
        case "swimming", "swim": return .swimming
        case "yoga": return .yoga
        case "strength", "weights": return .traditionalStrengthTraining
        case "hiit": return .highIntensityIntervalTraining
        case "elliptical": return .elliptical
        case "rowing": return .rowing
        case "stair_climbing", "stairs": return .stairClimbing
        default: return .other
        }
    }
}

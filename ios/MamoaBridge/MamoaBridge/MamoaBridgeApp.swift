import SwiftUI
import HealthKit
import BackgroundTasks

@main
struct MamoaBridgeApp: App {
    @StateObject private var healthManager = HealthKitManager.shared
    @StateObject private var syncManager = SyncManager.shared
    
    init() {
        // Registrar tareas de background
        BGTaskScheduler.shared.register(
            forTaskWithIdentifier: "com.mamoa.bridge.healthsync",
            using: nil
        ) { task in
            self.handleBackgroundSync(task: task as! BGAppRefreshTask)
        }
    }
    
    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(healthManager)
                .environmentObject(syncManager)
                .onAppear {
                    healthManager.requestAuthorization()
                    scheduleBackgroundSync()
                }
        }
    }
    
    private func handleBackgroundSync(task: BGAppRefreshTask) {
        scheduleBackgroundSync()
        
        let syncTask = Task {
            do {
                try await syncManager.syncHealthData()
                task.setTaskCompleted(success: true)
            } catch {
                task.setTaskCompleted(success: false)
            }
        }
        
        task.expirationHandler = {
            syncTask.cancel()
        }
    }
    
    private func scheduleBackgroundSync() {
        let request = BGAppRefreshTaskRequest(identifier: "com.mamoa.bridge.healthsync")
        request.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60) // 15 minutos
        
        do {
            try BGTaskScheduler.shared.submit(request)
        } catch {
            print("Error scheduling background sync: \(error)")
        }
    }
}

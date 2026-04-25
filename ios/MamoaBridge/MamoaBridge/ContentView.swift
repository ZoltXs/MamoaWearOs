import SwiftUI
import HealthKit

struct ContentView: View {
    @EnvironmentObject var healthManager: HealthKitManager
    @EnvironmentObject var syncManager: SyncManager
    @State private var showingLinkSheet = false
    @State private var linkCode = ""
    
    var body: some View {
        NavigationView {
            ScrollView {
                VStack(spacing: 24) {
                    // Estado de conexion
                    ConnectionStatusCard()
                    
                    // Resumen de salud de hoy
                    TodayHealthSummary()
                    
                    // Ultima sincronizacion
                    SyncStatusCard()
                    
                    // Acciones
                    ActionsSection(showingLinkSheet: $showingLinkSheet)
                }
                .padding()
            }
            .navigationTitle("Mamoa Bridge")
            .sheet(isPresented: $showingLinkSheet) {
                LinkDeviceSheet(linkCode: $linkCode)
            }
            .refreshable {
                await syncManager.syncHealthData()
            }
        }
    }
}

// MARK: - Subviews

struct ConnectionStatusCard: View {
    @EnvironmentObject var syncManager: SyncManager
    
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Circle()
                    .fill(syncManager.isLinked ? Color.green : Color.orange)
                    .frame(width: 12, height: 12)
                
                Text(syncManager.isLinked ? "WearOS Vinculado" : "Sin Vincular")
                    .font(.headline)
                
                Spacer()
                
                if syncManager.isLinked {
                    Image(systemName: "applewatch")
                        .foregroundColor(.blue)
                }
            }
            
            if let deviceId = syncManager.linkedDeviceId {
                Text("ID: \(deviceId)")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
        }
        .padding()
        .background(Color(.systemGray6))
        .cornerRadius(12)
    }
}

struct TodayHealthSummary: View {
    @EnvironmentObject var syncManager: SyncManager
    
    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Hoy")
                .font(.headline)
            
            LazyVGrid(columns: [
                GridItem(.flexible()),
                GridItem(.flexible())
            ], spacing: 16) {
                HealthMetricCard(
                    icon: "figure.walk",
                    title: "Pasos",
                    value: "\(syncManager.todaySteps)",
                    color: .green
                )
                
                HealthMetricCard(
                    icon: "heart.fill",
                    title: "FC Promedio",
                    value: syncManager.averageHeartRate > 0 ? "\(Int(syncManager.averageHeartRate)) bpm" : "--",
                    color: .red
                )
                
                HealthMetricCard(
                    icon: "flame.fill",
                    title: "Calorias",
                    value: "\(Int(syncManager.todayCalories)) kcal",
                    color: .orange
                )
                
                HealthMetricCard(
                    icon: "figure.run",
                    title: "Distancia",
                    value: String(format: "%.1f km", syncManager.todayDistance / 1000),
                    color: .blue
                )
            }
        }
        .padding()
        .background(Color(.systemGray6))
        .cornerRadius(12)
    }
}

struct HealthMetricCard: View {
    let icon: String
    let title: String
    let value: String
    let color: Color
    
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Image(systemName: icon)
                    .foregroundColor(color)
                Spacer()
            }
            
            Text(value)
                .font(.title2)
                .fontWeight(.semibold)
            
            Text(title)
                .font(.caption)
                .foregroundColor(.secondary)
        }
        .padding()
        .background(Color(.systemBackground))
        .cornerRadius(8)
    }
}

struct SyncStatusCard: View {
    @EnvironmentObject var syncManager: SyncManager
    
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("Sincronizacion")
                    .font(.headline)
                
                Spacer()
                
                if syncManager.isSyncing {
                    ProgressView()
                        .scaleEffect(0.8)
                }
            }
            
            HStack {
                Image(systemName: "arrow.triangle.2.circlepath")
                    .foregroundColor(.blue)
                
                if let lastSync = syncManager.lastSyncDate {
                    Text("Ultima: \(lastSync, style: .relative)")
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                } else {
                    Text("Nunca sincronizado")
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                }
            }
            
            if let error = syncManager.lastError {
                HStack {
                    Image(systemName: "exclamationmark.triangle")
                        .foregroundColor(.orange)
                    Text(error)
                        .font(.caption)
                        .foregroundColor(.orange)
                }
            }
        }
        .padding()
        .background(Color(.systemGray6))
        .cornerRadius(12)
    }
}

struct ActionsSection: View {
    @EnvironmentObject var syncManager: SyncManager
    @Binding var showingLinkSheet: Bool
    
    var body: some View {
        VStack(spacing: 12) {
            Button(action: {
                Task {
                    await syncManager.syncHealthData()
                }
            }) {
                HStack {
                    Image(systemName: "arrow.triangle.2.circlepath")
                    Text("Sincronizar Ahora")
                }
                .frame(maxWidth: .infinity)
                .padding()
                .background(Color.blue)
                .foregroundColor(.white)
                .cornerRadius(12)
            }
            .disabled(syncManager.isSyncing)
            
            Button(action: {
                showingLinkSheet = true
            }) {
                HStack {
                    Image(systemName: "link")
                    Text(syncManager.isLinked ? "Cambiar Dispositivo" : "Vincular WearOS")
                }
                .frame(maxWidth: .infinity)
                .padding()
                .background(Color(.systemGray5))
                .foregroundColor(.primary)
                .cornerRadius(12)
            }
        }
    }
}

struct LinkDeviceSheet: View {
    @EnvironmentObject var syncManager: SyncManager
    @Binding var linkCode: String
    @Environment(\.dismiss) var dismiss
    @State private var isLinking = false
    @State private var errorMessage: String?
    
    var body: some View {
        NavigationView {
            VStack(spacing: 24) {
                Image(systemName: "applewatch.and.arrow.forward")
                    .font(.system(size: 60))
                    .foregroundColor(.blue)
                
                Text("Vincular Samsung Watch")
                    .font(.title2)
                    .fontWeight(.semibold)
                
                Text("Ingresa el codigo de 6 digitos que aparece en tu reloj Samsung con Mamoa instalado.")
                    .multilineTextAlignment(.center)
                    .foregroundColor(.secondary)
                
                TextField("Codigo", text: $linkCode)
                    .textFieldStyle(.roundedBorder)
                    .font(.title)
                    .multilineTextAlignment(.center)
                    .autocapitalization(.allCharacters)
                    .disableAutocorrection(true)
                
                if let error = errorMessage {
                    Text(error)
                        .foregroundColor(.red)
                        .font(.caption)
                }
                
                Button(action: {
                    Task {
                        await linkDevice()
                    }
                }) {
                    HStack {
                        if isLinking {
                            ProgressView()
                                .tint(.white)
                        }
                        Text("Vincular")
                    }
                    .frame(maxWidth: .infinity)
                    .padding()
                    .background(linkCode.count == 6 ? Color.blue : Color.gray)
                    .foregroundColor(.white)
                    .cornerRadius(12)
                }
                .disabled(linkCode.count != 6 || isLinking)
                
                Spacer()
            }
            .padding()
            .navigationTitle("Vincular Dispositivo")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancelar") {
                        dismiss()
                    }
                }
            }
        }
    }
    
    private func linkDevice() async {
        isLinking = true
        errorMessage = nil
        
        do {
            try await syncManager.linkDevice(code: linkCode)
            dismiss()
        } catch {
            errorMessage = "Error vinculando: \(error.localizedDescription)"
        }
        
        isLinking = false
    }
}

#Preview {
    ContentView()
        .environmentObject(HealthKitManager.shared)
        .environmentObject(SyncManager.shared)
}

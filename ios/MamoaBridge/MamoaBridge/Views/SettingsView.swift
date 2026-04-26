import SwiftUI

struct SettingsView: View {
    @EnvironmentObject var syncManager: SyncManager
    @EnvironmentObject var healthManager: HealthKitManager
    @Environment(\.dismiss) var dismiss
    @AppStorage("syncInterval") private var syncInterval = 15
    @AppStorage("hasCompletedOnboarding") private var hasCompletedOnboarding = true
    @State private var showingUnlinkAlert = false
    @State private var showingResetAlert = false
    
    var body: some View {
        NavigationView {
            List {
                // Seccion de dispositivo
                Section {
                    if syncManager.isLinked {
                        HStack {
                            Image(systemName: "applewatch")
                                .font(.title2)
                                .foregroundColor(.blue)
                                .frame(width: 40)
                            
                            VStack(alignment: .leading, spacing: 4) {
                                Text("Samsung Watch")
                                    .font(.headline)
                                if let deviceId = syncManager.linkedDeviceId {
                                    Text("ID: \(deviceId)")
                                        .font(.caption)
                                        .foregroundColor(.secondary)
                                }
                            }
                            
                            Spacer()
                            
                            Circle()
                                .fill(Color.green)
                                .frame(width: 10, height: 10)
                        }
                        
                        Button(role: .destructive) {
                            showingUnlinkAlert = true
                        } label: {
                            HStack {
                                Image(systemName: "link.badge.minus")
                                Text("Desvincular Dispositivo")
                            }
                        }
                    } else {
                        HStack {
                            Image(systemName: "applewatch.slash")
                                .font(.title2)
                                .foregroundColor(.gray)
                                .frame(width: 40)
                            
                            Text("Sin dispositivo vinculado")
                                .foregroundColor(.secondary)
                        }
                        
                        NavigationLink {
                            LinkDeviceSettingsView()
                        } label: {
                            HStack {
                                Image(systemName: "link.badge.plus")
                                Text("Vincular Dispositivo")
                            }
                        }
                    }
                } header: {
                    Text("Dispositivo")
                }
                
                // Seccion de sincronizacion
                Section {
                    Picker("Intervalo de Sincronizacion", selection: $syncInterval) {
                        Text("15 minutos").tag(15)
                        Text("30 minutos").tag(30)
                        Text("1 hora").tag(60)
                        Text("2 horas").tag(120)
                    }
                    
                    HStack {
                        Image(systemName: "clock.arrow.circlepath")
                            .foregroundColor(.blue)
                        Text("Ultima sincronizacion")
                        Spacer()
                        if let lastSync = syncManager.lastSyncDate {
                            Text(lastSync, style: .relative)
                                .foregroundColor(.secondary)
                        } else {
                            Text("Nunca")
                                .foregroundColor(.secondary)
                        }
                    }
                    
                    Button {
                        Task {
                            await syncManager.syncHealthData()
                        }
                    } label: {
                        HStack {
                            Image(systemName: "arrow.triangle.2.circlepath")
                            Text("Sincronizar Ahora")
                            
                            if syncManager.isSyncing {
                                Spacer()
                                ProgressView()
                            }
                        }
                    }
                    .disabled(syncManager.isSyncing || !syncManager.isLinked)
                } header: {
                    Text("Sincronizacion")
                } footer: {
                    Text("Los datos se sincronizan automaticamente en segundo plano")
                }
                
                // Seccion de datos de salud
                Section {
                    HealthTypeToggle(type: "Pasos", icon: "figure.walk", color: .green)
                    HealthTypeToggle(type: "Frecuencia Cardiaca", icon: "heart.fill", color: .red)
                    HealthTypeToggle(type: "Calorias", icon: "flame.fill", color: .orange)
                    HealthTypeToggle(type: "Distancia", icon: "figure.run", color: .blue)
                    HealthTypeToggle(type: "Sueno", icon: "bed.double.fill", color: .purple)
                    HealthTypeToggle(type: "Entrenamientos", icon: "figure.strengthtraining.traditional", color: .green)
                } header: {
                    Text("Datos de Salud")
                } footer: {
                    Text("Elige que datos sincronizar a Apple Health")
                }
                
                // Seccion de informacion
                Section {
                    HStack {
                        Text("Version")
                        Spacer()
                        Text("1.0.0")
                            .foregroundColor(.secondary)
                    }
                    
                    Link(destination: URL(string: "https://mamoa.app/privacy")!) {
                        HStack {
                            Text("Politica de Privacidad")
                            Spacer()
                            Image(systemName: "arrow.up.right.square")
                                .foregroundColor(.secondary)
                        }
                    }
                    
                    Link(destination: URL(string: "https://mamoa.app/terms")!) {
                        HStack {
                            Text("Terminos de Uso")
                            Spacer()
                            Image(systemName: "arrow.up.right.square")
                                .foregroundColor(.secondary)
                        }
                    }
                } header: {
                    Text("Informacion")
                }
                
                // Seccion de debug/reset
                Section {
                    Button(role: .destructive) {
                        showingResetAlert = true
                    } label: {
                        HStack {
                            Image(systemName: "arrow.counterclockwise")
                            Text("Restablecer App")
                        }
                    }
                } footer: {
                    Text("Esto eliminara todos los datos locales y te pedira configurar la app nuevamente")
                }
            }
            .navigationTitle("Ajustes")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Listo") {
                        dismiss()
                    }
                }
            }
            .alert("Desvincular Dispositivo", isPresented: $showingUnlinkAlert) {
                Button("Cancelar", role: .cancel) { }
                Button("Desvincular", role: .destructive) {
                    syncManager.unlinkDevice()
                }
            } message: {
                Text("Ya no recibiras datos de salud de este dispositivo. Puedes vincularlo de nuevo en cualquier momento.")
            }
            .alert("Restablecer App", isPresented: $showingResetAlert) {
                Button("Cancelar", role: .cancel) { }
                Button("Restablecer", role: .destructive) {
                    resetApp()
                }
            } message: {
                Text("Se eliminaran todos los datos locales. Los datos ya sincronizados en Apple Health no se veran afectados.")
            }
        }
    }
    
    private func resetApp() {
        syncManager.unlinkDevice()
        hasCompletedOnboarding = false
        UserDefaults.standard.removeObject(forKey: "syncInterval")
    }
}

struct HealthTypeToggle: View {
    let type: String
    let icon: String
    let color: Color
    @State private var isEnabled = true
    
    var body: some View {
        Toggle(isOn: $isEnabled) {
            HStack(spacing: 12) {
                Image(systemName: icon)
                    .foregroundColor(color)
                    .frame(width: 24)
                Text(type)
            }
        }
    }
}

struct LinkDeviceSettingsView: View {
    @EnvironmentObject var syncManager: SyncManager
    @Environment(\.dismiss) var dismiss
    @State private var linkCode = ""
    @State private var isLinking = false
    @State private var errorMessage: String?
    
    var body: some View {
        VStack(spacing: 24) {
            Image(systemName: "applewatch.and.arrow.forward")
                .font(.system(size: 60))
                .foregroundColor(.blue)
            
            Text("Vincular Samsung Watch")
                .font(.title2)
                .fontWeight(.semibold)
            
            Text("Abre Mamoa en tu Samsung Watch e introduce el codigo de 6 digitos")
                .multilineTextAlignment(.center)
                .foregroundColor(.secondary)
                .padding(.horizontal)
            
            TextField("CODIGO", text: $linkCode)
                .font(.system(size: 28, weight: .bold, design: .monospaced))
                .multilineTextAlignment(.center)
                .textFieldStyle(.roundedBorder)
                .autocapitalization(.allCharacters)
                .disableAutocorrection(true)
                .padding(.horizontal, 60)
                .onChange(of: linkCode) { _, newValue in
                    linkCode = String(newValue.prefix(6)).uppercased()
                }
            
            if let error = errorMessage {
                Text(error)
                    .foregroundColor(.red)
                    .font(.caption)
            }
            
            Button {
                Task {
                    await linkDevice()
                }
            } label: {
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
            .padding(.horizontal)
            
            Spacer()
        }
        .padding(.top, 40)
        .navigationTitle("Vincular")
        .navigationBarTitleDisplayMode(.inline)
    }
    
    private func linkDevice() async {
        isLinking = true
        errorMessage = nil
        
        do {
            try await syncManager.linkDevice(code: linkCode)
            dismiss()
        } catch {
            errorMessage = "Error: \(error.localizedDescription)"
        }
        
        isLinking = false
    }
}

#Preview {
    SettingsView()
        .environmentObject(SyncManager.shared)
        .environmentObject(HealthKitManager.shared)
}

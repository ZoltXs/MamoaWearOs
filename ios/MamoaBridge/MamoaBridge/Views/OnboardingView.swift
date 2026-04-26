import SwiftUI

struct OnboardingView: View {
    @EnvironmentObject var healthManager: HealthKitManager
    @EnvironmentObject var syncManager: SyncManager
    @AppStorage("hasCompletedOnboarding") private var hasCompletedOnboarding = false
    @State private var currentPage = 0
    
    var body: some View {
        ZStack {
            LinearGradient(
                colors: [Color(hex: "1a1a2e"), Color(hex: "16213e")],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            .ignoresSafeArea()
            
            VStack(spacing: 0) {
                TabView(selection: $currentPage) {
                    WelcomePage()
                        .tag(0)
                    
                    HealthPermissionPage()
                        .tag(1)
                    
                    LinkDevicePage()
                        .tag(2)
                    
                    ReadyPage()
                        .tag(3)
                }
                .tabViewStyle(.page(indexDisplayMode: .never))
                
                // Custom page indicator
                HStack(spacing: 8) {
                    ForEach(0..<4) { index in
                        Circle()
                            .fill(currentPage == index ? Color.white : Color.white.opacity(0.3))
                            .frame(width: 8, height: 8)
                    }
                }
                .padding(.bottom, 20)
                
                // Navigation buttons
                HStack {
                    if currentPage > 0 {
                        Button(action: {
                            withAnimation {
                                currentPage -= 1
                            }
                        }) {
                            Text("Atras")
                                .foregroundColor(.white.opacity(0.7))
                                .padding(.horizontal, 30)
                                .padding(.vertical, 14)
                        }
                    }
                    
                    Spacer()
                    
                    Button(action: {
                        handleNextAction()
                    }) {
                        Text(currentPage == 3 ? "Comenzar" : "Siguiente")
                            .fontWeight(.semibold)
                            .foregroundColor(.white)
                            .padding(.horizontal, 30)
                            .padding(.vertical, 14)
                            .background(Color(hex: "3B72FB"))
                            .cornerRadius(25)
                    }
                }
                .padding(.horizontal, 24)
                .padding(.bottom, 40)
            }
        }
    }
    
    private func handleNextAction() {
        switch currentPage {
        case 1:
            healthManager.requestAuthorization()
            withAnimation {
                currentPage += 1
            }
        case 3:
            hasCompletedOnboarding = true
        default:
            withAnimation {
                currentPage += 1
            }
        }
    }
}

// MARK: - Pages

struct WelcomePage: View {
    var body: some View {
        VStack(spacing: 24) {
            Spacer()
            
            Image(systemName: "applewatch.and.arrow.forward")
                .font(.system(size: 80))
                .foregroundStyle(
                    LinearGradient(
                        colors: [Color(hex: "3B72FB"), Color(hex: "00D4FF")],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    )
                )
            
            Text("Mamoa Bridge")
                .font(.system(size: 36, weight: .bold))
                .foregroundColor(.white)
            
            Text("Sincroniza los datos de salud de tu Samsung Watch con Apple Health de forma automatica")
                .font(.body)
                .multilineTextAlignment(.center)
                .foregroundColor(.white.opacity(0.7))
                .padding(.horizontal, 40)
            
            Spacer()
            Spacer()
        }
    }
}

struct HealthPermissionPage: View {
    var body: some View {
        VStack(spacing: 24) {
            Spacer()
            
            Image(systemName: "heart.text.square.fill")
                .font(.system(size: 80))
                .foregroundColor(.red)
            
            Text("Acceso a Salud")
                .font(.system(size: 28, weight: .bold))
                .foregroundColor(.white)
            
            Text("Necesitamos acceso a Apple Health para guardar tus datos de actividad, frecuencia cardiaca, sueno y mas.")
                .font(.body)
                .multilineTextAlignment(.center)
                .foregroundColor(.white.opacity(0.7))
                .padding(.horizontal, 40)
            
            VStack(alignment: .leading, spacing: 16) {
                PermissionRow(icon: "figure.walk", text: "Pasos y distancia", color: .green)
                PermissionRow(icon: "heart.fill", text: "Frecuencia cardiaca", color: .red)
                PermissionRow(icon: "flame.fill", text: "Calorias activas", color: .orange)
                PermissionRow(icon: "bed.double.fill", text: "Datos de sueno", color: .purple)
            }
            .padding(.horizontal, 40)
            .padding(.top, 20)
            
            Spacer()
            Spacer()
        }
    }
}

struct PermissionRow: View {
    let icon: String
    let text: String
    let color: Color
    
    var body: some View {
        HStack(spacing: 16) {
            Image(systemName: icon)
                .font(.title2)
                .foregroundColor(color)
                .frame(width: 30)
            
            Text(text)
                .foregroundColor(.white.opacity(0.9))
            
            Spacer()
            
            Image(systemName: "checkmark.circle.fill")
                .foregroundColor(.green)
        }
    }
}

struct LinkDevicePage: View {
    @EnvironmentObject var syncManager: SyncManager
    @State private var linkCode = ""
    @State private var isLinking = false
    @State private var errorMessage: String?
    
    var body: some View {
        VStack(spacing: 24) {
            Spacer()
            
            Image(systemName: "link.circle.fill")
                .font(.system(size: 80))
                .foregroundStyle(
                    LinearGradient(
                        colors: [Color(hex: "3B72FB"), Color(hex: "00D4FF")],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    )
                )
            
            Text("Vincula tu Watch")
                .font(.system(size: 28, weight: .bold))
                .foregroundColor(.white)
            
            Text("Abre Mamoa en tu Samsung Watch y introduce el codigo de 6 digitos que aparece en pantalla")
                .font(.body)
                .multilineTextAlignment(.center)
                .foregroundColor(.white.opacity(0.7))
                .padding(.horizontal, 40)
            
            TextField("", text: $linkCode)
                .placeholder(when: linkCode.isEmpty) {
                    Text("CODIGO")
                        .foregroundColor(.white.opacity(0.3))
                }
                .font(.system(size: 32, weight: .bold, design: .monospaced))
                .foregroundColor(.white)
                .multilineTextAlignment(.center)
                .keyboardType(.asciiCapable)
                .autocapitalization(.allCharacters)
                .disableAutocorrection(true)
                .padding()
                .background(Color.white.opacity(0.1))
                .cornerRadius(16)
                .padding(.horizontal, 60)
                .onChange(of: linkCode) { _, newValue in
                    linkCode = String(newValue.prefix(6)).uppercased()
                }
            
            if let error = errorMessage {
                Text(error)
                    .foregroundColor(.red)
                    .font(.caption)
            }
            
            if linkCode.count == 6 {
                Button(action: {
                    Task {
                        isLinking = true
                        errorMessage = nil
                        do {
                            try await syncManager.linkDevice(code: linkCode)
                        } catch {
                            errorMessage = "Error: \(error.localizedDescription)"
                        }
                        isLinking = false
                    }
                }) {
                    HStack {
                        if isLinking {
                            ProgressView()
                                .tint(.white)
                        }
                        Text("Vincular Ahora")
                    }
                    .font(.headline)
                    .foregroundColor(.white)
                    .padding(.horizontal, 40)
                    .padding(.vertical, 14)
                    .background(Color(hex: "3B72FB"))
                    .cornerRadius(25)
                }
                .disabled(isLinking)
            }
            
            Text("Puedes saltar este paso y vincularlo despues")
                .font(.caption)
                .foregroundColor(.white.opacity(0.5))
            
            Spacer()
            Spacer()
        }
    }
}

struct ReadyPage: View {
    var body: some View {
        VStack(spacing: 24) {
            Spacer()
            
            Image(systemName: "checkmark.seal.fill")
                .font(.system(size: 80))
                .foregroundColor(.green)
            
            Text("Todo Listo")
                .font(.system(size: 28, weight: .bold))
                .foregroundColor(.white)
            
            Text("Mamoa Bridge sincronizara automaticamente los datos de salud de tu Samsung Watch cada 15 minutos")
                .font(.body)
                .multilineTextAlignment(.center)
                .foregroundColor(.white.opacity(0.7))
                .padding(.horizontal, 40)
            
            VStack(alignment: .leading, spacing: 16) {
                FeatureRow(icon: "arrow.triangle.2.circlepath", text: "Sincronizacion automatica en segundo plano")
                FeatureRow(icon: "icloud.fill", text: "Datos seguros via servidor")
                FeatureRow(icon: "heart.fill", text: "Compatible con Apple Health")
            }
            .padding(.horizontal, 40)
            .padding(.top, 20)
            
            Spacer()
            Spacer()
        }
    }
}

struct FeatureRow: View {
    let icon: String
    let text: String
    
    var body: some View {
        HStack(spacing: 16) {
            Image(systemName: icon)
                .font(.title3)
                .foregroundColor(Color(hex: "3B72FB"))
                .frame(width: 30)
            
            Text(text)
                .foregroundColor(.white.opacity(0.9))
                .font(.subheadline)
            
            Spacer()
        }
    }
}

// MARK: - Helper Extensions

extension View {
    func placeholder<Content: View>(
        when shouldShow: Bool,
        alignment: Alignment = .center,
        @ViewBuilder placeholder: () -> Content
    ) -> some View {
        ZStack(alignment: alignment) {
            placeholder().opacity(shouldShow ? 1 : 0)
            self
        }
    }
}

extension Color {
    init(hex: String) {
        let hex = hex.trimmingCharacters(in: CharacterSet.alphanumerics.inverted)
        var int: UInt64 = 0
        Scanner(string: hex).scanHexInt64(&int)
        let a, r, g, b: UInt64
        switch hex.count {
        case 3:
            (a, r, g, b) = (255, (int >> 8) * 17, (int >> 4 & 0xF) * 17, (int & 0xF) * 17)
        case 6:
            (a, r, g, b) = (255, int >> 16, int >> 8 & 0xFF, int & 0xFF)
        case 8:
            (a, r, g, b) = (int >> 24, int >> 16 & 0xFF, int >> 8 & 0xFF, int & 0xFF)
        default:
            (a, r, g, b) = (255, 0, 0, 0)
        }
        self.init(
            .sRGB,
            red: Double(r) / 255,
            green: Double(g) / 255,
            blue: Double(b) / 255,
            opacity: Double(a) / 255
        )
    }
}

#Preview {
    OnboardingView()
        .environmentObject(HealthKitManager.shared)
        .environmentObject(SyncManager.shared)
}

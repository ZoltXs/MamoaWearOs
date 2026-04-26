import SwiftUI

// MARK: - Gradient Button

struct GradientButton: View {
    let title: String
    let icon: String?
    let colors: [Color]
    let action: () -> Void
    
    init(
        title: String,
        icon: String? = nil,
        colors: [Color] = [Color(hex: "3B72FB"), Color(hex: "00D4FF")],
        action: @escaping () -> Void
    ) {
        self.title = title
        self.icon = icon
        self.colors = colors
        self.action = action
    }
    
    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                if let icon = icon {
                    Image(systemName: icon)
                }
                Text(title)
                    .fontWeight(.semibold)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 16)
            .background(
                LinearGradient(
                    colors: colors,
                    startPoint: .leading,
                    endPoint: .trailing
                )
            )
            .foregroundColor(.white)
            .cornerRadius(14)
        }
    }
}

// MARK: - Glass Card

struct GlassCard<Content: View>: View {
    let content: Content
    
    init(@ViewBuilder content: () -> Content) {
        self.content = content()
    }
    
    var body: some View {
        content
            .padding()
            .background(.ultraThinMaterial)
            .cornerRadius(20)
            .overlay(
                RoundedRectangle(cornerRadius: 20)
                    .stroke(Color.white.opacity(0.2), lineWidth: 1)
            )
    }
}

// MARK: - Status Badge

struct StatusBadge: View {
    let isActive: Bool
    let activeText: String
    let inactiveText: String
    
    var body: some View {
        HStack(spacing: 6) {
            Circle()
                .fill(isActive ? Color.green : Color.orange)
                .frame(width: 8, height: 8)
            
            Text(isActive ? activeText : inactiveText)
                .font(.caption)
                .fontWeight(.medium)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .background(
            Capsule()
                .fill((isActive ? Color.green : Color.orange).opacity(0.15))
        )
    }
}

// MARK: - Animated Ring

struct AnimatedRing: View {
    let progress: Double
    let color: Color
    let lineWidth: CGFloat
    
    @State private var animatedProgress: Double = 0
    
    var body: some View {
        ZStack {
            Circle()
                .stroke(color.opacity(0.2), lineWidth: lineWidth)
            
            Circle()
                .trim(from: 0, to: animatedProgress)
                .stroke(
                    color,
                    style: StrokeStyle(lineWidth: lineWidth, lineCap: .round)
                )
                .rotationEffect(.degrees(-90))
        }
        .onAppear {
            withAnimation(.easeOut(duration: 1.0)) {
                animatedProgress = progress
            }
        }
        .onChange(of: progress) { _, newValue in
            withAnimation(.easeOut(duration: 0.5)) {
                animatedProgress = newValue
            }
        }
    }
}

// MARK: - Loading View

struct LoadingView: View {
    let message: String
    
    var body: some View {
        VStack(spacing: 20) {
            ProgressView()
                .scaleEffect(1.5)
            
            Text(message)
                .font(.subheadline)
                .foregroundColor(.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(.systemBackground))
    }
}

// MARK: - Empty State View

struct EmptyStateView: View {
    let icon: String
    let title: String
    let message: String
    let actionTitle: String?
    let action: (() -> Void)?
    
    init(
        icon: String,
        title: String,
        message: String,
        actionTitle: String? = nil,
        action: (() -> Void)? = nil
    ) {
        self.icon = icon
        self.title = title
        self.message = message
        self.actionTitle = actionTitle
        self.action = action
    }
    
    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: icon)
                .font(.system(size: 60))
                .foregroundColor(.secondary)
            
            Text(title)
                .font(.title2)
                .fontWeight(.semibold)
            
            Text(message)
                .font(.body)
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 40)
            
            if let actionTitle = actionTitle, let action = action {
                Button(action: action) {
                    Text(actionTitle)
                        .fontWeight(.semibold)
                        .padding(.horizontal, 24)
                        .padding(.vertical, 12)
                        .background(Color.blue)
                        .foregroundColor(.white)
                        .cornerRadius(10)
                }
                .padding(.top, 8)
            }
        }
        .padding()
    }
}

// MARK: - Metric Tile

struct MetricTile: View {
    let icon: String
    let title: String
    let value: String
    let unit: String
    let color: Color
    let trend: Double?
    
    init(
        icon: String,
        title: String,
        value: String,
        unit: String = "",
        color: Color,
        trend: Double? = nil
    ) {
        self.icon = icon
        self.title = title
        self.value = value
        self.unit = unit
        self.color = color
        self.trend = trend
    }
    
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Image(systemName: icon)
                    .font(.title3)
                    .foregroundColor(color)
                
                Spacer()
                
                if let trend = trend {
                    HStack(spacing: 2) {
                        Image(systemName: trend >= 0 ? "arrow.up.right" : "arrow.down.right")
                            .font(.caption2)
                        Text("\(abs(Int(trend)))%")
                            .font(.caption2)
                    }
                    .foregroundColor(trend >= 0 ? .green : .red)
                }
            }
            
            HStack(alignment: .firstTextBaseline, spacing: 4) {
                Text(value)
                    .font(.system(size: 28, weight: .bold))
                
                Text(unit)
                    .font(.subheadline)
                    .foregroundColor(.secondary)
            }
            
            Text(title)
                .font(.caption)
                .foregroundColor(.secondary)
        }
        .padding()
        .background(Color(.secondarySystemBackground))
        .cornerRadius(16)
    }
}

// MARK: - Pulse Animation

struct PulseAnimation: ViewModifier {
    @State private var isPulsing = false
    
    func body(content: Content) -> some View {
        content
            .scaleEffect(isPulsing ? 1.05 : 1.0)
            .opacity(isPulsing ? 0.8 : 1.0)
            .animation(
                Animation.easeInOut(duration: 1.0).repeatForever(autoreverses: true),
                value: isPulsing
            )
            .onAppear {
                isPulsing = true
            }
    }
}

extension View {
    func pulse() -> some View {
        modifier(PulseAnimation())
    }
}

// MARK: - Shake Animation

struct ShakeEffect: GeometryEffect {
    var amount: CGFloat = 10
    var shakesPerUnit = 3
    var animatableData: CGFloat
    
    func effectValue(size: CGSize) -> ProjectionTransform {
        ProjectionTransform(CGAffineTransform(translationX:
            amount * sin(animatableData * .pi * CGFloat(shakesPerUnit)),
            y: 0))
    }
}

// MARK: - Previews

#Preview("Gradient Button") {
    VStack(spacing: 20) {
        GradientButton(title: "Sincronizar", icon: "arrow.triangle.2.circlepath") { }
        GradientButton(title: "Vincular", colors: [.purple, .pink]) { }
    }
    .padding()
}

#Preview("Status Badge") {
    VStack(spacing: 20) {
        StatusBadge(isActive: true, activeText: "Conectado", inactiveText: "Desconectado")
        StatusBadge(isActive: false, activeText: "Conectado", inactiveText: "Desconectado")
    }
}

#Preview("Metric Tile") {
    LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())]) {
        MetricTile(icon: "figure.walk", title: "Pasos", value: "8,234", color: .green, trend: 12)
        MetricTile(icon: "heart.fill", title: "FC Media", value: "72", unit: "bpm", color: .red, trend: -5)
    }
    .padding()
}

#Preview("Empty State") {
    EmptyStateView(
        icon: "applewatch.slash",
        title: "Sin Dispositivo",
        message: "Vincula tu Samsung Watch para comenzar a sincronizar datos de salud",
        actionTitle: "Vincular",
        action: { }
    )
}

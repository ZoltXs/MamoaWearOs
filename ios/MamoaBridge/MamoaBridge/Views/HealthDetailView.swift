import SwiftUI
import Charts

struct HealthDetailView: View {
    @EnvironmentObject var syncManager: SyncManager
    @State private var selectedPeriod: TimePeriod = .week
    
    enum TimePeriod: String, CaseIterable {
        case day = "Dia"
        case week = "Semana"
        case month = "Mes"
    }
    
    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                // Selector de periodo
                Picker("Periodo", selection: $selectedPeriod) {
                    ForEach(TimePeriod.allCases, id: \.self) { period in
                        Text(period.rawValue).tag(period)
                    }
                }
                .pickerStyle(.segmented)
                .padding(.horizontal)
                
                // Tarjetas de resumen
                LazyVGrid(columns: [
                    GridItem(.flexible()),
                    GridItem(.flexible())
                ], spacing: 16) {
                    SummaryCard(
                        title: "Pasos",
                        value: "\(syncManager.todaySteps)",
                        subtitle: "de 10,000",
                        icon: "figure.walk",
                        color: .green,
                        progress: min(Double(syncManager.todaySteps) / 10000, 1.0)
                    )
                    
                    SummaryCard(
                        title: "Calorias",
                        value: "\(Int(syncManager.todayCalories))",
                        subtitle: "kcal activas",
                        icon: "flame.fill",
                        color: .orange,
                        progress: min(syncManager.todayCalories / 500, 1.0)
                    )
                    
                    SummaryCard(
                        title: "Distancia",
                        value: String(format: "%.1f", syncManager.todayDistance / 1000),
                        subtitle: "kilometros",
                        icon: "figure.run",
                        color: .blue,
                        progress: min(syncManager.todayDistance / 8000, 1.0)
                    )
                    
                    SummaryCard(
                        title: "FC Promedio",
                        value: syncManager.averageHeartRate > 0 ? "\(Int(syncManager.averageHeartRate))" : "--",
                        subtitle: "bpm",
                        icon: "heart.fill",
                        color: .red,
                        progress: syncManager.averageHeartRate > 0 ? min(syncManager.averageHeartRate / 120, 1.0) : 0
                    )
                }
                .padding(.horizontal)
                
                // Grafico de pasos
                VStack(alignment: .leading, spacing: 12) {
                    Text("Pasos esta semana")
                        .font(.headline)
                    
                    StepsChart()
                        .frame(height: 200)
                }
                .padding()
                .background(Color(.systemGray6))
                .cornerRadius(16)
                .padding(.horizontal)
                
                // Grafico de frecuencia cardiaca
                VStack(alignment: .leading, spacing: 12) {
                    Text("Frecuencia Cardiaca")
                        .font(.headline)
                    
                    HeartRateChart()
                        .frame(height: 200)
                }
                .padding()
                .background(Color(.systemGray6))
                .cornerRadius(16)
                .padding(.horizontal)
                
                // Lista de entrenamientos recientes
                VStack(alignment: .leading, spacing: 12) {
                    Text("Entrenamientos Recientes")
                        .font(.headline)
                    
                    if syncManager.recentWorkouts.isEmpty {
                        HStack {
                            Spacer()
                            VStack(spacing: 8) {
                                Image(systemName: "figure.run.circle")
                                    .font(.system(size: 40))
                                    .foregroundColor(.secondary)
                                Text("Sin entrenamientos recientes")
                                    .foregroundColor(.secondary)
                            }
                            .padding(.vertical, 30)
                            Spacer()
                        }
                    } else {
                        ForEach(syncManager.recentWorkouts) { workout in
                            WorkoutRow(workout: workout)
                        }
                    }
                }
                .padding()
                .background(Color(.systemGray6))
                .cornerRadius(16)
                .padding(.horizontal)
            }
            .padding(.vertical)
        }
        .navigationTitle("Actividad")
        .navigationBarTitleDisplayMode(.large)
    }
}

struct SummaryCard: View {
    let title: String
    let value: String
    let subtitle: String
    let icon: String
    let color: Color
    let progress: Double
    
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Image(systemName: icon)
                    .font(.title2)
                    .foregroundColor(color)
                Spacer()
            }
            
            Text(value)
                .font(.system(size: 28, weight: .bold))
            
            Text(subtitle)
                .font(.caption)
                .foregroundColor(.secondary)
            
            GeometryReader { geometry in
                ZStack(alignment: .leading) {
                    Rectangle()
                        .fill(Color(.systemGray4))
                        .frame(height: 4)
                        .cornerRadius(2)
                    
                    Rectangle()
                        .fill(color)
                        .frame(width: geometry.size.width * progress, height: 4)
                        .cornerRadius(2)
                }
            }
            .frame(height: 4)
        }
        .padding()
        .background(Color(.systemBackground))
        .cornerRadius(16)
    }
}

struct StepsChart: View {
    // Datos de ejemplo - en produccion vendrian del syncManager
    let data: [(String, Int)] = [
        ("L", 8234),
        ("M", 6123),
        ("X", 9876),
        ("J", 5432),
        ("V", 11234),
        ("S", 7654),
        ("D", 4321)
    ]
    
    var body: some View {
        Chart {
            ForEach(data, id: \.0) { day, steps in
                BarMark(
                    x: .value("Dia", day),
                    y: .value("Pasos", steps)
                )
                .foregroundStyle(
                    LinearGradient(
                        colors: [.green.opacity(0.8), .green],
                        startPoint: .bottom,
                        endPoint: .top
                    )
                )
                .cornerRadius(4)
            }
            
            RuleMark(y: .value("Meta", 10000))
                .foregroundStyle(.gray.opacity(0.5))
                .lineStyle(StrokeStyle(lineWidth: 1, dash: [5]))
        }
        .chartYAxis {
            AxisMarks(position: .leading) { value in
                if let intValue = value.as(Int.self) {
                    AxisValueLabel {
                        Text("\(intValue / 1000)k")
                            .font(.caption2)
                    }
                }
                AxisGridLine()
            }
        }
    }
}

struct HeartRateChart: View {
    // Datos de ejemplo
    let data: [(Date, Int)] = {
        var result: [(Date, Int)] = []
        let calendar = Calendar.current
        for i in 0..<24 {
            let date = calendar.date(byAdding: .hour, value: -23 + i, to: Date())!
            let hr = Int.random(in: 60...95)
            result.append((date, hr))
        }
        return result
    }()
    
    var body: some View {
        Chart {
            ForEach(data, id: \.0) { time, hr in
                LineMark(
                    x: .value("Hora", time),
                    y: .value("BPM", hr)
                )
                .foregroundStyle(.red)
                .interpolationMethod(.catmullRom)
                
                AreaMark(
                    x: .value("Hora", time),
                    y: .value("BPM", hr)
                )
                .foregroundStyle(
                    LinearGradient(
                        colors: [.red.opacity(0.3), .red.opacity(0.05)],
                        startPoint: .top,
                        endPoint: .bottom
                    )
                )
                .interpolationMethod(.catmullRom)
            }
        }
        .chartYScale(domain: 50...120)
        .chartXAxis {
            AxisMarks(values: .stride(by: .hour, count: 6)) { value in
                if let date = value.as(Date.self) {
                    AxisValueLabel {
                        Text(date, format: .dateTime.hour())
                            .font(.caption2)
                    }
                }
            }
        }
    }
}

struct WorkoutRow: View {
    let workout: WorkoutData
    
    var body: some View {
        HStack(spacing: 16) {
            Image(systemName: workout.icon)
                .font(.title2)
                .foregroundColor(.green)
                .frame(width: 44, height: 44)
                .background(Color.green.opacity(0.15))
                .cornerRadius(12)
            
            VStack(alignment: .leading, spacing: 4) {
                Text(workout.name)
                    .font(.headline)
                Text(workout.date, style: .date)
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
            
            Spacer()
            
            VStack(alignment: .trailing, spacing: 4) {
                Text(workout.durationFormatted)
                    .font(.headline)
                Text("\(Int(workout.calories)) kcal")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
        }
        .padding(.vertical, 8)
    }
}

struct WorkoutData: Identifiable {
    let id = UUID()
    let name: String
    let icon: String
    let date: Date
    let duration: TimeInterval
    let calories: Double
    
    var durationFormatted: String {
        let minutes = Int(duration / 60)
        return "\(minutes) min"
    }
}

extension SyncManager {
    var recentWorkouts: [WorkoutData] {
        // Datos de ejemplo - en produccion vendrian del servidor
        []
    }
}

#Preview {
    NavigationView {
        HealthDetailView()
            .environmentObject(SyncManager.shared)
    }
}

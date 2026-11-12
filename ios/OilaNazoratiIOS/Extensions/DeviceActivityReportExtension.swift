import SwiftUI
import DeviceActivity

/// Xcode'da alohida Device Activity Report Extension targetiga qo'shiladi.
/// App targetidagi DeviceActivityReport ushbu context nomlari bilan chaqiriladi.
struct OilaNazoratiReportExtension: DeviceActivityReportExtension {
    var body: some DeviceActivityReportScene {
        DailyUsageReport { configuration in
            UsageSummaryView(title: "Kunlik ekran vaqti", duration: configuration.totalDuration)
        }
        WeeklyUsageReport { configuration in
            UsageSummaryView(title: "Haftalik ekran vaqti", duration: configuration.totalDuration)
        }
    }
}

struct UsageReportConfiguration {
    let totalDuration: TimeInterval
}

struct DailyUsageReport<Content: View>: DeviceActivityReportScene {
    let context: DeviceActivityReport.Context = .init("OilaNazorati.Daily")
    let content: (UsageReportConfiguration) -> Content

    func makeConfiguration(
        representing data: DeviceActivityResults<DeviceActivityData>
    ) async -> UsageReportConfiguration {
        await UsageReportBuilder.configuration(from: data)
    }
}

struct WeeklyUsageReport<Content: View>: DeviceActivityReportScene {
    let context: DeviceActivityReport.Context = .init("OilaNazorati.Weekly")
    let content: (UsageReportConfiguration) -> Content

    func makeConfiguration(
        representing data: DeviceActivityResults<DeviceActivityData>
    ) async -> UsageReportConfiguration {
        await UsageReportBuilder.configuration(from: data)
    }
}

private enum UsageReportBuilder {
    static func configuration(from data: DeviceActivityResults<DeviceActivityData>) async -> UsageReportConfiguration {
        var total: TimeInterval = 0
        for await day in data {
            for await segment in day.activitySegments {
                total += segment.totalActivityDuration
            }
        }
        return UsageReportConfiguration(totalDuration: total)
    }
}

private struct UsageSummaryView: View {
    let title: String
    let duration: TimeInterval

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).font(.headline)
            Text(duration.formatted(.time(pattern: .hourMinute)))
                .font(.largeTitle.bold().monospacedDigit())
            Text("Apple DeviceActivity hisobotidan olingan jami foydalanish vaqti.")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .padding()
    }
}

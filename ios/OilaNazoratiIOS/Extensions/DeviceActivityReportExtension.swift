import SwiftUI
import DeviceActivity
import FamilyControls

/// Screen Time report extension. Apple runs this extension in a privacy sandbox:
/// usage details are rendered locally and are deliberately NOT exported to Firebase.
@main
struct OilaNazoratiReportExtension: DeviceActivityReportExtension {
    var body: some DeviceActivityReportScene {
        DailyUsageReport { configuration in
            UsageSummaryView(title: "Kunlik ekran vaqti", configuration: configuration)
        }
        WeeklyUsageReport { configuration in
            UsageSummaryView(title: "Haftalik ekran vaqti", configuration: configuration)
        }
    }
}

struct ApplicationUsageItem: Identifiable {
    let id: String
    let token: ApplicationToken
    let duration: TimeInterval
}

struct CategoryUsageItem: Identifiable {
    let id: String
    let token: ActivityCategoryToken
    let duration: TimeInterval
}

struct UsageReportConfiguration {
    let totalDuration: TimeInterval
    let applications: [ApplicationUsageItem]
    let categories: [CategoryUsageItem]
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
        var appTotals: [ApplicationToken: TimeInterval] = [:]
        var categoryTotals: [ActivityCategoryToken: TimeInterval] = [:]

        for await day in data {
            for await segment in day.activitySegments {
                total += segment.totalActivityDuration
                for await category in segment.categories {
                    if let token = category.category.token {
                        categoryTotals[token, default: 0] += category.totalActivityDuration
                    }
                    for await application in category.applications {
                        if let token = application.application.token {
                            appTotals[token, default: 0] += application.totalActivityDuration
                        }
                    }
                }
            }
        }

        let apps = appTotals
            .sorted { $0.value > $1.value }
            .prefix(8)
            .enumerated()
            .map { ApplicationUsageItem(id: "app-\($0.offset)", token: $0.element.key, duration: $0.element.value) }
        let categories = categoryTotals
            .sorted { $0.value > $1.value }
            .prefix(8)
            .enumerated()
            .map { CategoryUsageItem(id: "category-\($0.offset)", token: $0.element.key, duration: $0.element.value) }

        return UsageReportConfiguration(
            totalDuration: total,
            applications: Array(apps),
            categories: Array(categories)
        )
    }
}

private struct UsageSummaryView: View {
    let title: String
    let configuration: UsageReportConfiguration

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(title).font(.headline)
            Text(configuration.totalDuration.formatted(.time(pattern: .hourMinute)))
                .font(.largeTitle.bold().monospacedDigit())

            if !configuration.categories.isEmpty {
                Text("Toifalar").font(.subheadline.bold())
                ForEach(configuration.categories) { item in
                    HStack {
                        Label(item.token)
                        Spacer()
                        Text(item.duration.formatted(.time(pattern: .hourMinute)))
                            .font(.caption.monospacedDigit())
                    }
                }
            }

            if !configuration.applications.isEmpty {
                Text("Ilovalar").font(.subheadline.bold())
                ForEach(configuration.applications) { item in
                    HStack {
                        Label(item.token)
                        Spacer()
                        Text(item.duration.formatted(.time(pattern: .hourMinute)))
                            .font(.caption.monospacedDigit())
                    }
                }
            }
        }
        .padding()
    }
}

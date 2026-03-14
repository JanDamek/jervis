import WidgetKit
import SwiftUI

/// Three Jervis complications:
/// 1. App launcher (Jervis icon) → opens home
/// 2. Recording (mic) → starts recording immediately
/// 3. Chat (bubble) → starts listening immediately

struct JervisComplicationEntry: TimelineEntry {
    let date: Date
}

struct JervisComplicationProvider: TimelineProvider {
    func placeholder(in context: Context) -> JervisComplicationEntry {
        JervisComplicationEntry(date: Date())
    }

    func getSnapshot(in context: Context, completion: @escaping (JervisComplicationEntry) -> Void) {
        completion(JervisComplicationEntry(date: Date()))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<JervisComplicationEntry>) -> Void) {
        let entry = JervisComplicationEntry(date: Date())
        let nextUpdate = Calendar.current.date(byAdding: .hour, value: 24, to: Date())!
        completion(Timeline(entries: [entry], policy: .after(nextUpdate)))
    }
}

// MARK: - App Launcher (Jervis icon)

struct AppCircularView: View {
    var body: some View {
        ZStack {
            AccessoryWidgetBackground()
            Image("ComplicationIcon")
                .resizable()
                .scaledToFit()
                .clipShape(Circle())
                .padding(6)
                .widgetAccentable()
        }
        .widgetURL(URL(string: "jervis://home"))
    }
}

struct AppCornerView: View {
    var body: some View {
        Image("ComplicationIcon")
            .resizable()
            .scaledToFit()
            .clipShape(Circle())
            .padding(4)
            .widgetAccentable()
            .widgetURL(URL(string: "jervis://home"))
    }
}

// MARK: - Recording (mic + small Jervis badge)

struct RecordingCircularView: View {
    var body: some View {
        ZStack {
            AccessoryWidgetBackground()
            ZStack(alignment: .bottomTrailing) {
                Image(systemName: "mic.fill")
                    .font(.system(size: 20))
                    .foregroundStyle(.red)
                    .widgetAccentable()
                Image("ComplicationIcon")
                    .resizable()
                    .scaledToFit()
                    .frame(width: 12, height: 12)
                    .clipShape(Circle())
                    .offset(x: 3, y: 3)
            }
        }
        .widgetURL(URL(string: "jervis://recording"))
    }
}

struct RecordingCornerView: View {
    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            Image(systemName: "mic.fill")
                .font(.title3)
                .foregroundStyle(.red)
                .widgetAccentable()
            Image("ComplicationIcon")
                .resizable()
                .scaledToFit()
                .frame(width: 10, height: 10)
                .clipShape(Circle())
                .offset(x: 2, y: 2)
        }
        .widgetURL(URL(string: "jervis://recording"))
    }
}

// MARK: - Chat (bubble + small Jervis badge)

struct ChatCircularView: View {
    var body: some View {
        ZStack {
            AccessoryWidgetBackground()
            ZStack(alignment: .bottomTrailing) {
                Image(systemName: "bubble.left.fill")
                    .font(.system(size: 20))
                    .foregroundStyle(.blue)
                    .widgetAccentable()
                Image("ComplicationIcon")
                    .resizable()
                    .scaledToFit()
                    .frame(width: 12, height: 12)
                    .clipShape(Circle())
                    .offset(x: 3, y: 3)
            }
        }
        .widgetURL(URL(string: "jervis://chat"))
    }
}

struct ChatCornerView: View {
    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            Image(systemName: "bubble.left.fill")
                .font(.title3)
                .foregroundStyle(.blue)
                .widgetAccentable()
            Image("ComplicationIcon")
                .resizable()
                .scaledToFit()
                .frame(width: 10, height: 10)
                .clipShape(Circle())
                .offset(x: 2, y: 2)
        }
        .widgetURL(URL(string: "jervis://chat"))
    }
}

// MARK: - Inline & Rectangular shared views

struct InlineView: View {
    let title: String
    let icon: String

    var body: some View {
        Label(title, systemImage: icon)
    }
}

struct RectangularView: View {
    let title: String
    let subtitle: String
    let icon: String
    let color: Color
    let url: URL

    var body: some View {
        HStack(spacing: 6) {
            ZStack(alignment: .bottomTrailing) {
                Image(systemName: icon)
                    .font(.title2)
                    .foregroundStyle(color)
                    .widgetAccentable()
                Image("ComplicationIcon")
                    .resizable()
                    .scaledToFit()
                    .frame(width: 10, height: 10)
                    .clipShape(Circle())
                    .offset(x: 2, y: 2)
            }
            VStack(alignment: .leading) {
                Text(title)
                    .font(.headline)
                Text(subtitle)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
        }
        .widgetURL(url)
    }
}

// MARK: - Widget: Jervis App

struct JervisAppWidget: Widget {
    let kind = "JervisApp"

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: kind, provider: JervisComplicationProvider()) { _ in
            if #available(watchOS 10.0, *) {
                AppCircularView()
                    .containerBackground(.clear, for: .widget)
            } else {
                AppCircularView()
            }
        }
        .configurationDisplayName("Jervis")
        .description("Otevri Jervis")
        .supportedFamilies([
            .accessoryCircular,
            .accessoryCorner,
            .accessoryInline,
            .accessoryRectangular,
        ])
    }
}

// MARK: - Widget: Recording

struct JervisRecordingWidget: Widget {
    let kind = "JervisRecording"

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: kind, provider: JervisComplicationProvider()) { _ in
            if #available(watchOS 10.0, *) {
                RecordingCircularView()
                    .containerBackground(.clear, for: .widget)
            } else {
                RecordingCircularView()
            }
        }
        .configurationDisplayName("Nahravani")
        .description("Spusti nahravani")
        .supportedFamilies([
            .accessoryCircular,
            .accessoryCorner,
            .accessoryInline,
            .accessoryRectangular,
        ])
    }
}

// MARK: - Widget: Chat

struct JervisChatWidget: Widget {
    let kind = "JervisChat"

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: kind, provider: JervisComplicationProvider()) { _ in
            if #available(watchOS 10.0, *) {
                ChatCircularView()
                    .containerBackground(.clear, for: .widget)
            } else {
                ChatCircularView()
            }
        }
        .configurationDisplayName("Chat")
        .description("Spusti chat")
        .supportedFamilies([
            .accessoryCircular,
            .accessoryCorner,
            .accessoryInline,
            .accessoryRectangular,
        ])
    }
}

// MARK: - Bundle

@main
struct JervisWidgetBundle: WidgetBundle {
    var body: some Widget {
        JervisAppWidget()
        JervisRecordingWidget()
        JervisChatWidget()
    }
}

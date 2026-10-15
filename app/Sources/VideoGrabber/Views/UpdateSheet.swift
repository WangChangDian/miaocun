import SwiftUI

/// 版本更新卡片：主窗口冷启动检查和设置页「检查更新」共用。
/// 两个窗口各自 present 自己的那份状态（见 AppState.updateInfo / settingsUpdateInfo）。
struct UpdateSheet: View {
    let info: UpdateInfo
    let onDownload: () -> Void
    let onDismiss: () -> Void

    private var lines: [String] {
        let parsed = info.changelog
            .split(separator: "\n")
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
        return parsed.isEmpty ? ["有新版本可用，建议更新。"] : parsed
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 12) {
                ZStack {
                    RoundedRectangle(cornerRadius: 14)
                        .fill(LinearGradient(
                            colors: [Color(red: 1.0, green: 0.66, blue: 0.42), Color(red: 0.98, green: 0.45, blue: 0.09)],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        ))
                        .frame(width: 46, height: 46)
                    Image(systemName: "arrow.down.circle.fill")
                        .font(.system(size: 24))
                        .foregroundStyle(.white)
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text("发现新版本")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Text(info.versionName)
                        .font(.title2)
                        .bold()
                }
                Spacer()
                if info.force {
                    Text("强制更新")
                        .font(.caption2)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 3)
                        .background(Capsule().fill(Color.orange.opacity(0.18)))
                        .foregroundStyle(.orange)
                }
            }

            VStack(alignment: .leading, spacing: 3) {
                Text("当前版本 \(AppState.appVersionName)（\(AppState.appVersionCode)）")
                Text("最新版本 \(info.versionName)（\(info.versionCode)）\(info.sizeText)")
                    .foregroundStyle(.orange)
            }
            .font(.caption)
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 12).fill(Color.secondary.opacity(0.10)))

            VStack(alignment: .leading, spacing: 6) {
                Text("更新内容")
                    .font(.subheadline)
                    .bold()
                ScrollView {
                    VStack(alignment: .leading, spacing: 6) {
                        ForEach(lines, id: \.self) { line in
                            HStack(alignment: .top, spacing: 8) {
                                Text("•").foregroundStyle(.orange)
                                Text(line).fixedSize(horizontal: false, vertical: true)
                            }
                            .font(.callout)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .frame(maxHeight: 200)
            }

            HStack {
                Spacer()
                if !info.force {
                    Button("稍后") { onDismiss() }
                }
                Button("前往下载") { onDownload() }
                    .buttonStyle(.borderedProminent)
                    .keyboardShortcut(.defaultAction)
            }
        }
        .padding(20)
        .frame(width: 460)
    }
}

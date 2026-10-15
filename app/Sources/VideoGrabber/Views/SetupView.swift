import SwiftUI

/// 首次运行 / 引擎缺失时的初始化界面。
struct SetupView: View {
    @EnvironmentObject private var state: AppState

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Label("首次使用需要初始化解析引擎", systemImage: "wrench.and.screwdriver")
                .font(.headline)
            Text("将使用本机 Python 3.12+ 创建三个独立环境，并下载 yt-dlp、XHS-Downloader、KS-Downloader 的源码与依赖，约 1-3 分钟。")
                .font(.callout)
                .foregroundStyle(.secondary)

            HStack {
                Button {
                    state.runBootstrap()
                } label: {
                    Label(state.isBootstrapping ? "初始化中…" : "开始初始化", systemImage: "play.fill")
                }
                .buttonStyle(.borderedProminent)
                .disabled(state.isBootstrapping)

                Button {
                    state.runBootstrap(update: true)
                } label: {
                    Label("更新解析引擎", systemImage: "arrow.triangle.2.circlepath")
                }
                .disabled(state.isBootstrapping)

                Spacer()
            }

            if !state.bootstrapLog.isEmpty {
                ScrollView {
                    VStack(alignment: .leading, spacing: 2) {
                        ForEach(state.bootstrapLog.indices, id: \.self) { index in
                            Text(state.bootstrapLog[index])
                                .font(.caption.monospaced())
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                    }
                }
                .frame(height: 220)
                .padding(8)
                .background(Color(nsColor: .textBackgroundColor))
                .clipShape(RoundedRectangle(cornerRadius: 8))
            }
        }
    }
}

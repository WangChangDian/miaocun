import SwiftUI

@main
struct VideoGrabberApp: App {
    @StateObject private var state = AppState()

    var body: some Scene {
        WindowGroup("秒存") {
            MainView()
                .environmentObject(state)
                .frame(minWidth: 620, minHeight: 560)
                .onAppear { state.onAppear() }
                .sheet(item: Binding(
                    get: { state.updateInfo },
                    set: { if $0 == nil { state.updateInfo = nil } }
                )) { info in
                    UpdateSheet(
                        info: info,
                        onDownload: {
                            state.openUpdatePage(info)
                            state.updateInfo = nil
                        },
                        onDismiss: { state.updateInfo = nil }
                    )
                }
        }
        .windowResizability(.contentMinSize)
        .commands {
            // 与 Windows 端一致：设置是主窗口内的切换页，⌘, 也走同一个入口
            CommandGroup(replacing: .appSettings) {
                Button("设置…") { state.showingSettings = true }
                    .keyboardShortcut(",", modifiers: .command)
            }
        }
    }

}

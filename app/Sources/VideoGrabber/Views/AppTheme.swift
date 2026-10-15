import SwiftUI

/// 与安卓端一致的「浅橙 / 浅绿」两套配色。
struct AppThemePalette {
    let key: String
    let primary: Color
    let primaryContainer: Color
    /// 页面背景（顶部微染色 → 底部近白）
    let background: [Color]
    /// 主按钮 / 标题的渐变色
    let gradient: [Color]

    static let green = AppThemePalette(
        key: "green",
        primary: Color(red: 0.30, green: 0.69, blue: 0.49),
        primaryContainer: Color(red: 0.85, green: 0.95, blue: 0.89),
        background: [
            Color(red: 0.91, green: 0.97, blue: 0.94),
            Color(red: 0.97, green: 0.99, blue: 0.98),
        ],
        gradient: [
            Color(red: 0.49, green: 0.85, blue: 0.65),
            Color(red: 0.25, green: 0.64, blue: 0.43),
        ]
    )

    static let orange = AppThemePalette(
        key: "orange",
        primary: Color(red: 1.00, green: 0.54, blue: 0.24),
        primaryContainer: Color(red: 1.00, green: 0.91, blue: 0.84),
        background: [
            Color(red: 1.00, green: 0.93, blue: 0.88),
            Color(red: 1.00, green: 0.98, blue: 0.96),
        ],
        gradient: [
            Color(red: 1.00, green: 0.66, blue: 0.42),
            Color(red: 0.98, green: 0.45, blue: 0.09),
        ]
    )

    static func of(_ key: String) -> AppThemePalette {
        key == "orange" ? .orange : .green
    }
}

/// 主按钮用的渐变背景（与安卓端 gradient 一致）
struct ThemeGradientButtonStyle: ButtonStyle {
    let gradient: [Color]
    let enabled: Bool

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .padding(.horizontal, 14)
            .padding(.vertical, 7)
            .background(
                LinearGradient(
                    colors: enabled ? gradient : [Color.gray.opacity(0.35), Color.gray.opacity(0.45)],
                    startPoint: .leading,
                    endPoint: .trailing
                )
            )
            .foregroundStyle(.white)
            .clipShape(RoundedRectangle(cornerRadius: 10))
            .opacity(configuration.isPressed ? 0.85 : 1)
    }
}

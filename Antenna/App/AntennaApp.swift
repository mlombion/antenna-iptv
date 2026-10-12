import SwiftUI

@main
struct AntennaApp: App {
    @State private var model = AppModel()

    init() {
        // Channel logos are fetched through AsyncImage, which uses the shared cache.
        URLCache.shared = URLCache(memoryCapacity: 64 << 20, diskCapacity: 512 << 20)
    }

    var body: some Scene {
        WindowGroup("Antenna IPTV") {
            RootView()
                .environment(model)
                .frame(minWidth: 1000, minHeight: 640)
                .preferredColorScheme(.dark)
                .task {
                    if model.pipPresenter == nil { model.pipPresenter = PiPWindowController(model: model) }
                    await model.start()
                }
        }
        .windowStyle(.hiddenTitleBar)
        .defaultSize(width: 1400, height: 880)
        .commands {
            CommandGroup(replacing: .newItem) {}
            CommandGroup(replacing: .appSettings) {
                Button("Settings…") { model.go(.settings) }
                    .keyboardShortcut(",")
            }
            CommandMenu("Go") {
                Button("Home") { model.go(.home) }.keyboardShortcut("0")
                Button("Live TV") { model.go(.live) }.keyboardShortcut("1")
                Button("TV Guide") { model.go(.guide) }.keyboardShortcut("2")
                Button("Favorites") { model.go(.favorites) }.keyboardShortcut("3")
                Button("Movies & Series") { model.go(.movies) }.keyboardShortcut("4")
                Divider()
                Button("Search") { model.showSearch.toggle() }.keyboardShortcut("k")
                Divider()
                Button(model.isPiP ? "Exit Picture in Picture" : "Picture in Picture") { model.togglePiP() }
                    .keyboardShortcut("p", modifiers: [.command, .option])
                    .disabled(model.playing == nil)
                Divider()
                Button("Refresh Channels") { Task { await model.refresh(silent: false) } }
                    .keyboardShortcut("r")
                    .disabled(model.phase != .ready)
            }
        }
    }
}

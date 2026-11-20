import SwiftUI
import ComposeApp

@main
struct iOSApp: App {
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            ContentView()
                .ignoresSafeArea(.all)
                .onChange(of: scenePhase) { newPhase in
                    if newPhase == .active {
                        MainViewControllerKt.onAppForeground()
                    }
                }
        }
    }
}

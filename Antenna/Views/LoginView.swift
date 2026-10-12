import SwiftUI

struct LoginView: View {
    @Environment(AppModel.self) private var model
    @State private var name = "My IPTV"
    @State private var server = ""
    @State private var username = ""
    @State private var password = ""
    @FocusState private var field: Field?

    private enum Field { case name, server, username, password }

    var body: some View {
        HStack(spacing: 0) {
            art
            form.frame(maxWidth: .infinity)
        }
        .ignoresSafeArea()
        .onAppear {
            if let acc = model.account {
                name = acc.name
                server = acc.server
                username = acc.username
            }
            field = server.isEmpty ? .server : .password
        }
    }

    private var art: some View {
        ZStack(alignment: .bottomLeading) {
            ZStack {
                Color(hex: 0x10141C)
                RadialGradient(colors: [Theme.accent.opacity(0.55), .clear], center: UnitPoint(x: 0.25, y: 0.3), startRadius: 0, endRadius: 420)
                RadialGradient(colors: [Color(hex: 0xE0703A).opacity(0.45), .clear], center: UnitPoint(x: 0.8, y: 0.75), startRadius: 0, endRadius: 380)
                RadialGradient(colors: [Color(hex: 0x3A6FE0).opacity(0.35), .clear], center: UnitPoint(x: 0.6, y: 0.1), startRadius: 0, endRadius: 300)
            }
            LinearGradient(colors: [Theme.bg.opacity(0.92), Theme.bg.opacity(0.1)], startPoint: .bottom, endPoint: .center)
            VStack(alignment: .leading, spacing: 12) {
                Image(systemName: "dot.radiowaves.left.and.right")
                    .font(.system(size: 36, weight: .semibold))
                    .foregroundStyle(Theme.accent)
                (Text("Antenna ") + Text("IPTV").foregroundStyle(Theme.accent))
                    .font(.system(size: 32, weight: .bold)).tracking(-0.6)
                Text("Live TV from your IPTV subscription, on a player that opens fast and stays open.")
                    .foregroundStyle(Color(hex: 0xC3CAD6))
                    .frame(maxWidth: 340, alignment: .leading)
            }
            .padding(40)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private var form: some View {
        let busy = model.phase == .connecting
        return VStack(alignment: .leading, spacing: 14) {
            Text(model.account == nil ? "Add your subscription" : "Switch account")
                .font(.system(size: 24, weight: .bold)).tracking(-0.4)
            Text("Enter the Xtream Codes details your provider sent you.")
                .foregroundStyle(Theme.muted)
                .padding(.bottom, 4)

            input("Name", text: $name, field: .name, hint: "Only shown in the app.")
            input("Server URL", text: $server, field: .server, prompt: "http://provider.example:8080")
            input("Username", text: $username, field: .username)
            VStack(alignment: .leading, spacing: 6) {
                Text("Password").font(.system(size: 12, weight: .medium)).foregroundStyle(Theme.muted)
                SecureField("", text: $password)
                    .textFieldStyle(.plain)
                    .focused($field, equals: .password)
                    .onSubmit(connect)
                    .modifier(FieldChrome(focused: field == .password))
            }

            if let error = model.loginError, !busy {
                Label(error, systemImage: "exclamationmark.triangle.fill")
                    .foregroundStyle(Color(hex: 0xFF8A80))
                    .font(.system(size: 12.5))
                    .fixedSize(horizontal: false, vertical: true)
            }

            Button(action: connect) {
                Text(busy ? "Connecting…" : "Connect").frame(maxWidth: .infinity)
            }
            .buttonStyle(PillButtonStyle(prominent: true))
            .keyboardShortcut(.defaultAction)
            .disabled(busy)
            .padding(.top, 4)

            if busy { StepList(steps: model.steps).padding(.top, 2) }

            Label("Your password is saved in this Mac's Keychain, not in a file.", systemImage: "lock.fill")
                .font(.system(size: 11.5))
                .foregroundStyle(Theme.faint)

            if model.account != nil && !busy {
                Button("Cancel") { model.cancelSwitchAccount() }
                    .buttonStyle(PillButtonStyle())
                    .frame(maxWidth: .infinity)
            }
        }
        .frame(width: 380)
        .padding(32)
    }

    private func input(_ label: String, text: Binding<String>, field f: Field, prompt: String = "", hint: String? = nil) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label).font(.system(size: 12, weight: .medium)).foregroundStyle(Theme.muted)
            TextField("", text: text, prompt: Text(prompt).foregroundStyle(Theme.faint))
                .textFieldStyle(.plain)
                .autocorrectionDisabled()
                .focused($field, equals: f)
                .onSubmit { advance(from: f) }
                .modifier(FieldChrome(focused: field == f))
            if let hint { Text(hint).font(.system(size: 11.5)).foregroundStyle(Theme.faint) }
        }
    }

    private func advance(from f: Field) {
        switch f {
        case .name: field = .server
        case .server: field = .username
        case .username: field = .password
        case .password: connect()
        }
    }

    private func connect() {
        guard model.phase != .connecting else { return }
        Task { await model.login(name: name, server: server, username: username, password: password) }
    }
}

private struct FieldChrome: ViewModifier {
    let focused: Bool
    func body(content: Content) -> some View {
        content
            .padding(.horizontal, 12)
            .frame(height: 40)
            .background(Theme.surface2, in: RoundedRectangle(cornerRadius: 8))
            .overlay(RoundedRectangle(cornerRadius: 8).strokeBorder(focused ? Theme.accent : Theme.lineStrong))
            .shadow(color: focused ? Theme.accent.opacity(0.18) : .clear, radius: 4)
    }
}

import SwiftUI

struct PhoneLoginView: View {
    @Environment(AppModel.self) private var model
    @State private var name = "My IPTV"
    @State private var server = ""
    @State private var username = ""
    @State private var password = ""
    @FocusState private var field: Field?

    private enum Field { case name, server, username, password }

    var body: some View {
        let busy = model.phase == .connecting
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                VStack(alignment: .leading, spacing: 10) {
                    Image(systemName: "dot.radiowaves.left.and.right")
                        .font(.system(size: 34, weight: .semibold))
                        .foregroundStyle(Theme.accent)
                    (Text("Antenna ") + Text("IPTV").foregroundStyle(Theme.accent))
                        .font(.system(size: 30, weight: .bold))
                    Text(model.account == nil ? "Enter the Xtream Codes details your provider sent you." : "Switch to another subscription.")
                        .foregroundStyle(Theme.muted)
                }
                .padding(.top, 40)
                .padding(.bottom, 8)

                input("Name", text: $name, field: .name, hint: "Only shown in the app.")
                input("Server URL", text: $server, field: .server, prompt: "http://provider.example:8080", keyboard: .URL)
                input("Username", text: $username, field: .username)
                VStack(alignment: .leading, spacing: 6) {
                    Text("Password").font(.system(size: 13, weight: .medium)).foregroundStyle(Theme.muted)
                    SecureField("", text: $password)
                        .textContentType(.password)
                        .focused($field, equals: .password)
                        .submitLabel(.go)
                        .onSubmit(connect)
                        .modifier(PhoneFieldChrome(focused: field == .password))
                }

                if let error = model.loginError, !busy {
                    Label(error, systemImage: "exclamationmark.triangle.fill")
                        .foregroundStyle(Color(hex: 0xFF8A80))
                        .font(.system(size: 13))
                }

                Button(action: connect) {
                    Text(busy ? "Connecting…" : "Connect")
                        .font(.system(size: 16, weight: .semibold))
                        .frame(maxWidth: .infinity, minHeight: 50)
                        .foregroundStyle(Theme.accentInk)
                        .background(Theme.accent, in: RoundedRectangle(cornerRadius: 12))
                }
                .disabled(busy)

                if busy { StepList(steps: model.steps) }

                Label("Your password is saved in this phone's Keychain.", systemImage: "lock.fill")
                    .font(.system(size: 12))
                    .foregroundStyle(Theme.faint)

                if model.account != nil && !busy {
                    Button("Cancel") { model.cancelSwitchAccount() }
                        .frame(maxWidth: .infinity)
                }
            }
            .padding(.horizontal, 24)
            .padding(.bottom, 40)
            .frame(maxWidth: 520)
            .frame(maxWidth: .infinity)
        }
        .scrollDismissesKeyboard(.interactively)
        .background(
            ZStack {
                Theme.bg
                RadialGradient(colors: [Theme.accent.opacity(0.28), .clear], center: .topLeading, startRadius: 0, endRadius: 420)
                RadialGradient(colors: [Color(hex: 0xE0703A).opacity(0.18), .clear], center: .topTrailing, startRadius: 0, endRadius: 360)
            }
            .ignoresSafeArea()
        )
        .onAppear {
            if let acc = model.account {
                name = acc.name
                server = acc.server
                username = acc.username
            }
        }
    }

    private func input(_ label: String, text: Binding<String>, field f: Field, prompt: String = "",
                       hint: String? = nil, keyboard: UIKeyboardType = .default) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label).font(.system(size: 13, weight: .medium)).foregroundStyle(Theme.muted)
            TextField("", text: text, prompt: Text(prompt).foregroundStyle(Theme.faint))
                .keyboardType(keyboard)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .focused($field, equals: f)
                .submitLabel(.next)
                .onSubmit { advance(from: f) }
                .modifier(PhoneFieldChrome(focused: field == f))
            if let hint { Text(hint).font(.system(size: 12)).foregroundStyle(Theme.faint) }
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
        field = nil
        Task { await model.login(name: name, server: server, username: username, password: password) }
    }
}

private struct PhoneFieldChrome: ViewModifier {
    let focused: Bool
    func body(content: Content) -> some View {
        content
            .padding(.horizontal, 14)
            .frame(height: 48)
            .background(Theme.surface2, in: RoundedRectangle(cornerRadius: 10))
            .overlay(RoundedRectangle(cornerRadius: 10).strokeBorder(focused ? Theme.accent : Theme.lineStrong))
    }
}

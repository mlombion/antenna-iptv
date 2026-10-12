// Draws the Android TV launcher banner (320×180 at xhdpi) and square launcher icons.
// `swift Tools/make_tv_banner.swift <res-dir>`
import AppKit

let res = URL(fileURLWithPath: CommandLine.arguments[1])
let amber = NSColor(srgbRed: 0.95, green: 0.71, blue: 0.27, alpha: 1)

func render(width: Int, height: Int, to url: URL, draw: (NSRect) -> Void) {
    let ctx = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
                        space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(cgContext: ctx, flipped: false)
    draw(NSRect(x: 0, y: 0, width: width, height: height))
    NSGraphicsContext.restoreGraphicsState()
    try! FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
    try! NSBitmapImageRep(cgImage: ctx.makeImage()!).representation(using: .png, properties: [:])!.write(to: url)
}

func background(_ r: NSRect, glowAt p: NSPoint, radius: CGFloat) {
    NSGradient(colors: [NSColor(srgbRed: 0.13, green: 0.16, blue: 0.21, alpha: 1),
                        NSColor(srgbRed: 0.04, green: 0.05, blue: 0.07, alpha: 1)])!.draw(in: r, angle: -90)
    NSGradient(colors: [amber.withAlphaComponent(0.35), amber.withAlphaComponent(0)])!
        .draw(fromCenter: p, radius: 0, toCenter: p, radius: radius, options: [])
}

func waves(size: CGFloat, center: NSPoint) {
    let cfg = NSImage.SymbolConfiguration(pointSize: size, weight: .semibold).applying(.init(paletteColors: [amber]))
    let sym = NSImage(systemSymbolName: "dot.radiowaves.left.and.right", accessibilityDescription: nil)!.withSymbolConfiguration(cfg)!
    sym.draw(in: NSRect(x: center.x - sym.size.width / 2, y: center.y - sym.size.height / 2, width: sym.size.width, height: sym.size.height))
}

// Banner: waves on the left, "Antenna IPTV" on the right.
render(width: 320, height: 180, to: res.appending(path: "drawable-xhdpi/banner.png")) { r in
    background(r, glowAt: NSPoint(x: 70, y: 90), radius: 120)
    waves(size: 44, center: NSPoint(x: 70, y: 92))
    let title = NSMutableAttributedString(string: "Antenna\n", attributes: [
        .font: NSFont.systemFont(ofSize: 34, weight: .bold), .foregroundColor: NSColor(white: 0.93, alpha: 1)])
    title.append(NSAttributedString(string: "IPTV", attributes: [.font: NSFont.systemFont(ofSize: 34, weight: .bold), .foregroundColor: amber]))
    title.draw(in: NSRect(x: 130, y: 44, width: 180, height: 96))
}

for (folder, px) in [("mipmap-xhdpi", 96), ("mipmap-xxhdpi", 144), ("mipmap-xxxhdpi", 192)] {
    render(width: px, height: px, to: res.appending(path: "\(folder)/ic_launcher.png")) { r in
        let p = NSPoint(x: r.midX, y: r.midY)
        background(r, glowAt: NSPoint(x: p.x, y: p.y - r.height * 0.05), radius: r.width * 0.5)
        waves(size: r.width * 0.46, center: p)
    }
}

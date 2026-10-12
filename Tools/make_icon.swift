// Draws the app icon. `swift Tools/make_icon.swift out.png [--full-bleed]`
// Mac icons keep a transparent margin and rounded corners; iOS icons are the same artwork filling an opaque
// square (iOS rounds the corners itself).
import AppKit

let args = CommandLine.arguments
let fullBleed = args.contains("--full-bleed")
let size = 1024
let ctx = CGContext(data: nil, width: size, height: size, bitsPerComponent: 8, bytesPerRow: 0,
                    space: CGColorSpace(name: CGColorSpace.sRGB)!,
                    bitmapInfo: fullBleed ? CGImageAlphaInfo.noneSkipLast.rawValue : CGImageAlphaInfo.premultipliedLast.rawValue)!
NSGraphicsContext.saveGraphicsState()
NSGraphicsContext.current = NSGraphicsContext(cgContext: ctx, flipped: false)

let full = NSRect(x: 0, y: 0, width: size, height: size)
// The Mac tile is 824 pt inside a 1024 canvas; the iOS version scales the same artwork up to fill the square.
let tile = fullBleed ? full : full.insetBy(dx: 100, dy: 100)
let scale = tile.width / 824
let center = NSPoint(x: tile.midX, y: tile.midY)

let shape = fullBleed ? NSBezierPath(rect: tile) : NSBezierPath(roundedRect: tile, xRadius: 185, yRadius: 185)
NSGradient(colors: [NSColor(srgbRed: 0.13, green: 0.16, blue: 0.21, alpha: 1),
                    NSColor(srgbRed: 0.04, green: 0.05, blue: 0.07, alpha: 1)])!.draw(in: shape, angle: -90)
shape.addClip()
let glowCenter = NSPoint(x: center.x, y: center.y - 42 * scale)
NSGradient(colors: [NSColor(srgbRed: 0.95, green: 0.71, blue: 0.27, alpha: 0.35),
                    NSColor(srgbRed: 0.95, green: 0.71, blue: 0.27, alpha: 0)])!
    .draw(fromCenter: glowCenter, radius: 0, toCenter: glowCenter, radius: 420 * scale, options: [])

let config = NSImage.SymbolConfiguration(pointSize: 420 * scale, weight: .semibold)
    .applying(.init(paletteColors: [NSColor(srgbRed: 0.95, green: 0.71, blue: 0.27, alpha: 1)]))
let symbol = NSImage(systemSymbolName: "dot.radiowaves.left.and.right", accessibilityDescription: nil)!
    .withSymbolConfiguration(config)!
let s = symbol.size
symbol.draw(in: NSRect(x: center.x - s.width / 2, y: center.y - s.height / 2, width: s.width, height: s.height))

NSGraphicsContext.restoreGraphicsState()
let rep = NSBitmapImageRep(cgImage: ctx.makeImage()!)
try! rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: args[1]))

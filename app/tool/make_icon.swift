// Placeholder app icon (2026-10-26), until a designed one exists. Regenerate every size with:
//   swift app/tool/make_icon.swift /tmp/icon-1024.png
//   then sips -z <px> <px> into ios/Runner/Assets.xcassets/AppIcon.appiconset, the LaunchImage set (160 pt)
//   and android/app/src/main/res/mipmap-*/ic_launcher.png (CLAUDE.md, "TestFlight").
import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

// 1024×1024 opaque App Store icon: a dashed perimeter ring around a map pin, white on the app's red.
let size = 1024
let space = CGColorSpace(name: CGColorSpace.sRGB)!
let ctx = CGContext(data: nil, width: size, height: size, bitsPerComponent: 8, bytesPerRow: 0, space: space,
                    bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
let red = CGColor(srgbRed: 0xC8 / 255.0, green: 0x10 / 255.0, blue: 0x2E / 255.0, alpha: 1)
let white = CGColor(srgbRed: 1, green: 1, blue: 1, alpha: 1)
ctx.setFillColor(red)
ctx.fill(CGRect(x: 0, y: 0, width: size, height: size))

let c = CGPoint(x: 512, y: 512)
// Faint filled perimeter disc, then the dashed ring.
ctx.setFillColor(CGColor(srgbRed: 1, green: 1, blue: 1, alpha: 0.16))
ctx.fillEllipse(in: CGRect(x: c.x - 330, y: c.y - 330, width: 660, height: 660))
ctx.setStrokeColor(white)
ctx.setLineWidth(34)
ctx.setLineCap(.round)
ctx.setLineDash(phase: 0, lengths: [70, 52])
ctx.strokeEllipse(in: CGRect(x: c.x - 330, y: c.y - 330, width: 660, height: 660))
ctx.setLineDash(phase: 0, lengths: [])

// Pin: a round head whose point sits at the perimeter centre (CG origin is bottom-left, y up).
let tip = CGPoint(x: c.x, y: c.y - 40)
let head = CGPoint(x: c.x, y: c.y + 150)
let r: CGFloat = 118
let theta = acos(r / (head.y - tip.y))
let pin = CGMutablePath()
pin.move(to: tip)
pin.addArc(center: head, radius: r, startAngle: -.pi / 2 + theta, endAngle: 3 * .pi / 2 - theta, clockwise: false)
pin.closeSubpath()
ctx.setFillColor(white)
ctx.addPath(pin)
ctx.fillPath()
// The hole shows the perimeter disc (red under 16 % white).
ctx.setFillColor(CGColor(srgbRed: 209 / 255.0, green: 54 / 255.0, blue: 79 / 255.0, alpha: 1))
ctx.fillEllipse(in: CGRect(x: head.x - 46, y: head.y - 46, width: 92, height: 92))

let image = ctx.makeImage()!
let url = URL(fileURLWithPath: CommandLine.arguments[1])
let dest = CGImageDestinationCreateWithURL(url as CFURL, UTType.png.identifier as CFString, 1, nil)!
CGImageDestinationAddImage(dest, image, nil)
CGImageDestinationFinalize(dest)

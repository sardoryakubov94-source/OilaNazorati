import Foundation
import CoreGraphics
import ImageIO

// Android ilovadagi "Bulut servis" ikoni bilan bir xil: oq fon va 4 rangli bulut.
// Android adaptive ikonining ko'rinadigan markaziy 72x72 qismi butun rasmga yoyiladi.

let output = CommandLine.arguments.count > 1
    ? CommandLine.arguments[1]
    : "ios/OilaNazoratiIOS/Assets.xcassets/AppIcon.appiconset/AppIcon.png"
let size = CommandLine.arguments.count > 2
    ? max(1, Int(CommandLine.arguments[2]) ?? 1024)
    : 1024

let colorSpace = CGColorSpaceCreateDeviceRGB()
guard let context = CGContext(
    data: nil,
    width: size,
    height: size,
    bitsPerComponent: 8,
    bytesPerRow: 0,
    space: colorSpace,
    bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
) else {
    fatalError("Could not create app icon drawing context")
}

// Oq fon, shaffoflik yo'q (App Store talabi).
context.setFillColor(CGColor(red: 1, green: 1, blue: 1, alpha: 1))
context.fill(CGRect(x: 0, y: 0, width: size, height: size))

// Android vektor koordinatalari (yuqori-chap boshlang'ich, 108x108) ishlatiladi.
let scale = CGFloat(size) / 72.0
context.translateBy(x: 0, y: CGFloat(size))
context.scaleBy(x: scale, y: -scale)
context.translateBy(x: -18, y: -18)

let cloud = CGMutablePath()
cloud.move(to: CGPoint(x: 74.21, y: 48.61))
cloud.addCurve(to: CGPoint(x: 54, y: 32),
               control1: CGPoint(x: 72.34, y: 39.12),
               control2: CGPoint(x: 64.01, y: 32))
cloud.addCurve(to: CGPoint(x: 35.71, y: 43.11),
               control1: CGPoint(x: 46.05, y: 32),
               control2: CGPoint(x: 39.15, y: 36.51))
cloud.addCurve(to: CGPoint(x: 21, y: 59.5),
               control1: CGPoint(x: 27.44, y: 43.99),
               control2: CGPoint(x: 21, y: 51))
cloud.addCurve(to: CGPoint(x: 37.5, y: 76),
               control1: CGPoint(x: 21, y: 68.6),
               control2: CGPoint(x: 28.4, y: 76))
cloud.addLine(to: CGPoint(x: 73.25, y: 76))
cloud.addCurve(to: CGPoint(x: 87, y: 62.25),
               control1: CGPoint(x: 80.84, y: 76),
               control2: CGPoint(x: 87, y: 69.84))
cloud.addCurve(to: CGPoint(x: 74.21, y: 48.61),
               control1: CGPoint(x: 87, y: 55),
               control2: CGPoint(x: 81.36, y: 49.11))
cloud.closeSubpath()

struct Quadrant {
    let rect: CGRect
    let red: CGFloat
    let green: CGFloat
    let blue: CGFloat
}

let quadrants: [Quadrant] = [
    Quadrant(rect: CGRect(x: 0, y: 0, width: 54, height: 54), red: 41.0/255.0, green: 182.0/255.0, blue: 246.0/255.0),
    Quadrant(rect: CGRect(x: 54, y: 0, width: 54, height: 54), red: 102.0/255.0, green: 187.0/255.0, blue: 106.0/255.0),
    Quadrant(rect: CGRect(x: 0, y: 54, width: 54, height: 54), red: 255.0/255.0, green: 202.0/255.0, blue: 40.0/255.0),
    Quadrant(rect: CGRect(x: 54, y: 54, width: 54, height: 54), red: 239.0/255.0, green: 83.0/255.0, blue: 80.0/255.0)
]

for quadrant in quadrants {
    context.saveGState()
    context.clip(to: quadrant.rect)
    context.addPath(cloud)
    context.setFillColor(CGColor(red: quadrant.red, green: quadrant.green, blue: quadrant.blue, alpha: 1))
    context.fillPath()
    context.restoreGState()
}

guard let image = context.makeImage() else {
    fatalError("Could not render app icon")
}

let url = URL(fileURLWithPath: output)
try? FileManager.default.createDirectory(
    at: url.deletingLastPathComponent(),
    withIntermediateDirectories: true
)
guard let destination = CGImageDestinationCreateWithURL(url as CFURL, "public.png" as CFString, 1, nil) else {
    fatalError("Could not create PNG destination")
}
CGImageDestinationAddImage(destination, image, nil)
guard CGImageDestinationFinalize(destination) else {
    fatalError("Could not write PNG")
}
print("Wrote \(output) (\(size)x\(size))")

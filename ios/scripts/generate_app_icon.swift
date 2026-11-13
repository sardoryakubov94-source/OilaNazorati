import Foundation
import CoreGraphics
import ImageIO

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

let scale = CGFloat(size) / 1024.0
context.scaleBy(x: scale, y: scale)

// Brand background: deep navy, without transparency.
context.setFillColor(CGColor(red: 16.0/255.0, green: 32.0/255.0, blue: 51/255, alpha: 1))
context.fill(CGRect(x: 0, y: 0, width: 1024, height: 1024))

// A clear shield silhouette.
let shield = CGMutablePath()
shield.move(to: CGPoint(x: 512, y: 925))
shield.addCurve(to: CGPoint(x: 842, y: 790),
                control1: CGPoint(x: 690, y: 900),
                control2: CGPoint(x: 820, y: 850))
shield.addLine(to: CGPoint(x: 842, y: 485))
shield.addCurve(to: CGPoint(x: 512, y: 115),
                control1: CGPoint(x: 842, y: 295),
                control2: CGPoint(x: 670, y: 155))
shield.addCurve(to: CGPoint(x: 182, y: 485),
                control1: CGPoint(x: 354, y: 155),
                control2: CGPoint(x: 182, y: 295))
shield.addLine(to: CGPoint(x: 182, y: 790))
shield.addCurve(to: CGPoint(x: 512, y: 925),
                control1: CGPoint(x: 204, y: 850),
                control2: CGPoint(x: 334, y: 900))
shield.closeSubpath()
context.addPath(shield)
context.setFillColor(CGColor(red: 1, green: 1, blue: 1, alpha: 1))
context.fillPath()

// Family silhouettes in the center of the shield.
context.setFillColor(CGColor(red: 16.0/255.0, green: 32.0/255.0, blue: 51/255, alpha: 1))
context.fillEllipse(in: CGRect(x: 447, y: 590, width: 130, height: 130))
context.fillEllipse(in: CGRect(x: 292, y: 545, width: 100, height: 100))
context.fillEllipse(in: CGRect(x: 632, y: 545, width: 100, height: 100))

let centerBody = CGMutablePath()
centerBody.move(to: CGPoint(x: 365, y: 360))
centerBody.addCurve(to: CGPoint(x: 659, y: 360),
                    control1: CGPoint(x: 395, y: 520),
                    control2: CGPoint(x: 629, y: 520))
centerBody.addLine(to: CGPoint(x: 659, y: 430))
centerBody.addLine(to: CGPoint(x: 365, y: 430))
centerBody.closeSubpath()
context.addPath(centerBody)
context.fillPath()

let leftBody = CGMutablePath()
leftBody.move(to: CGPoint(x: 235, y: 390))
leftBody.addCurve(to: CGPoint(x: 425, y: 390),
                  control1: CGPoint(x: 255, y: 500),
                  control2: CGPoint(x: 405, y: 500))
leftBody.addLine(to: CGPoint(x: 425, y: 445))
leftBody.addLine(to: CGPoint(x: 235, y: 445))
leftBody.closeSubpath()
context.addPath(leftBody)
context.fillPath()

let rightBody = CGMutablePath()
rightBody.move(to: CGPoint(x: 599, y: 390))
rightBody.addCurve(to: CGPoint(x: 789, y: 390),
                   control1: CGPoint(x: 619, y: 500),
                   control2: CGPoint(x: 769, y: 500))
rightBody.addLine(to: CGPoint(x: 789, y: 445))
rightBody.addLine(to: CGPoint(x: 599, y: 445))
rightBody.closeSubpath()
context.addPath(rightBody)
context.fillPath()

// Blue check mark: safety and verified family connection.
let check = CGMutablePath()
check.move(to: CGPoint(x: 650, y: 275))
check.addLine(to: CGPoint(x: 710, y: 215))
check.addLine(to: CGPoint(x: 790, y: 315))
context.addPath(check)
context.setStrokeColor(CGColor(red: 47.0/255.0, green: 128.0/255.0, blue: 237.0/255.0, alpha: 1))
context.setLineWidth(42)
context.setLineCap(.round)
context.setLineJoin(.round)
context.strokePath()

guard let image = context.makeImage() else {
    fatalError("Could not render app icon")
}
let outputURL = URL(fileURLWithPath: output)
try FileManager.default.createDirectory(
    at: outputURL.deletingLastPathComponent(),
    withIntermediateDirectories: true
)
guard let destination = CGImageDestinationCreateWithURL(
    outputURL as CFURL,
    "public.png" as CFString,
    1,
    nil
) else {
    fatalError("Could not create PNG destination")
}
CGImageDestinationAddImage(destination, image, nil)
guard CGImageDestinationFinalize(destination) else {
    fatalError("Could not write app icon PNG")
}
print("Generated Oila Nazorati app icon at \(outputURL.path)")

import Foundation

/// 从画面比例、放大倍数和归一化平移计算原图裁剪区，越界平移限制在图片内。
public enum BackgroundImageCrop {
    public static func rectangle(width: Int, height: Int, aspect: Double, zoom: Double,
                                 horizontal: Double, vertical: Double) -> CGRect? {
        guard width > 0, height > 0, aspect.isFinite, aspect > 0, zoom.isFinite,
              horizontal.isFinite, vertical.isFinite else { return nil }
        let scale = max(1, min(4, zoom)), w = Double(width), h = Double(height)
        let cropWidth = min(w, h * aspect) / scale
        let cropHeight = min(h, w / aspect) / scale
        let x = (w - cropWidth) * (1 - min(1, max(-1, horizontal))) / 2
        let y = (h - cropHeight) * (1 - min(1, max(-1, vertical))) / 2
        return CGRect(x: x, y: y, width: cropWidth, height: cropHeight).integral.intersection(CGRect(x: 0, y: 0, width: w, height: h))
    }
}

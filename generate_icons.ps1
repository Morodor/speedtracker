Add-Type -AssemblyName System.Drawing

function Generate-SpeedTrackerIcon {
    param(
        [int]$size,
        [string]$outputPath
    )

    $bitmap = New-Object System.Drawing.Bitmap($size, $size)
    $g = [System.Drawing.Graphics]::FromImage($bitmap)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality

    # 1. Background: Deep Obsidian Dark with subtle gradient
    $rect = New-Object System.Drawing.Rectangle(0, 0, $size, $size)
    $cTop = [System.Drawing.Color]::FromArgb(10, 14, 23)
    $cBottom = [System.Drawing.Color]::FromArgb(18, 26, 38)
    $gradBrush = New-Object System.Drawing.Drawing2D.LinearGradientBrush($rect, $cTop, $cBottom, 45.0)
    $g.FillRectangle($gradBrush, $rect)

    # 2. Outer Athletic Plate / Speedometer Arc
    $pad = [float]($size * 0.10)
    $innerSize = [float]($size - (2.0 * $pad))
    
    # Outer Glow Ring (Cyan 20% alpha)
    $glowPen = New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(50, 0, 255, 204), [float]($size * 0.05))
    $g.DrawEllipse($glowPen, $pad, $pad, $innerSize, $innerSize)

    # High-intensity Neon Cyan Arc
    $platePen = New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(255, 0, 255, 204), [float]($size * 0.055))
    $platePen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
    $platePen.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
    $g.DrawArc($platePen, [float]($pad + $size * 0.02), [float]($pad + $size * 0.02), [float]($innerSize - $size * 0.04), [float]($innerSize - $size * 0.04), 130.0, 280.0)

    # 3. Barbell Horizontal Axis (Steel Silver)
    $barPen = New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(160, 200, 220), [float]($size * 0.045))
    $barPen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
    $barPen.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
    $barY = [float]($size * 0.52)
    $g.DrawLine($barPen, [float]($size * 0.18), $barY, [float]($size * 0.82), $barY)

    # Barbell Sleeves / Plates (Electric Blue)
    $collarBrush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(0, 229, 255))
    $g.FillRectangle($collarBrush, [float]($size * 0.25), [float]($barY - $size * 0.12), [float]($size * 0.05), [float]($size * 0.24))
    $g.FillRectangle($collarBrush, [float]($size * 0.70), [float]($barY - $size * 0.12), [float]($size * 0.05), [float]($size * 0.24))

    # 4. Stylized Lightning / Velocity Vector (Neon Cyan & Gold Core)
    [System.Drawing.PointF[]]$points = @(
        (New-Object System.Drawing.PointF([float]($size * 0.55), [float]($size * 0.18))),
        (New-Object System.Drawing.PointF([float]($size * 0.36), [float]($size * 0.50))),
        (New-Object System.Drawing.PointF([float]($size * 0.50), [float]($size * 0.50))),
        (New-Object System.Drawing.PointF([float]($size * 0.44), [float]($size * 0.84))),
        (New-Object System.Drawing.PointF([float]($size * 0.68), [float]($size * 0.44))),
        (New-Object System.Drawing.PointF([float]($size * 0.53), [float]($size * 0.44)))
    )

    [System.Drawing.PointF[]]$shadowPoints = @(
        (New-Object System.Drawing.PointF([float]($size * 0.56), [float]($size * 0.20))),
        (New-Object System.Drawing.PointF([float]($size * 0.37), [float]($size * 0.52))),
        (New-Object System.Drawing.PointF([float]($size * 0.51), [float]($size * 0.52))),
        (New-Object System.Drawing.PointF([float]($size * 0.45), [float]($size * 0.86))),
        (New-Object System.Drawing.PointF([float]($size * 0.69), [float]($size * 0.46))),
        (New-Object System.Drawing.PointF([float]($size * 0.54), [float]($size * 0.46)))
    )

    $shadowBrush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(120, 0, 0, 0))
    $g.FillPolygon($shadowBrush, $shadowPoints)

    $boltBrush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255, 0, 255, 204))
    $g.FillPolygon($boltBrush, $points)

    # 5. Glowing Center Reticle (Pure White)
    $centerBrush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255, 255, 255))
    $centerSize = [float]($size * 0.06)
    $g.FillEllipse($centerBrush, [float]($size * 0.5 - $centerSize/2), [float]($size * 0.52 - $centerSize/2), $centerSize, $centerSize)

    $dir = [System.IO.Path]::GetDirectoryName($outputPath)
    if ($dir -and (-not (Test-Path $dir))) {
        [System.IO.Directory]::CreateDirectory($dir) | Out-Null
    }

    $bitmap.Save($outputPath, [System.Drawing.Imaging.ImageFormat]::Png)
    $g.Dispose()
    $bitmap.Dispose()
}

$resPath = "app\src\main\res"
Generate-SpeedTrackerIcon 48  "$resPath\mipmap-mdpi\ic_launcher.png"
Generate-SpeedTrackerIcon 48  "$resPath\mipmap-mdpi\ic_launcher_round.png"
Generate-SpeedTrackerIcon 72  "$resPath\mipmap-hdpi\ic_launcher.png"
Generate-SpeedTrackerIcon 72  "$resPath\mipmap-hdpi\ic_launcher_round.png"
Generate-SpeedTrackerIcon 96  "$resPath\mipmap-xhdpi\ic_launcher.png"
Generate-SpeedTrackerIcon 96  "$resPath\mipmap-xhdpi\ic_launcher_round.png"
Generate-SpeedTrackerIcon 144 "$resPath\mipmap-xxhdpi\ic_launcher.png"
Generate-SpeedTrackerIcon 144 "$resPath\mipmap-xxhdpi\ic_launcher_round.png"
Generate-SpeedTrackerIcon 192 "$resPath\mipmap-xxxhdpi\ic_launcher.png"
Generate-SpeedTrackerIcon 192 "$resPath\mipmap-xxxhdpi\ic_launcher_round.png"
Generate-SpeedTrackerIcon 512 "SpeedTracker_Icon_512.png"
Generate-SpeedTrackerIcon 64  "favicon.png"

Write-Host "All icons generated cleanly!"

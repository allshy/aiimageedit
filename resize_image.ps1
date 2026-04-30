param(
  [Parameter(Mandatory=$true)][string]$InputPath,
  [Parameter(Mandatory=$true)][string]$OutputPath,
  [int]$MaxSide = 1536,
  [int]$Quality = 95,
  [ValidateSet("jpg", "png")][string]$Format = "jpg"
)

Add-Type -AssemblyName System.Drawing

$inputFullPath = [System.IO.Path]::GetFullPath($InputPath)
$outputFullPath = [System.IO.Path]::GetFullPath($OutputPath)
$outputDir = [System.IO.Path]::GetDirectoryName($outputFullPath)
if (-not [System.IO.Directory]::Exists($outputDir)) {
  [System.IO.Directory]::CreateDirectory($outputDir) | Out-Null
}

$image = [System.Drawing.Image]::FromFile($inputFullPath)
try {
  $inputWidth = $image.Width
  $inputHeight = $image.Height
  $scale = [Math]::Min(1.0, $MaxSide / [double]([Math]::Max($inputWidth, $inputHeight)))
  $outputWidth = [Math]::Max(1, [int][Math]::Round($inputWidth * $scale))
  $outputHeight = [Math]::Max(1, [int][Math]::Round($inputHeight * $scale))

  $bitmap = New-Object System.Drawing.Bitmap($outputWidth, $outputHeight)
  try {
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    try {
      $graphics.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
      $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
      $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
      if ($Format -eq "png") {
        $graphics.Clear([System.Drawing.Color]::Transparent)
      } else {
        $graphics.Clear([System.Drawing.Color]::White)
      }
      $graphics.DrawImage($image, 0, 0, $outputWidth, $outputHeight)
    } finally {
      $graphics.Dispose()
    }

    if ($Format -eq "png") {
      $bitmap.Save($outputFullPath, [System.Drawing.Imaging.ImageFormat]::Png)
    } else {
      $codec = [System.Drawing.Imaging.ImageCodecInfo]::GetImageEncoders() |
        Where-Object { $_.MimeType -eq "image/jpeg" } |
        Select-Object -First 1
      $encoder = [System.Drawing.Imaging.Encoder]::Quality
      $encoderParams = New-Object System.Drawing.Imaging.EncoderParameters(1)
      $encoderParams.Param[0] = New-Object System.Drawing.Imaging.EncoderParameter($encoder, [long]$Quality)
      $bitmap.Save($outputFullPath, $codec, $encoderParams)
    }
  } finally {
    if ($bitmap) { $bitmap.Dispose() }
  }
} finally {
  $image.Dispose()
}

$outFile = Get-Item -LiteralPath $outputFullPath
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
@{
  inputWidth = $inputWidth
  inputHeight = $inputHeight
  outputWidth = $outputWidth
  outputHeight = $outputHeight
  outputBytes = $outFile.Length
  format = $Format
  quality = $Quality
} | ConvertTo-Json -Compress

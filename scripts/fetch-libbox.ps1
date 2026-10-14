$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$artifactPath = Join-Path $projectRoot 'libbox.aar'
Invoke-WebRequest 'https://raw.githubusercontent.com/singbox-android/libbox/1.12.25/libbox.aar' -OutFile $artifactPath
$actualHash = (Get-FileHash -LiteralPath $artifactPath -Algorithm SHA256).Hash.ToLowerInvariant()
if ($actualHash -ne '5f48d3df14e010473318877ff75a33001e70da46ccde896b1eebc410fde4126d') {
    throw 'libbox SHA-256 mismatch; do not build or run this artifact'
}

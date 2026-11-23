# cslknn - Bilgisayardan kaynak ekleme (Windows PowerShell)
#
# GitHub sunucularının ulaşamadığı ama sizin bağlantınızdan açılan yasal kaynaklar için.
# Kalıbı sizin bilgisayarınızda algılar ve test eder, ardından repoya gönderir.
# Gönderim sonrası GitHub derlemeyi kendisi başlatır.
#
# Kullanım (repo klasöründe):
#   powershell -ExecutionPolicy Bypass -File tools\kaynak-ekle.ps1 -Adres "https://ornek.com" -Tur belgesel
#
# Parametreler:
#   -Adres  (zorunlu) site, kanal, M3U veya RSS adresi
#   -Ad     uygulamada görünecek ad (boşsa otomatik)
#   -Tur    otomatik | film | dizi | belgesel | canli | cizgi | video
#   -Dil    tr | en
#   -Dene   yalnızca test et, repoya gönderme

param(
    [Parameter(Mandatory = $true)][string]$Adres,
    [string]$Ad = "",
    [ValidateSet("otomatik", "film", "dizi", "belgesel", "canli", "cizgi", "video")][string]$Tur = "otomatik",
    [ValidateSet("tr", "en")][string]$Dil = "tr",
    [switch]$Dene
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$env:PYTHONIOENCODING = "utf-8"

$repo = Split-Path -Parent $PSScriptRoot
Set-Location $repo

function Need($cmd, $url) {
    if (-not (Get-Command $cmd -ErrorAction SilentlyContinue)) {
        Write-Host "✗ '$cmd' bulunamadı. Kurulum: $url" -ForegroundColor Red
        exit 1
    }
}
Need "python" "https://www.python.org/downloads/ (kurarken 'Add python.exe to PATH' işaretleyin)"
Need "git" "https://git-scm.com/download/win"

Write-Host "▶ Depo güncelleniyor..." -ForegroundColor Cyan
git pull --quiet --rebase

Write-Host "▶ Gerekli Python paketleri kontrol ediliyor..." -ForegroundColor Cyan
python -m pip install --quiet --disable-pip-version-check -r tools\requirements.txt

$argsList = @("tools\detect.py", $Adres, "--kind", $Tur, "--lang", $Dil, "--out", "sources")
if ($Ad) { $argsList += @("--name", $Ad) }
if ($Dene) { $argsList += "--dry" }

$env:GITHUB_OUTPUT = Join-Path $env:TEMP "cslknn-out.txt"
Remove-Item $env:GITHUB_OUTPUT -ErrorAction SilentlyContinue

python @argsList
if ($LASTEXITCODE -ne 0) {
    Write-Host "`n✗ Kaynak eklenemedi. Yukarıdaki mesaja bakın." -ForegroundColor Red
    exit 1
}
if ($Dene) {
    Write-Host "`n✓ Deneme tamamlandı (repoya gönderilmedi)." -ForegroundColor Green
    exit 0
}

$out = @{}
Get-Content $env:GITHUB_OUTPUT | ForEach-Object { $k, $v = $_ -split "=", 2; $out[$k] = $v }
$file = $out["path"]
$name = $out["name"]

git add -- $file
git commit --quiet -m "Kaynak eklendi (bilgisayardan): $name"
git push --quiet
if ($LASTEXITCODE -ne 0) {
    Write-Host "`n✗ Repoya gönderilemedi. GitHub girişinizi kontrol edin (git push)." -ForegroundColor Red
    exit 1
}

if ($out["ok"] -eq "true") {
    Write-Host "`n✓ $name eklendi. Birkaç dakika içinde yayına girer; CloudStream'i yeniden başlatın." -ForegroundColor Green
} else {
    Write-Host "`n! $name KAPALI olarak eklendi (test tam geçmedi). Tarif: $file" -ForegroundColor Yellow
}

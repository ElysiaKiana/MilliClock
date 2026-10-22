# MilliClock build script: aapt2 + javac + d8 + zipalign + apksigner (no Gradle)
$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

$root  = $PSScriptRoot
$sdk   = "D:\AndroidTerminal#1-2"
$bt    = "$sdk\build-tools\34.0.0"
$plat  = "$sdk\platforms\android-34\android.jar"
$env:JAVA_HOME = "D:\Java\javajdk\jdk-17.0.1"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

if (-not (Test-Path $bt)) { throw "build-tools not found: $bt (run install.ps1 first)" }

$obj = "$root\obj"
Remove-Item -Recurse -Force $obj -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path "$obj\gen", "$obj\classes", "$obj\dex" | Out-Null

Write-Host "[1/7] aapt2 compile resources"
& "$bt\aapt2.exe" compile --dir "$root\app\res" -o "$obj\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

Write-Host "[2/7] aapt2 link"
& "$bt\aapt2.exe" link -o "$obj\base.apk" -I $plat `
    --manifest "$root\app\AndroidManifest.xml" -R "$obj\res.zip" `
    --java "$obj\gen" --auto-add-overlay `
    --min-sdk-version 26 --target-sdk-version 34 `
    --version-code 1 --version-name "1.0"
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Host "[3/7] javac"
$sources = @(Get-ChildItem "$root\app\java" -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$sources += @(Get-ChildItem "$obj\gen" -Recurse -Filter *.java | ForEach-Object { $_.FullName })
& javac -encoding UTF-8 -source 11 -target 11 -Xlint:-options -classpath $plat -d "$obj\classes" $sources
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Write-Host "[4/7] d8 dex"
$classFiles = @(Get-ChildItem "$obj\classes" -Recurse -Filter *.class | ForEach-Object { $_.FullName })
& "$bt\d8.bat" --release --min-api 26 --lib $plat --output "$obj\dex" $classFiles
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Write-Host "[5/7] package classes.dex into apk (keep resources.arsc uncompressed)"
Copy-Item "$obj\base.apk" "$obj\unsigned.apk" -Force
Add-Type -AssemblyName System.IO.Compression.FileSystem
Add-Type -AssemblyName System.IO.Compression
$apk = [System.IO.Compression.ZipFile]::Open("$obj\unsigned.apk", 'Update')
try {
    # resources.arsc must be stored uncompressed for targetSdk 30+
    $arsc = $apk.GetEntry("resources.arsc")
    if ($arsc -and $arsc.Length -ne $arsc.CompressedLength) {
        $ms = New-Object System.IO.MemoryStream
        $es = $arsc.Open(); $es.CopyTo($ms); $es.Close()
        $arsc.Delete()
        $ne = $apk.CreateEntry("resources.arsc", [System.IO.Compression.CompressionLevel]::NoCompression)
        $ns = $ne.Open(); $ms.Position = 0; $ms.CopyTo($ns); $ns.Close()
    }
    $dexBytes = [System.IO.File]::ReadAllBytes("$obj\dex\classes.dex")
    $entry = $apk.CreateEntry("classes.dex", [System.IO.Compression.CompressionLevel]::Optimal)
    $s = $entry.Open(); $s.Write($dexBytes, 0, $dexBytes.Length); $s.Close()
} finally { $apk.Dispose() }

Write-Host "[6/7] zipalign"
& "$bt\zipalign.exe" -f 4 "$obj\unsigned.apk" "$obj\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

Write-Host "[7/7] sign"
$ks = "$root\keystore.jks"
if (-not (Test-Path $ks)) {
    & keytool -genkeypair -keystore $ks -alias milliclock -keyalg RSA -keysize 2048 `
        -validity 10950 -storepass milliclock123 -keypass milliclock123 `
        -dname "CN=MilliClock,O=Personal,C=CN"
    if ($LASTEXITCODE -ne 0) { throw "keytool failed" }
}
& "$bt\apksigner.bat" sign --ks $ks --ks-pass pass:milliclock123 --out "$root\MilliClock.apk" "$obj\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }
& "$bt\apksigner.bat" verify "$root\MilliClock.apk"

$size = (Get-Item "$root\MilliClock.apk").Length / 1KB
Write-Host ("BUILD OK: {0}\MilliClock.apk ({1:N0} KB)" -f $root, $size)

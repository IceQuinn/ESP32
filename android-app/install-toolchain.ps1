$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$root = 'D:\APP\Android'
New-Item -ItemType Directory -Force -Path "$root\downloads", "$root\sdk\cmdline-tools", "$root\gradle-cache" | Out-Null

function Get-Package($Url, $Path) {
    Write-Host "Downloading $Url"
    & curl.exe --fail --location --retry 2 --connect-timeout 20 --max-time 600 --silent --show-error --output $Path $Url
    if ($LASTEXITCODE -ne 0) { throw "Download failed: $Url" }
}

# 解压交给系统自带的 bsdtar，比 PowerShell 5.1 的 Expand-Archive 快一个数量级。
function Expand-Fast($Zip, $Dest) {
    New-Item -ItemType Directory -Force -Path $Dest | Out-Null
    & tar.exe -xf $Zip -C $Dest
    if ($LASTEXITCODE -ne 0) {
        Write-Host "tar failed, falling back to Expand-Archive"
        Expand-Archive -LiteralPath $Zip -DestinationPath $Dest -Force
    }
}

if (!(Test-Path "$root\jdk-17")) {
    Get-Package 'https://corretto.aws/downloads/latest/amazon-corretto-17-x64-windows-jdk.zip' "$root\downloads\jdk17.zip"
    Expand-Fast "$root\downloads\jdk17.zip" "$root\jdk-unpack"
    $jdk = Get-ChildItem "$root\jdk-unpack" -Directory | Select-Object -First 1
    Move-Item -LiteralPath $jdk.FullName -Destination "$root\jdk-17"
}

if (!(Test-Path "$root\sdk\cmdline-tools\latest\bin\sdkmanager.bat")) {
    Get-Package 'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip' "$root\downloads\commandline-tools.zip"
    Expand-Fast "$root\downloads\commandline-tools.zip" "$root\cli-unpack"
    Move-Item -LiteralPath "$root\cli-unpack\cmdline-tools" -Destination "$root\sdk\cmdline-tools\latest"
}

if (!(Test-Path "$root\gradle-8.11.1\bin\gradle.bat")) {
    Get-Package 'https://downloads.gradle.org/distributions/gradle-8.11.1-bin.zip' "$root\downloads\gradle.zip"
    $checksum = (Invoke-RestMethod 'https://services.gradle.org/distributions/gradle-8.11.1-bin.zip.sha256').Trim()
    if ((Get-FileHash "$root\downloads\gradle.zip" -Algorithm SHA256).Hash.ToLower() -ne $checksum) { throw 'Gradle checksum mismatch' }
    Expand-Fast "$root\downloads\gradle.zip" $root
}

$env:JAVA_HOME = "$root\jdk-17"
$env:ANDROID_HOME = "$root\sdk"
$env:GRADLE_USER_HOME = "$root\gradle-cache"
$env:PATH = "$env:JAVA_HOME\bin;$root\sdk\platform-tools;$root\sdk\cmdline-tools\latest\bin;$root\gradle-8.11.1\bin;" + $env:PATH

Write-Host 'Installing Android SDK packages and accepting SDK licenses for this requested installation'
1..100 | ForEach-Object { 'y' } | & "$root\sdk\cmdline-tools\latest\bin\sdkmanager.bat" --sdk_root="$root\sdk" 'platform-tools' 'platforms;android-36' 'build-tools;36.0.0'
if ($LASTEXITCODE -ne 0) { throw 'SDK installation failed' }
& "$root\jdk-17\bin\java.exe" -version
& "$root\sdk\platform-tools\adb.exe" version
& "$root\gradle-8.11.1\bin\gradle.bat" --version
if ($LASTEXITCODE -ne 0) { throw 'Gradle verification failed' }
Write-Host "Toolchain ready in $root"

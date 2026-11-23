# ESP32 BLE LED Control for Android

Native Android app for the ESP32 GATT LED protocol in the parent project.

## Windows command-line toolchain

The local toolchain is installed under `D:\APP\Android`.
Load the environment in PowerShell before using the tools:

```powershell
cd E:\Project\esp\ESP32\ESP\android-app
. .\android-env.ps1
gradle assembleDebug
```

Alternatively, use `.\gradlew.bat assembleDebug`. The first Wrapper run downloads
its own Gradle distribution into the D-drive Gradle cache; `gradle assembleDebug`
uses the already installed distribution directly.

The debug APK is generated at `app\build\outputs\apk\debug\app-debug.apk`.
Install it on a USB-debugging enabled phone with:

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Gradle caches are stored under `D:\APP\Android\gradle-cache`.
The installer can be re-run with `powershell -ExecutionPolicy Bypass -File install-toolchain.ps1`.

## Requirements

- Android Studio Meerkat 2024.3.1 Patch 1 or newer
- Android SDK Platform 36 and Android SDK Build-Tools 36.x
- JDK 17
- An Android 12 or newer phone with Bluetooth Low Energy

The app compiles and targets Android 16 (API 36) and has a minimum SDK of 31.

## BLE protocol

- Device name: `ESP32_BLE`
- Service UUID: `12345678-1234-5678-1234-56789abcdef0`
- LED characteristic UUID: `12345678-1234-5678-1234-56789abcdef1`
- Write `0x01` to turn the LED on
- Write `0x00` to turn the LED off
- Read one byte to obtain the current LED state

## Run

1. Open the `android-app` directory in Android Studio.
2. Install Android SDK Platform 36 when prompted.
3. Let Gradle sync download AGP 8.10.1, Kotlin 2.1.20, and Gradle 8.11.1.
4. Connect an Android phone and run the `app` configuration.
5. Grant the Nearby devices permission.
6. Tap **扫描并连接**, then use **开灯** and **关灯**.

The local workspace where this project was generated did not contain Android
SDK or Gradle installations, so no APK was built locally.

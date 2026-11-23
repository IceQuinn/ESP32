# ESP32
ESP32工程

## BLE LED control protocol

- Device name: `ESP32_BLE`
- Service UUID: `12345678-1234-5678-1234-56789abcdef0`
- LED characteristic UUID: `12345678-1234-5678-1234-56789abcdef1`
- Characteristic properties: read and write with response
- Write `0x01`: turn the LED on
- Write `0x00`: turn the LED off
- Read: returns one byte containing the current LED state (`0x01` or `0x00`)

The service UUID is included in the advertising data. The device name is
included in the scan response. The ESP32 resumes advertising after a BLE
client disconnects.

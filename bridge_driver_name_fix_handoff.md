# Android App — Driver Name Change Compatibility

## Context
The Hawkeye borescope camera driver was updated. The camera's friendly name changed:
- **Old driver:** `USB Camera (0bdf:8076)`
- **New driver:** `Hawkeye HVR 260114-02 (0bdf:8076)`

The USB VID:PID remains the same: **vendor_id=3039 (0x0BDF), product_id=32886 (0x8076)**

## Windows App Fix (Completed 2026-03-24)
The HawkeyeViewerPlus Windows app had hardcoded `"USB Camera"` string matching in:
1. **HawkeyeBridge.exe** (C# .NET) — used to find the DirectShow device for tilt/roll button monitoring
2. **index.html** (Electron renderer) — used to auto-select the camera in the dropdown

Fix: 3-tier device matching — VID:PID first, then "Hawkeye HVR", then "USB Camera", then fallback.
Built and tested as v1.3. Buttons confirmed working with new driver.

## What to Check in the Android App

The Android app (HawkeyeViewer-Android) uses libausbc/UVC for camera access. Check these areas for hardcoded "USB Camera" references or name-based matching that could break with the new driver:

### 1. USB Device Filtering
- Check `AndroidManifest.xml` for USB device filters — these typically use VID/PID (should be fine)
- Check `res/xml/` for any USB device filter XML files
- Search for any string matching on device name/description

### 2. Camera Selection Logic
- `MainActivity.kt` — how does it select which USB camera to use?
- `CameraUVC.kt` / `MultiCameraClient.kt` — any name-based filtering?
- Check if device enumeration uses `UsbDevice.getProductName()` or `getDeviceName()` for matching

### 3. Button/Tilt/Roll Monitoring
- The Android app has its own button detection (separate from the Windows bridge)
- Check `UVCCamera` native layer — does it filter by device name?
- Check any UVC extension unit or camera control code that reads tilt/roll

### 4. Key Search Terms
```
grep -ri "USB Camera" app/ libausbc/
grep -ri "friendly.name\|product.name\|device.name" app/ libausbc/
grep -ri "getProductName\|getDeviceName" app/ libausbc/
```

### 5. Most Likely Safe
The Android USB subsystem typically matches by VID:PID in the intent filter, not by friendly name. The UVC protocol also doesn't depend on the OS-level device name. But worth verifying there are no name-based checks in the Kotlin/Java layer.

## USB Identifiers (unchanged across drivers)
- VID: `0x0BDF` (3039 decimal)
- PID: `0x8076` (32886 decimal)
- UVC class: 239, subclass: 2

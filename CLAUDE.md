# Hawkeye Viewer - Android USB Borescope Camera App

## Project Overview
Android app for viewing USB borescope camera feed on Samsung Galaxy Tab A (SM-T290, Android 11).
Connected via WiFi/ADB for development. Borescope connects through MakerSpot USB hub.

**Hardware:**
- Samsung Galaxy Tab A SM-T290, Android 11
- Borescope: vendor_id=3039, product_id=32886, UVC class 239/subclass 2
- MakerSpot USB hub (creates bandwidth limitations)
- Camera sensor is 720x720 (square), also supports 640x480 and 720x540

## Architecture
- **Package:** `com.hawkeyeborescopes.viewer`
- **Library:** AndroidUSBCamera 3.3.3 (`libausbc` local module, package `com.jiangdg.ausbc`)
- **Base class:** `MainActivity` extends `CameraActivity` (from libausbc)
- **Render mode:** OPENGL (required - NORMAL mode only shows top 15% of frames through USB hub)
- **Build:** AGP 8.2, SDK 34, two product flavors: `mobile` and `tv`

## Current Camera Configuration (in MainActivity.kt)
```kotlin
CameraRequest.Builder()
    .setPreviewWidth(640)
    .setPreviewHeight(480)
    .setRenderMode(CameraRequest.RenderMode.OPENGL)
    .setAspectRatioShow(false)
    .setCaptureRawImage(false)
    .setRawPreviewData(false)
```

## Key Files Modified from Original libausbc
1. **`libausbc/.../widget/TipView.kt`** - SDK 34 Animator callback nullability fix (Animator? -> Animator)
2. **`libausbc/.../base/BaseBottomDialog.kt`** - Fixed `R.id.design_bottom_sheet` -> `MaterialR.id.design_bottom_sheet`
3. **`libausbc/.../base/CameraActivity.kt`** - SDK 34 nullability fix for SurfaceTexture/SurfaceHolder callbacks
4. **`libausbc/.../camera/CameraUVC.kt`** - Added diagnostic logging for supported sizes; MIN_FS=10, MAX_FPS=60

## Known Issues & Status (as of March 2026)

### CRITICAL: USB Hub Bandwidth Limitation
- 720x720 (camera's native square resolution) only gets 1-5 fps through the USB hub - UNUSABLE
- 640x480 works at 30fps when USB connection is fresh
- After many rapid app restart/reconnection cycles, even 640x480 degrades to 1-5fps
- **Physical USB replug is needed to restore performance after testing sessions**

### Aspect Ratio Problem (UNSOLVED)
- Camera sensor is 720x720 (square) but we must use 640x480 (4:3) for bandwidth
- The 640x480 stream appears to crop the square sensor, losing the full circular borescope view
- `setAspectRatioShow(true)` causes a timing issue in MultiCameraClient.kt:
  - `setAspectRatio()` calls `post { requestLayout() }` (async/deferred)
  - But `getSurfaceWidth()/getSurfaceHeight()` is called immediately after, getting stale full-screen dimensions
  - This causes the GL render target to be wrong dimensions
- `setAspectRatioShow(false)` avoids the timing bug but the image stretches to fill the screen (16:9 on tablet)
- Previous attempt to fix GL render dimensions in MultiCameraClient.kt was reverted

### Image Display Problem (CURRENT)
- With current config (640x480, OPENGL, aspectRatioShow=false), image is stretched to 16:9 tablet screen
- Image doesn't stay visible - only top few lines appear occasionally
- This is likely the same USB hub degradation from repeated testing sessions
- **FIRST STEP NEXT SESSION: Physically unplug and replug USB camera/hub before testing**

### NORMAL Render Mode Doesn't Work
- Shows only top 10-15% of image as intermittent lines
- Same frame truncation problem as old libusbcamera v2.3.7
- OPENGL mode is required for reliable streaming through USB hub

## What Worked
- First deployment with OPENGL mode at 640x480: steady 30-31 fps
- Camera auto-connects, OPENED state fires correctly
- All UI controls (capture, recording, image adjustments) are wired up

## Next Steps (Priority Order)
1. **Physical USB reset** - Unplug and replug camera/hub before any testing
2. **Fix aspect ratio** - Need to properly solve the GL render target dimensions so the 640x480 feed displays at 4:3 (not stretched to 16:9). Options:
   - Fix the timing issue in MultiCameraClient.kt (use previewWidth/Height for GL target instead of stale view dimensions when aspectRatioShow is true)
   - Or: set fixed dimensions on the FrameLayout container in XML/code to force 4:3 aspect
   - Or: use a custom AspectRatioTextureView that constrains itself properly before GL init
3. **Investigate 720x720** - Once aspect ratio works, determine if 640x480 actually crops the square sensor or scales it. If it crops, we may need to find creative solutions (lower bandwidth factor, different USB hub, etc.)
4. **Test stability** - Ensure camera feed stays stable across app restarts without degradation

## Build & Deploy Commands
```bash
# Build mobile debug APK
./gradlew :app:assembleMobileDebug

# Install via ADB (WiFi)
adb install -r app/build/outputs/apk/mobile/debug/app-mobile-debug.apk

# View logs
adb logcat -s CameraUVC:* HawkeyeCamera:* MultiCameraClient:* AspectRatioTextureView:*
```

## Project Structure
```
app/src/main/java/com/hawkeyeborescopes/viewer/
  - MainActivity.kt        (extends CameraActivity, main UI)
  - UsbButtonHelper.kt     (physical button listener - no HID interface on this device)
app/src/main/res/layout/
  - activity_main.xml      (FrameLayout container + controls overlay)
libausbc/                   (AndroidUSBCamera 3.3.3 library module)
  src/main/java/com/jiangdg/ausbc/
    - MultiCameraClient.kt (camera lifecycle, GL render setup)
    - camera/CameraUVC.kt  (UVC camera implementation)
    - base/CameraActivity.kt (base activity with camera integration)
    - widget/AspectRatioTextureView.kt (aspect-ratio-aware TextureView)
```

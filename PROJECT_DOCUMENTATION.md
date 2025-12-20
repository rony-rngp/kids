# MKL (Formerly Kids Monitor) - Project Documentation

## 1. Project Overview
**MKL** is an Android application designed to function as a remote monitoring tool (similar to a baby monitor). It turns an Android device into a server that streams video and audio to a connected client (viewer) over a local Wi-Fi network.

**Key Features:**
*   **Live Video Streaming:** Uses MJPEG over HTTP.
*   **Live Audio Streaming:** Uses raw PCM audio chunks over WebSockets.
*   **Remote Control:** Clients can toggle audio, switch cameras (front/back), and stop monitoring via WebSocket commands.
*   **Background Operation:** Runs as a high-priority Foreground Service to ensure persistence even when the app is minimized or the screen is off.
*   **Auto-Recovery:** Automatically restarts the service if the app is swiped away from Recents or if the device reboots.

## 2. Technical Stack
*   **Language:** Kotlin
*   **Platform:** Android (Native)
*   **Build System:** Gradle
*   **Minimum SDK:** (Implied from code, likely 23+)
*   **Networking:**
    *   `java-websocket` (for Audio & Commands)
    *   `ServerSocket` (for MJPEG Video)

## 3. Project Structure
```
/app/src/main/java/com/kidsmonitor/
├── MainActivity.kt           // Entry point. Handles UI, Permissions, and Service start/stop.
├── services/
│   └── MonitorService.kt     // THE CORE. Foreground Service managing servers and hardware streams.
├── network/
│   ├── MjpegServer.kt        // Simple HTTP server for MJPEG video stream (Port 8081).
│   ├── AudioWebSocketServer.kt // WebSocket server for Audio + Control Commands (Port 8082).
│   ├── NetworkUtils.kt       // Helper to get local Wi-Fi IP address.
│   └── CommandListener.kt    // Interface for handling WebSocket commands in the Service.
├── audio/
│   └── AudioStreamer.kt      // Captures audio from microphone (AudioRecord).
├── camera/
│   ├── CameraStreamer.kt     // Captures frames from Camera2 API.
│   └── CameraFacing.kt       // Enum for Front/Back camera.
├── receivers/
│   ├── BootReceiver.kt       // Listen for BOOT_COMPLETED to auto-start service.
│   └── MyDeviceAdminReceiver.kt // Device Admin implementation (prevents uninstall/tampering).
└── utils/
    └── MonitorActions.kt     // Constants for Service Intent Actions.
```

## 4. Key Components Detail

### A. `MonitorService.kt` (The Brain)
*   **Type:** `LifecycleService` (Foreground Service).
*   **Responsibility:**
    *   Manages the lifecycle of `MjpegServer` and `AudioWebSocketServer`.
    *   Manages `CameraStreamer` and `AudioStreamer`.
    *   Handles the persistent notification.
    *   **Crucial Logic:**
        *   `onStartCommand`: Handles `ACTION_START_MONITORING` and `ACTION_STOP_MONITORING`.
        *   `onTaskRemoved`: **CRITICAL FIX APPLIED HERE.** Detects when the app is swiped away and restarts the service with a valid Action Intent.
        *   `Heartbeat`: Checks for active clients. If no client is connected for 15s, it stops hardware access (camera/mic) to save battery, but keeps the servers listening.

### B. `AudioWebSocketServer.kt` (Control & Audio)
*   **Port:** 8082
*   **Protocol:** WebSocket.
*   **Functions:**
    *   Streams raw audio data (byte arrays).
    *   Receives JSON commands: `startMonitoring`, `stopMonitoring`, `switchCamera`, `audioOn`, `audioOff`, `getIp`, `ping`.
    *   Sends status updates (JSON).
*   **Recent Fixes:**
    *   Removed logic that marked server as "stopped" on single client disconnect.
    *   Now sends dynamic Local IP instead of hardcoded IP on connection.

### C. `MjpegServer.kt` (Video)
*   **Port:** 8081
*   **Protocol:** HTTP (Multipart/x-mixed-replace).
*   **Functions:**
    *   Accepts HTTP GET requests.
    *   Streams JPEG frames continuously to connected clients.

### D. `BootReceiver.kt`
*   **Trigger:** `BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED`.
*   **Logic:** Checks if monitoring was previously enabled (`sharedPrefs`). If yes, it immediately starts `MonitorService` in the foreground.

## 5. Critical Workflows

### 1. App Startup & Permission Flow
1.  `MainActivity` launches.
2.  Checks for Permissions (Camera, Audio, Notifications, etc.).
3.  Checks if Device Admin is active.
4.  If all good -> Starts `MonitorService` with `ACTION_START_MONITORING`.

### 2. Service "Keep-Alive" Strategy
*   **Foreground Service:** The service runs with a visible notification ("Servers running"), making it less likely to be killed by Android.
*   **`onTaskRemoved` Override:** If the user swipes the app from the Recents menu, this method is called. It immediately schedules a restart of the service using an Intent with `ACTION_START_MONITORING`.

### 3. Client Connection Flow
1.  Client (Viewer) connects to `ws://<PHONE_IP>:8082`.
2.  Server responds with the phone's IP address and current status.
3.  Client sends `startMonitoring` command.
4.  `MonitorService` enables the Camera.
5.  Client connects to `http://<PHONE_IP>:8081` to view the video stream.

## 6. Recent Modifications (History)
*   **Renamed App:** "Kids Monitor" -> "MKL".
*   **Crash Fix:** `MonitorService.onTaskRemoved` was crashing because the restart Intent lacked an Action. Added `MonitorActions.ACTION_START_MONITORING`.
*   **Server Logic Fix:** `AudioWebSocketServer` was incorrectly stopping its internal loop when a client disconnected. This was removed.
*   **IP Fix:** Replaced hardcoded `192.168.0.101` with dynamic IP retrieval in `AudioWebSocketServer`.
*   **Auto-Start:** `BootReceiver` logic simplified to auto-start if monitoring was previously active, ignoring the explicit "Auto Start" toggle dependency for better reliability.

## 7. Future AI Developer Instructions
*   **Building:** Use `./gradlew assembleDebug` or `./gradlew installDebug`.
*   **Testing:** Requires a real Android device for Camera/Audio functionality. Emulator camera support is limited.
*   **Debugging:** Use `adb logcat -s MonitorService AudioWebSocketServer MjpegServer` to trace issues.
*   **Note:** When modifying `MonitorService`, always ensure the `onStartCommand` and `onTaskRemoved` logic remains robust to prevent OS kills.

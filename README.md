# Jarvis Walking Assistant

A passive-listening AI assistant for Android (Galaxy Z Flip 6 + Galaxy Watch Ultra) that answers any question via Claude's full API + web search, with voice output to Bluetooth hearing aids.

## Features

- **Ambient Listening**: Continuous passive speech recognition via phone or Bluetooth device microphone
- **Full Claude API**: Any question answered (not restricted to private docs)
- **Web Search Integration**: Real-time web access for current information
- **Dual Output**: 
  - Short spoken answer via TTS routed to active Bluetooth audio device
  - Full detailed text answer in the phone UI
- **Watch Companion**: Galaxy Watch Ultra haptic feedback + response display
- **Foreground Service**: Keeps listening even when screen is off/locked

## Setup

### Prerequisites
- Claude API key (from console.anthropic.com, format: `sk-ant-...`)
- GitHub account for building
- Android device: Samsung Galaxy Z Flip 6 (Android 14+)
- Optional: Galaxy Watch Ultra, Phonak Bluetooth hearing aids

### Build via GitHub Actions (Recommended)

1. Replace `YOUR_KEY_HERE` in `JarvisForegroundService.kt` with your Claude API key
2. Push to GitHub repository
3. GitHub Actions builds the APKs automatically
4. Download APKs from Actions → Artifacts

### Local Build (Requires Android Studio + Gradle)

```bash
./gradlew assembleDebug
# Find APKs at app/build/outputs/apk/debug/ and wear/build/outputs/apk/debug/
```

## Installation

1. Download built APK files
2. Enable USB Debugging on your Z Flip 6 (Settings → Developer Options)
3. Install via ADB or direct file explorer:
   - Phone: `adb install app-debug.apk`
   - Watch: `adb install wear-debug.apk`
4. Grant microphone permission when prompted
5. Open Jarvis app and start listening

## Architecture

- **JarvisForegroundService**: Main audio capture + Claude API + TTS pipeline
- **MainActivity**: Live transcript + detailed answer UI
- **WatchActivity**: Watch UI with haptic notifications
- **MessageListenerService**: Phone-to-watch messaging

## Permissions

- `RECORD_AUDIO`: Capture speech input
- `INTERNET`: API calls to Claude
- `FOREGROUND_SERVICE`: Keep service alive when screen off
- `FOREGROUND_SERVICE_MICROPHONE`: Microphone access in foreground

## Troubleshooting

**App crashes on startup**: Check permissions are granted
**No audio output**: Verify Bluetooth device is paired and active
**Speech not recognized**: Ensure microphone is not muted
**Claude not responding**: Verify API key is correct and account has credits

## License

Proprietary - AI Bluetooth Ltd

## Contact

Dale Williams, Founder - AI Bluetooth Ltd
# Jarvis Walking Assistant - Build triggered

# WebHID Bridge for Android

An Android application that acts as a bridge for WebHID-enabled websites to communicate with USB HID devices on Android. Specifically optimized for **Truthear KEYX** and similar DAC/EQ devices.

## Why this app?
Most mobile browsers on Android do not support the **WebHID API**, making it impossible to use powerful web-based EQ tools like [graph.hangout.audio](https://graph.hangout.audio/) or [eqtool.com](https://eqtool.com/) on the go. This app provides a custom WebView with a WebHID polyfill (shim) that translates WebHID calls into native Android USB Host API calls.

## Features
- **WebHID Polyfill**: Implements `navigator.hid.requestDevice`, `open`, `sendReport`, etc.
- **Auto-Connect**: Automatically prompts for USB permission on launch.
- **Desktop Emulation**: Forces sites to load in desktop mode with zoom and scroll support enabled.
- **Quick Access**: Built-in buttons for popular EQ tools.
- **HID Descriptor Parsing**: Full support for device collections and report ID discovery.

## How it works
The app uses a custom Javascript Interface (`AndroidHID`) and a shim (`webhid_shim.js`) injected at document start. It handles:
1.  **USB Discovery**: Finding and matching devices via filters.
2.  **Permission**: Requesting native Android USB permission.
3.  **Communication**: Handling `controlTransfer` and `bulkTransfer` for Feature, Input, and Output reports.

## Disclaimer
This is an **unofficial community project**. I am not affiliated with Truthear, Hangout Audio, or EQ Tool. This project is provided "as is" to improve the mobile experience for device owners.

## License
MIT

## APPLICATION INSTALLATION
[WebHID Bridge.zip](https://github.com/user-attachments/files/30805851/WebHID.Bridge.zip)

This is the first stable release of the WebHID Bridge for Android, specifically designed to bring full mobile support to **Truthear KEYX** and similar devices.

### 🌟 Key Features
- **WebHID Polyfill**: Enables USB HID communication on mobile browsers that natively lack WebHID API support.
- **Optimized for EQ Tools**: Full compatibility with [graph.hangout.audio](https://graph.hangout.audio/) and [eqtool.com](https://eqtool.com/).
- **Desktop Emulation**: Automatically forces sites into desktop mode with proper zoom, scroll, and viewport settings.
- **Auto-Connect**: Prompts for USB permissions automatically on startup for a seamless experience.

### 🛠 How to Install
1. Download the `WebHID Bridge.zip` file attached to this release.
2. Extract the `.apk` file from the ZIP.
3. Install the APK on your Android device (you may need to allow "Install from unknown sources" in your settings).
4. Connect your Truthear KEYX and launch the app.

### ⚠️ Disclaimer
This is an **unofficial community project**. I am not affiliated with Truthear or the mentioned web tools. Use it at your own risk.

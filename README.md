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

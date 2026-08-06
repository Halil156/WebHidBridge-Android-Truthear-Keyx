// webhid_shim.js
// Enhanced WebHID Polyfill for Android WebView

(function () {
  if (navigator.hid && navigator.hid.__isAndroidBridge) return;

  // Simple EventTarget Polyfill for older WebViews
  class BridgeEventTarget {
    constructor() {
      this._listeners = {};
    }
    addEventListener(type, listener) {
      if (!this._listeners[type]) this._listeners[type] = [];
      this._listeners[type].push(listener);
    }
    removeEventListener(type, listener) {
      if (!this._listeners[type]) return;
      this._listeners[type] = this._listeners[type].filter(l => l !== listener);
    }
    dispatchEvent(event) {
      const type = event.type;
      const listeners = this._listeners[type] || [];
      listeners.forEach(l => {
        if (typeof l === 'function') l.call(this, event);
        else if (l && typeof l.handleEvent === 'function') l.handleEvent(event);
      });
      if (this['on' + type]) this['on' + type](event);
      return true;
    }
  }

  const ETarget = window.EventTarget || BridgeEventTarget;

  let nextCallId = 1;
  const pending = {};
  const openDevices = {};

  function callNative(method, argsObj) {
    return new Promise((resolve, reject) => {
      const callId = nextCallId++;
      pending[callId] = { resolve, reject };
      if (window.AndroidHID) {
        AndroidHID.call(method, JSON.stringify(argsObj || {}), callId);
      } else {
        reject(new Error("AndroidHID interface not found"));
      }
    });
  }

  window.__hidResolve = function (callId, jsonResult) {
    const p = pending[callId];
    if (!p) return;
    delete pending[callId];
    p.resolve(jsonResult ? JSON.parse(jsonResult) : undefined);
  };
  window.__hidReject = function (callId, message) {
    const p = pending[callId];
    if (!p) return;
    delete pending[callId];
    p.reject(new Error(message));
  };

  window.__hidDispatchInputReport = function (deviceId, reportId, base64Data) {
    const dev = openDevices[deviceId];
    if (!dev) return;
    const bytes = base64ToUint8Array(base64Data);
    const event = new CustomEvent('inputreport', { detail: { reportId, data: new DataView(bytes.buffer), device: dev } });
    // Mirror real WebHID event properties
    event.reportId = reportId;
    event.data = new DataView(bytes.buffer);
    event.device = dev;
    dev.dispatchEvent(event);
  };

  function base64ToUint8Array(b64) {
    const binary = atob(b64 || '');
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
    return bytes;
  }
  function uint8ArrayToBase64(bytes) {
    let binary = '';
    for (let i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i]);
    return btoa(binary);
  }
  function toUint8Array(data) {
    if (data instanceof Uint8Array) return data;
    if (data instanceof ArrayBuffer) return new Uint8Array(data);
    if (data && data.buffer instanceof ArrayBuffer) return new Uint8Array(data.buffer, data.byteOffset || 0, data.byteLength);
    return new Uint8Array(data);
  }

  class HIDDeviceShim extends ETarget {
    constructor(info) {
      super();
      this.__id = info.id;
      this.vendorId = info.vendorId;
      this.productId = info.productId;
      this.productName = info.productName || '';
      this.collections = info.collections || [];
      this.opened = false;
      openDevices[this.__id] = this;
    }
    async open() {
      await callNative('open', { deviceId: this.__id });
      this.opened = true;
    }
    async close() {
      await callNative('close', { deviceId: this.__id });
      this.opened = false;
    }
    async sendReport(reportId, data) {
      const bytes = toUint8Array(data);
      await callNative('sendReport', { deviceId: this.__id, reportId, data: uint8ArrayToBase64(bytes) });
    }
    async sendFeatureReport(reportId, data) {
      const bytes = toUint8Array(data);
      await callNative('sendFeatureReport', { deviceId: this.__id, reportId, data: uint8ArrayToBase64(bytes) });
    }
    async receiveFeatureReport(reportId) {
      const res = await callNative('receiveFeatureReport', { deviceId: this.__id, reportId });
      const bytes = base64ToUint8Array(res.data);
      return new DataView(bytes.buffer);
    }
    async forget() {
        await callNative('close', { deviceId: this.__id });
        delete openDevices[this.__id];
    }
  }

  class HIDShim extends ETarget {
    constructor() {
      super();
      this.__isAndroidBridge = true;
    }
    async requestDevice(options) {
      const res = await callNative('requestDevice', { filters: (options && options.filters) || [] });
      const list = Array.isArray(res) ? res : [res];
      return list.map((info) => openDevices[info.id] || new HIDDeviceShim(info));
    }
    async getDevices() {
      const res = await callNative('getDevices', {});
      const list = Array.isArray(res) ? res : [];
      return list.map((info) => openDevices[info.id] || new HIDDeviceShim(info));
    }
  }

  const hidInstance = new HIDShim();

  // Polyfill navigator.hid
  if (!navigator.hid) {
    Object.defineProperty(navigator, 'hid', {
      value: hidInstance,
      configurable: true,
      enumerable: true
    });
  } else {
      // If it exists but is broken or we want to override
      try {
          navigator.hid.requestDevice = hidInstance.requestDevice.bind(hidInstance);
          navigator.hid.getDevices = hidInstance.getDevices.bind(hidInstance);
          navigator.hid.__isAndroidBridge = true;
      } catch(e) {}
  }

  // Also some sites check for Bluetooth/USB to enable HID UI
  if (!navigator.usb) {
      navigator.usb = new ETarget();
      navigator.usb.getDevices = async () => [];
  }

  console.log("WebHID Bridge Shim Injected Successfully");
})();

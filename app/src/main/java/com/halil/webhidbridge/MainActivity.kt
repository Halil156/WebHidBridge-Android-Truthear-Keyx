package com.halil.webhidbridge

import android.os.Bundle
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewFeature
import androidx.webkit.WebViewCompat
import java.io.BufferedReader

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var bridge: WebHidBridge

    private val hangoutUrl = "https://graph.hangout.audio/"
    private val eqtoolUrl = "https://eqtool.com/"
    private val defaultUrl = hangoutUrl

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        val urlBar = findViewById<EditText>(R.id.urlBar)
        val goButton = findViewById<Button>(R.id.goButton)
        val connectHidButton = findViewById<Button>(R.id.connectHidButton)
        val btnHangout = findViewById<Button>(R.id.btnHangout)
        val btnEqtool = findViewById<Button>(R.id.btnEqtool)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.useWideViewPort = true
        webView.settings.loadWithOverviewMode = true
        webView.settings.builtInZoomControls = true
        webView.settings.displayZoomControls = false
        webView.settings.setSupportZoom(true)
        webView.settings.databaseEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.javaScriptCanOpenWindowsAutomatically = true
        webView.settings.allowFileAccess = true
        webView.settings.allowContentAccess = true
        webView.settings.loadWithOverviewMode = true
        webView.settings.useWideViewPort = true
        webView.settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        
        webView.isHorizontalScrollBarEnabled = true
        webView.isVerticalScrollBarEnabled = true
        webView.scrollBarStyle = WebView.SCROLLBARS_INSIDE_OVERLAY
        
        WebView.setWebContentsDebuggingEnabled(true)

        bridge = WebHidBridge(this, webView)
        webView.addJavascriptInterface(bridge, "AndroidHID")

        val shimJs = readAsset("webhid_shim.js")

        // Preferred: inject at document start, before the page's own scripts run.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, shimJs, setOf("*"))
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                Log.d("SiteConsole", "${message.messageLevel()}: ${message.message()} (${message.sourceId()}:${message.lineNumber()})")
                return true
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // Fallback for devices where DOCUMENT_START_SCRIPT isn't available.
                view.evaluateJavascript(shimJs, null)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                // Masaüstü gibi davranmasını güçlendir ve viewport/zoom ayarlarını yap
                view.evaluateJavascript("""
                    (function() {
                        // Masaüstü emülasyonu
                        try {
                            Object.defineProperty(navigator, 'platform', { get: function() { return 'Win32'; } });
                            Object.defineProperty(navigator, 'maxTouchPoints', { get: function() { return 0; } });
                        } catch(e) {}

                        function fixViewport() {
                            var meta = document.querySelector('meta[name="viewport"]');
                            var content = 'width=1280, initial-scale=1.0, minimum-scale=0.2, user-scalable=yes';
                            if (meta) {
                                if (meta.getAttribute('content') !== content) {
                                    meta.setAttribute('content', content);
                                }
                            } else {
                                meta = document.createElement('meta');
                                meta.name = 'viewport';
                                meta.content = content;
                                document.head.appendChild(meta);
                            }
                            
                            var style = document.getElementById('bridge-fix-style');
                            if (!style) {
                                style = document.createElement('style');
                                style.id = 'bridge-fix-style';
                                style.innerHTML = 'html, body { overflow: auto !important; width: 1280px !important; position: static !important; }';
                                document.head.appendChild(style);
                            }
                        }
                        
                        fixViewport();
                        var observer = new MutationObserver(fixViewport);
                        observer.observe(document.head, { childList: true, attributes: true, subtree: true });
                        setInterval(fixViewport, 2000);
                    })();
                """.trimIndent(), null)
            }
        }

        goButton.setOnClickListener {
            val url = urlBar.text.toString().trim().ifEmpty { defaultUrl }
            webView.loadUrl(if (url.startsWith("http")) url else "https://$url")
        }

        connectHidButton.setOnClickListener {
            triggerUsbPermission()
        }

        btnHangout.setOnClickListener {
            urlBar.setText(hangoutUrl)
            webView.loadUrl(hangoutUrl)
        }

        btnEqtool.setOnClickListener {
            urlBar.setText(eqtoolUrl)
            webView.loadUrl(eqtoolUrl)
        }

        webView.loadUrl(defaultUrl)

        // Uygulama açıldıktan 1 saniye sonra USB izni iste
        webView.postDelayed({ triggerUsbPermission() }, 1000)
    }

    private fun triggerUsbPermission() {
        webView.evaluateJavascript("""
            (async () => {
                try {
                    const devices = await navigator.hid.getDevices();
                    if (devices.length === 0) {
                        await navigator.hid.requestDevice({ filters: [] });
                    }
                } catch (e) {
                    console.error("HID Otomatik İzin Hatası: " + e.message);
                }
            })();
        """.trimIndent(), null)
    }

    private fun readAsset(name: String): String =
        assets.open(name).bufferedReader().use(BufferedReader::readText)

    override fun onDestroy() {
        bridge.teardown()
        super.onDestroy()
    }
}

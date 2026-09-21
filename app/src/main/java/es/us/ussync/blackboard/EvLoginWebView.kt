package es.us.ussync.blackboard

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EvLoginWebView(
    onVerify: (Boolean) -> Unit,
    onCancel: () -> Unit,
    verifying: Boolean,
) {
    val handler = remember { Handler(Looper.getMainLooper()) }
    val currentVerify = rememberUpdatedState(onVerify)
    val backgroundColor = MaterialTheme.colorScheme.background
    DisposableEffect(Unit) {
        onDispose { handler.removeCallbacksAndMessages(null) }
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            "Inicia sesión en la página oficial de la Universidad de Sevilla.",
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { context ->
                WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    setBackgroundColor(backgroundColor.toArgb())
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webChromeClient = WebChromeClient()
                    var automaticCheckDone = false
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            // Existing SSO cookies often redirect through the university login page.
                            // A single silent check is enough to continue without interrupting manual login.
                            if (!automaticCheckDone && url?.startsWith("https://ev.us.es/") == true) {
                                automaticCheckDone = true
                                handler.postDelayed({ currentVerify.value(true) }, 900L)
                            }
                        }
                    }
                    android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    loadUrl(EvEndpoints.loginUrl.toString())
                }
            },
            update = { it.setBackgroundColor(backgroundColor.toArgb()) },
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onCancel, enabled = !verifying) { Text("Cancelar") }
            Button(onClick = { onVerify(false) }, enabled = !verifying) {
                if (verifying) CircularProgressIndicator() else Text("He terminado: comprobar sesión")
            }
        }
    }
}

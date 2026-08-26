package com.minosuko.clouddrive

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var messageLaunch by mutableStateOf<MessageLaunch?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent {
            var theme by remember { mutableStateOf(AppSettings.theme(this@MainActivity)) }
            CloudDriveTheme(darkTheme = theme == ThemeMode.Dark) {
                CloudDriveRoot(
                    theme = theme,
                    onThemeChanged = {
                        AppSettings.saveTheme(this@MainActivity, it)
                        theme = it
                    },
                    messageLaunch = messageLaunch,
                )
            }
        }
        lifecycleScope.launch(Dispatchers.IO) {
            AppSettings.schedule(applicationContext)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri = intent?.data
        val messagingScheme = uri?.scheme in setOf("sms", "smsto", "mms", "mmsto")
        messageLaunch = when {
            intent?.action == Intent.ACTION_SENDTO && messagingScheme -> MessageLaunch(
                address = uri?.schemeSpecificPart?.substringBefore('?')?.let(Uri::decode)?.trim()?.ifEmpty { null },
                body = uri?.getQueryParameter("body"),
                requestId = System.nanoTime(),
            )
            intent?.getBooleanExtra(EXTRA_OPEN_MESSAGES, false) == true -> MessageLaunch(requestId = System.nanoTime())
            else -> messageLaunch
        }
    }
}

data class MessageLaunch(
    val address: String? = null,
    val body: String? = null,
    val requestId: Long,
)

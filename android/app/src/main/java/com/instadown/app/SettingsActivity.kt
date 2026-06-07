package com.instadown.app

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

class SettingsActivity : ComponentActivity() {

    private val bridge by lazy { PythonBridge(applicationContext) }
    private val isSignedInState = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshSignedIn()
        setContent {
            InstaDownTheme {
                Scaffold(
                    topBar = {
                        @OptIn(ExperimentalMaterial3Api::class)
                        TopAppBar(
                            title = { Text("Settings") },
                            navigationIcon = {
                                IconButton(onClick = { finish() }) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Back",
                                    )
                                }
                            },
                        )
                    },
                ) { padding ->
                    SettingsBody(
                        modifier = Modifier.padding(padding).fillMaxSize(),
                        isSignedIn = isSignedInState.value,
                        onSignIn = { startActivity(Intent(this, LoginActivity::class.java)) },
                        onSignOut = {
                            bridge.clearCookies()
                            Toast.makeText(this, "Signed out. Cookies cleared.", Toast.LENGTH_SHORT).show()
                            isSignedInState.value = false
                        },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-check the cookies file on every resume. Without this, returning
        // from LoginActivity (which writes the cookies file) shows the stale
        // "Not signed in" state until the user kills and reopens the app.
        refreshSignedIn()
    }

    private fun refreshSignedIn() {
        isSignedInState.value = bridge.cookiesFile().isFile
    }
}

@Composable
private fun SettingsBody(
    modifier: Modifier = Modifier,
    isSignedIn: Boolean,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
) {
    var showConfirm by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (isSignedIn) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.errorContainer
                },
            ),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    if (isSignedIn) "Signed in to Instagram" else "Not signed in",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (isSignedIn) {
                        "Your login cookies are saved on this device. Downloads will work until they expire (a few weeks)."
                    } else {
                        "Sign in once to enable downloads. Your password is never sent to or stored by InstaDown — login happens in an in-app browser."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        if (isSignedIn) {
            OutlinedButton(
                onClick = { showConfirm = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Sign out & clear cookies")
            }
        } else {
            Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth()) {
                Text("Sign in to Instagram")
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("About", fontWeight = FontWeight.SemiBold)
                Text(
                    "InstaDown v0.1.0 — saves Instagram photos and carousels to your phone.\n\n" +
                        "Sign-in uses an in-app browser; the cookies stay on your device. " +
                        "No data is sent anywhere except Instagram itself.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("Sign out?") },
            text = { Text("This deletes the saved cookies. You can sign in again at any time.") },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirm = false
                        onSignOut()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Sign out") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

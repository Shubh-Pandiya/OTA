package com.example.otaapplication.migration

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.example.otaapplication.BuildConfig
import com.example.otaapplication.MainActivity
import com.example.otaapplication.ui.theme.OTAApplicationTheme
import com.example.otaapplication.update.AppUpdater
import com.example.otaapplication.update.PhoneReleases
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Takes this phone's data from the old app. Runs in its own process (":restore"), so the main app's
 * open databases are never written under it. The old app hands over a file and its recovery code;
 * only that app, signed with the original key, may do so. The user can also pick the file from
 * Downloads and type the code.
 */
class RestoreActivity : ComponentActivity() {
    private sealed interface Ui {
        data object PickFile : Ui
        data class NeedCode(val uri: Uri, val error: String? = null) : Ui
        data object Working : Ui
        data class Done(val summary: MoveBackup.Summary) : Ui
        data class Refused(val message: String) : Ui
    }

    private var ui by mutableStateOf<Ui>(Ui.Working)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = when {
            signedIn() -> Ui.Refused("This app is already signed in and in use, so the old app's data can't be moved into it. Sign out here first, then move again from the old app.")
            intent.data == null -> Ui.PickFile
            else -> callerProblem()?.let { Ui.Refused(it) } ?: run {
                val code = intent.getStringExtra(AppMove.EXTRA_CODE)
                if (code == null) Ui.NeedCode(intent.data!!) else Ui.Working.also { restore(intent.data!!, code) }
            }
        }
        setContent {
            OTAApplicationTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Screen() }
            }
        }
    }

    /** Null when the hand-over comes from the old OTA Business app signed with the original key. */
    private fun callerProblem(): String? {
        val caller = callingPackage ?: return "Open the move from the old OTA Business app, or pick the file from Downloads."
        if (caller == packageName) return null
        if (caller != BuildConfig.MOVE_SOURCE_PACKAGE) return "Only the old OTA Business app can hand over data."
        val info = runCatching {
            packageManager.getPackageInfo(caller, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        }.getOrNull() ?: return "Only the old OTA Business app can hand over data."
        val pins = PhoneReleases.pins(BuildConfig.MOVE_SOURCE_CERTS)
        return if (AppUpdater.certFingerprints(info).any { it in pins }) null else "The app handing over data is not the genuine OTA Business app."
    }

    private fun signedIn(): Boolean = getSharedPreferences("ota_cloud", Context.MODE_PRIVATE).contains("auth_refresh_token")

    private fun restore(uri: Uri, code: String) {
        ui = Ui.Working
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    stopMainProcess()
                    contentResolver.openInputStream(uri)?.use { MoveBackup.restore(this@RestoreActivity, it, code) }
                        ?: error("Could not open the move file")
                }
            }
            result.onSuccess {
                setResult(RESULT_OK)
                ui = Ui.Done(it)
            }.onFailure { e ->
                val message = e.message ?: e.javaClass.simpleName
                ui = if (intent.getStringExtra(AppMove.EXTRA_CODE) == null || message.contains("code")) Ui.NeedCode(uri, message) else Ui.Refused("Nothing was changed: $message")
            }
        }
    }

    /** The main app process (if it was opened) must not hold the databases while they are replaced. */
    private fun stopMainProcess() {
        val me = Process.myPid()
        getSystemService(ActivityManager::class.java).runningAppProcesses.orEmpty()
            .filter { it.pid != me && it.uid == Process.myUid() }
            .forEach { Process.killProcess(it.pid) }
    }

    private fun openApp() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }

    @Composable
    private fun Screen() {
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) ui = Ui.NeedCode(uri) else if (ui is Ui.PickFile) finish()
        }
        Column(
            Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(24.dp))
            Text("Move from the old app", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            when (val s = ui) {
                Ui.PickFile -> {
                    Text("Pick the move file the old app saved in Download › OTA Business (a name like OTA-move-20261114-0930.otamove).")
                    Button(onClick = { picker.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth()) { Text("Choose the move file") }
                    OutlinedButton(onClick = { finish() }, Modifier.fillMaxWidth()) { Text("Cancel") }
                }
                is Ui.NeedCode -> CodeEntry(s)
                Ui.Working -> {
                    CircularProgressIndicator()
                    Text("Moving your orders, bills, advices, to-dos, pictures and settings…")
                }
                is Ui.Done -> {
                    Text("Everything has moved.", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Text("${s.summary.images} picture(s), ${s.summary.files} saved file(s) and your settings came across with all orders, bills, advices and to-dos. Sign in once to carry on.")
                    Text("Then remove the old app: the old app offers a button for it.", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = ::openApp, Modifier.fillMaxWidth()) { Text("Open OTA Business") }
                }
                is Ui.Refused -> {
                    Text(s.message)
                    OutlinedButton(onClick = { finish() }, Modifier.fillMaxWidth()) { Text("Close") }
                }
            }
        }
    }

    @Composable
    private fun CodeEntry(s: Ui.NeedCode) {
        var code by remember { mutableStateOf("") }
        Text("Type the recovery code the old app showed (XXXXX-XXXXX-XXXXX-XXXXX).")
        OutlinedTextField(
            value = code, onValueChange = { code = it.uppercase().take(23) }, singleLine = true,
            label = { Text("Recovery code") }, isError = s.error != null,
            supportingText = s.error?.let { e -> { Text(e) } }, modifier = Modifier.fillMaxWidth()
        )
        Button(onClick = { restore(s.uri, code) }, enabled = MoveCrypto.normalize(code) != null, modifier = Modifier.fillMaxWidth()) { Text("Restore") }
        OutlinedButton(onClick = { finish() }, Modifier.fillMaxWidth()) { Text("Cancel") }
    }
}

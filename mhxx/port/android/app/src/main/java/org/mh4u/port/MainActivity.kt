package org.mh4u.port

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mh4u.port.ui.*
import java.io.File

/**
 * The launcher (the PC app's launcher): Home (play, what is ready), Setup (your game, the code
 * library, the update), Settings, Online (the room the game starts with).
 */
class MainActivity : ComponentActivity() {
    private var lastMessage by mutableStateOf("")

    private val gameResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        lastMessage = it.data?.getStringExtra(EmulationActivity.EXTRA_MESSAGE).orEmpty()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Mh4uTheme { Launcher() } }
    }

    // pads work in the menus' binding screens too
    override fun dispatchKeyEvent(e: android.view.KeyEvent): Boolean =
        (MH4UInput.learning() && MH4UInput.onKey(e)) || super.dispatchKeyEvent(e)

    override fun dispatchGenericMotionEvent(e: android.view.MotionEvent): Boolean =
        (MH4UInput.learning() && MH4UInput.onMotion(e)) || super.dispatchGenericMotionEvent(e)

    private enum class Screen(val title: String) { HOME("Home"), SETUP("Setup"), SETTINGS("Settings"), ONLINE("Online") }

    @Composable
    private fun Launcher() {
        var screen by remember { mutableStateOf(Screen.HOME) }
        var ready by remember { mutableIntStateOf(0) }   // bump to re-check files
        val gameOk = remember(ready) { Prefs.gameFile() != null }
        val codeOk = remember(ready) { (File(Storage.aot, "0004000000197100.so").exists() || File(Storage.recomp, "0004000000197100.so").exists()) }

        Column(Modifier.fillMaxSize().background(Mh4uColors.WindowBg).statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().background(Mh4uColors.TitleBgActive).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("MHXX", color = Mh4uColors.Amber, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Text("Monster Hunter XX · port", color = Mh4uColors.TextDisabled, fontSize = 13.sp)
            }
            Tabs(Screen.entries.map { it.title }, screen.ordinal) { screen = Screen.entries[it] }
            Box(Modifier.weight(1f)) {
                when (screen) {
                    Screen.HOME -> Home(gameOk, codeOk, onSetup = { screen = Screen.SETUP })
                    Screen.SETUP -> Setup(gameOk, codeOk) { ready++ }
                    Screen.SETTINGS -> SettingsScreen()
                    Screen.ONLINE -> OnlineBeforeGame()
                }
            }
        }
    }

    // ── home ──

    @Composable
    private fun Home(gameOk: Boolean, codeOk: Boolean, onSetup: () -> Unit) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            if (lastMessage.isNotEmpty()) { Notice(lastMessage); Spacer(Modifier.height(12.dp)) }
            Panel {
                Text("Ready to play?", fontSize = 18.sp)
                Spacer(Modifier.height(10.dp))
                Check("Your game", gameOk, "Choose it in Setup")
                Check("Port code library", codeOk, "Without it, the game runs on the emulator's CPU, which is slower, and the port's options do not apply.")
                Spacer(Modifier.height(14.dp))
                AmberButton(if (gameOk) "Play" else "Set up first", Modifier.fillMaxWidth().height(52.dp)) {
                    val game = Prefs.gamePath
                    if (gameOk) { lastMessage = ""; gameResult.launch(Intent(this@MainActivity, EmulationActivity::class.java).putExtra(EmulationActivity.EXTRA_GAME, game)) }
                    else onSetup()
                }
            }
            SectionTitle("Room")
            Panel {
                val join = Prefs.getApp("Multiplayer", "join", "")
                val host = Prefs.getInt("Multiplayer", "host", 0)
                Text(when { join.isNotEmpty() -> "Joins $join when the game starts"; host > 0 -> "Hosts a room on port $host"; else -> "Solo play: no room" }, fontSize = 14.sp)
                Hint("Rooms can also be joined, hosted or left during play from menu > Multiplayer.")
                LastRoomResult()
            }
        }
    }

    @Composable
    private fun Check(label: String, ok: Boolean, why: String) {
        Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
            Text(if (ok) "✓" else "•", color = if (ok) Mh4uColors.Amber else Mh4uColors.Notice, fontSize = 16.sp, modifier = Modifier.width(22.dp))
            Column { Text(label, fontSize = 15.sp); if (!ok) Hint(why) }
        }
    }

    // ── setup ──

    @Composable
    private fun Setup(gameOk: Boolean, codeOk: Boolean, changed: () -> Unit) {
        val scope = rememberCoroutineScope()
        var busy by remember { mutableStateOf("") }
        var progress by remember { mutableFloatStateOf(-1f) }
        var message by remember { mutableStateOf("") }
        val ctx = LocalContext.current

        suspend fun importTo(uri: Uri, dir: File, name: String?): File? = withContext(Dispatchers.IO) {
            val shown = name ?: ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
            } ?: "file"
            val out = File(dir, shown)
            val total = ctx.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
            runCatching {
                ctx.contentResolver.openInputStream(uri)!!.use { input ->
                    out.outputStream().use { o ->
                        val buf = ByteArray(1 shl 20); var done = 0L
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            o.write(buf, 0, n); done += n
                            if (total > 0) progress = done.toFloat() / total
                        }
                    }
                }
                out
            }.getOrElse { out.delete(); message = "The file could not be copied: ${it.message}"; null }
        }

        /** Copies the user folder to [target] and remembers it for the next start. */
        fun moveUserFolder(target: File): String {
            val old = Storage.root
            if (target.canonicalPath == old.canonicalPath) return "This folder is already in use."
            if (!(target.isDirectory || target.mkdirs()) || !target.canWrite()) return "The app cannot write to this folder."
            // the game's path is absolute: point it at the new place before the copy, so the copy carries it
            val oldGame = Prefs.gamePath
            val prefix = "!" + old.absolutePath
            if (oldGame.startsWith(prefix)) Prefs.gamePath = "!" + target.absolutePath + oldGame.removePrefix(prefix)
            return runCatching {
                old.copyRecursively(target, overwrite = false) { _, _ -> OnErrorAction.SKIP }
                Storage.chooseRoot(target)
                "Files copied to ${target.absolutePath}. Fully close and reopen the app to use this folder. The original files were left in place; delete them once everything works."
            }.getOrElse { Prefs.gamePath = oldGame; "The files could not be copied: ${it.message}" }
        }

        val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            val path = uri?.let { Storage.treeToPath(it) }
            if (uri != null && path == null) message = "This folder cannot be used. Choose a folder on internal storage or an SD card."
            if (path != null) scope.launch {
                busy = "Copying files…"; progress = -1f; message = ""
                message = withContext(Dispatchers.IO) { moveUserFolder(File(path)) }
                busy = ""
            }
        }

        fun askFolder() {
            if (!Storage.hasAllFilesAccess()) {
                message = "Grant \"All files access\" to this app on the next screen, then return and choose the folder again."
                ctx.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + ctx.packageName)))
            } else pickFolder.launch(null)
        }

        val pickGame = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) scope.launch {
                busy = "Copying the game…"; progress = 0f; message = ""
                importTo(uri, Storage.games, null)?.let { Prefs.gamePath = "!" + it.absolutePath; message = "Game imported: ${it.name}" }
                busy = ""; progress = -1f; changed()
            }
        }
        val pickCode = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) scope.launch {
                busy = "Copying the code library…"; progress = 0f; message = ""
                // the core looks for <program id>.so in aot/ (3dsrecomp's libraries)
                importTo(uri, Storage.aot, "0004000000197100.so")?.let { message = "Code library imported" }
                busy = ""; progress = -1f; changed()
            }
        }
        val pickCia = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) scope.launch {
                busy = "Installing the update…"; progress = -1f; message = ""
                val file = importTo(uri, ctx.cacheDir, "update.cia")
                if (file != null) {
                    val status = withContext(Dispatchers.IO) { Native.installCia("!" + file.absolutePath) }
                    file.delete()
                    message = if (status == 0) "Update installed" else "The update could not be installed (status $status)"
                }
                busy = ""
            }
        }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            if (busy.isNotEmpty()) {
                Panel {
                    Text(busy, color = Mh4uColors.Amber)
                    Spacer(Modifier.height(8.dp))
                    if (progress >= 0f) LinearProgressIndicator(progress = { progress }, color = Mh4uColors.Amber, trackColor = Mh4uColors.FrameBg, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(color = Mh4uColors.Amber, trackColor = Mh4uColors.FrameBg, modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(12.dp))
            } else if (message.isNotEmpty()) { Notice(message); Spacer(Modifier.height(12.dp)) }

            SectionTitle("1 · Your game")
            Panel {
                Check("Decrypted Monster Hunter XX (.3ds, .cci or .cxi)", gameOk, "Dump your own cartridge in decrypted form (for example with GodMode9).")
                Hint("The app copies the file into its own storage.")
                Spacer(Modifier.height(8.dp))
                AmberButton(if (gameOk) "Choose another" else "Choose game", enabled = busy.isEmpty()) { pickGame.launch(arrayOf("*/*")) }
            }
            SectionTitle("2 · Code library")
            Panel {
                Check("0004000000197100.so", codeOk, "Built on a PC from your own game dump. The app contains no game code.")
                Hint("On a PC with the Android NDK, run port/android/build-code-library.sh to build the arm64 library from your dump. Copy it to this device and select it here.")
                Spacer(Modifier.height(8.dp))
                AmberButton(if (codeOk) "Choose another" else "Choose library", enabled = busy.isEmpty()) { pickCode.launch(arrayOf("*/*")) }
            }
            SectionTitle("3 · Update")
            Panel {
                Hint("Install the game's update .cia once (the same one the library was built with); skip this step if it is already installed.")
                Spacer(Modifier.height(8.dp))
                QuietButton("Install the update (.cia)", enabled = busy.isEmpty()) { pickCia.launch(arrayOf("*/*")) }
            }
            SectionTitle("Files")
            Panel {
                Text(Storage.root.absolutePath, fontSize = 12.sp, color = Mh4uColors.TextDisabled)
                Hint("Settings, saves and the user folder are stored here. mh4u-app.ini and mh4u.ini use the same format as the PC port and can be copied between the two.")
                Spacer(Modifier.height(8.dp))
                QuietButton("Use a folder on this device", enabled = busy.isEmpty()) { askFolder() }
                Hint("Keeps the game, saves and settings in a folder you can access; they remain after the app is uninstalled. Requires \"All files access\".")
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    // ── settings ──

    @Composable
    private fun SettingsScreen() {
        var tab by remember { mutableIntStateOf(0) }
        Column {
            Tabs(listOf("Game", "Display", "Audio", "Controls", "Chat"), tab) { tab = it }
            Box(Modifier.weight(1f)) {
                when (tab) { 0 -> GamePage(); 1 -> DisplayPage(); 2 -> AudioPage(); 3 -> ControlsPage(); else -> ChatPage() }
            }
        }
    }

    /**
     * What the last game's room did (joined, or why it didn't). The join only happens once the game
     * runs, so it can't be known here beforehand: this is how the last try went.
     */
    @Composable
    private fun LastRoomResult() {
        val last = Prefs.getApp("Multiplayer", "last_result", "")
        if (last.isEmpty()) return
        Spacer(Modifier.height(6.dp))
        if (last.startsWith("Failed")) Notice("Last time: " + last) else Hint("Last time: " + last)
    }

    // ── online, before the game: the room it starts with ──

    @Composable
    private fun OnlineBeforeGame() {
        var join by remember { mutableStateOf(Prefs.getApp("Multiplayer", "join", "")) }
        var password by remember { mutableStateOf(Prefs.getApp("Multiplayer", "password", "")) }
        var host by remember { mutableStateOf(Prefs.getInt("Multiplayer", "host", 0)) }
        var nick by remember { mutableStateOf(Prefs.getApp("Multiplayer", "nickname", "")) }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
            SectionTitle("Starting room")
            Panel {
                LastRoomResult()
                Hint("Set a room here and the game joins or hosts it at start. During play, menu > Multiplayer can join, host or leave rooms at any time.")
                TextRow("Nickname", "Empty: the console's user name.", nick) { nick = it; if (it.isEmpty() || Native.validNickname(it)) Prefs.setApp("Multiplayer", "nickname", it) }
                TextRow("Invite code or address to join", "mh4u://host:port, host or host:port. Leave empty to not join a room.", join) {
                    join = it; Prefs.setApp("Multiplayer", "join", it); if (it.isNotEmpty()) { host = 0; Prefs.setApp("Multiplayer", "host", "0") }
                }
                TextRow("Password (when the address has none)", null, password, password = true) { password = it; Prefs.setApp("Multiplayer", "password", it) }
                ToggleRow("Host a room instead", "Friends join this device's room using the port below.", host > 0) {
                    host = if (it) Prefs.getInt("Multiplayer", "room_port", 24872) else 0
                    Prefs.setApp("Multiplayer", "host", host.toString()); if (it) { join = ""; Prefs.setApp("Multiplayer", "join", "") }
                }
                if (host > 0) TextRow("Port", null, host.toString()) {
                    it.filter(Char::isDigit).toIntOrNull()?.let { p -> host = p; Prefs.setApp("Multiplayer", "host", p.toString()); Prefs.setApp("Multiplayer", "room_port", p.toString()) }
                }
            }
            SectionTitle("Lobby")
            LobbyPanel(onJoin = { h, p, pw ->
                join = "$h:$p"; Prefs.setApp("Multiplayer", "join", join); Prefs.setApp("Multiplayer", "host", "0"); host = 0
                if (pw.isNotEmpty()) { password = pw; Prefs.setApp("Multiplayer", "password", pw) }
            })
            Spacer(Modifier.height(24.dp))
        }
    }
}

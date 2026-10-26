package org.mh4u.port.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mh4u.port.GpuDrivers
import org.mh4u.port.Native
import org.mh4u.port.Prefs

/**
 * The pages of the PC app's menu (Game, Display, Audio), each setting written to its ini the
 * moment it changes. Used by the launcher's Settings and by the in-game menu, which applies
 * them to the game that runs.
 */

private val PAD_BUTTONS_OR_OFF = listOf("Off" to "off") + listOf("A", "B", "X", "Y", "L", "R", "ZL", "ZR", "START", "UP", "DOWN", "LEFT", "RIGHT").map { it to it }
private val PAD_BUTTONS = PAD_BUTTONS_OR_OFF.drop(1)

@Composable
private fun Page(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), content = content)
}

/** The GPU driver choice: the phone's own, or one imported from a .zip (Adreno phones only). */
@Composable
private fun GpuDriverRows() {
    // Adreno phones only: nothing is shown on other chipsets
    if (!Native.supportsCustomDriver()) return
    RowDivider()
    val scope = rememberCoroutineScope()
    var drivers by remember { mutableStateOf(GpuDrivers.list()) }
    var chosen by remember { mutableStateOf(Prefs.getApp("Display", "gpu_driver", "system")) }
    var message by remember { mutableStateOf("") }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            message = "Importing driver…"
            val result = withContext(Dispatchers.IO) { GpuDrivers.install(uri) }
            drivers = GpuDrivers.list()
            message = result.fold({ "Imported \"$it\". Select it in the list above." }, { it.message ?: "The file could not be imported." })
        }
    }
    val options = listOf("System driver" to "system") + drivers.map { (it.name + if (it.version.isNotEmpty()) " (${it.version})" else "") to it.id }
    ChoiceRow("GPU driver", "Loads an imported driver (for example a Turnip build) instead of the system driver. Fully close and reopen the app to apply a change. If a driver fails during startup, the app falls back to the system driver on the next launch.",
        options, if (options.any { it.second == chosen }) chosen else "system") {
        chosen = it
        Prefs.setApp("Display", "gpu_driver", it)
        GpuDrivers.clearCrashMarker()
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AmberButton("Import driver (.zip)") { import.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }
        QuietButton("Remove selected", enabled = chosen != "system" && drivers.any { it.id == chosen }) {
            GpuDrivers.remove(chosen)
            drivers = GpuDrivers.list()
            chosen = "system"; Prefs.setApp("Display", "gpu_driver", "system")
            message = "Driver removed."
        }
    }
    if (message.isNotEmpty()) Hint(message)
}

/** A game option (mh4u.ini). */
@Composable
private fun GameChoice(title: String, key: String, default: String, options: List<Pair<String, String>>, hint: String? = null) {
    var v by remember { mutableStateOf(Prefs.getGame(key, default)) }
    ChoiceRow(title, hint, options, v) { v = it; Prefs.setGame(key, it) }
}

@Composable
private fun GameToggle(title: String, key: String, default: Boolean, hint: String? = null, on: String = "on", off: String = "off") {
    var v by remember { mutableStateOf(Prefs.getGame(key, if (default) on else off) == on) }
    ToggleRow(title, hint, v) { v = it; Prefs.setGame(key, if (it) on else off) }
}

private val ON_OFF = listOf("On" to "on", "Off" to "off")

@Composable
fun GamePage() = Page {
    SectionTitle("Speed")
    Panel {
        var fps by remember { mutableStateOf(Prefs.getGame("fps", "60").toFloatOrNull() ?: 60f) }
        SliderRow("Frame rate cap", "The game runs at 30 fps on a 3DS; game speed stays correct at any cap. Above 60 fps, demanding scenes may need a higher CPU clock.",
            fps, 30f..120f, " fps", steps = 17) { fps = it; Prefs.setGame("fps", it.toInt().toString()) }
        RowDivider()
        var clock by remember { mutableStateOf(Prefs.getInt("Game", "cpu_clock", 100).toFloat()) }
        SliderRow("CPU clock", "Applies on the next game start.", clock, 50f..400f, " %", steps = 6) {
            clock = it; Prefs.setApp("Game", "cpu_clock", it.toInt().toString())
        }
        RowDivider()
        GameToggle("Online sync (net_sync)", "net_sync", true, "Keeps the players in a room synchronised.")
    }

    SectionTitle("Picture")
    Panel {
        GameChoice("Top screen shape", "widescreen", "16:9",
            listOf("16:9", "16:10", "21:9", "32:9", "4:3", "Off").map { it to it.lowercase() })
        RowDivider()
        GameToggle("Native widescreen rendering", "wide_render", true, "When off, a 400×240 image is stretched.", "1", "0")
        RowDivider()
        GameToggle("HUD at screen edges", "hud_edges", true, "Moves the health, stamina and party list to the sides of the screen.", "1", "0")
        RowDivider()
        GameToggle("Dither", "dither", false, "Disable for a cleaner image above 1× resolution.")
        RowDivider()
        GameChoice("Stereoscopic 3D", "stereo_3d", "off", listOf("Off" to "off", "On" to "on"))
        RowDivider()
        GameToggle("Antialiasing", "antialiasing", true)
        RowDivider()
        GameToggle("Bloom", "bloom", false)
        RowDivider()
        GameToggle("Depth of field", "depth_of_field", false)
        RowDivider()
        GameToggle("Motion blur", "motion_blur", true)
        RowDivider()
        GameToggle("Radial blur", "radial_blur", true)
        RowDivider()
        GameToggle("Haze", "haze", true)
    }

    SectionTitle("Touch screen over the game")
    Panel { TouchOverlayEditor() }

    SectionTitle("Quest buttons")
    Panel {
        // each of these presses the 3DS button the game ALSO uses for its own action (ZR, ZL...),
        // and two on the same button fire together: the page says so
        var kick by remember { mutableStateOf(Prefs.getGame("kick", "off")) }
        var target by remember { mutableStateOf(Prefs.getGame("target", "off")) }
        var skip by remember { mutableStateOf(Prefs.getGame("skip", "ZR")) }
        ChoiceRow("Kick", "Assigns the touch screen's kick panel to a button.", PAD_BUTTONS_OR_OFF, kick) { kick = it; Prefs.setGame("kick", it) }
        RowDivider()
        ChoiceRow("Target camera", null, PAD_BUTTONS_OR_OFF, target) { target = it; Prefs.setGame("target", it) }
        RowDivider()
        ChoiceRow("Skip", "Skips the monster's finishing scene.", PAD_BUTTONS_OR_OFF, skip) { skip = it; Prefs.setGame("skip", it) }
        val shared = listOf(kick, target, skip).filter { it != "off" }.groupBy { it }.filter { it.value.size > 1 }.keys
        if (shared.isNotEmpty()) Notice("${shared.joinToString()} is assigned to more than one action, so they trigger together. Assign a separate button to each.")
        RowDivider()
        GameChoice("End the wait (hold)", "skip_end", "ZL+ZR",
            listOf("ZL + ZR" to "ZL+ZR", "L + R" to "L+R", "START + SELECT" to "START+SELECT"),
            "Hold together to end the carving wait early, as if the timer had expired.")
        RowDivider()
        var hold by remember { mutableStateOf(Prefs.getGame("skip_end_hold", "1.0").toFloatOrNull() ?: 1f) }
        SliderRow("Hold time", null, hold, 0.5f..3f, " s", steps = 4) { hold = it; Prefs.setGame("skip_end_hold", "%.1f".format(it)) }
        RowDivider()
        GameChoice("Skip hint on screen", "skip_hint", "on", listOf("On" to "on", "Flash" to "flash", "Off" to "off"))
    }
    Spacer(Modifier.height(24.dp))
}

/** touch_overlay = "<corner> <height %> <opacity %>", quest_overlay and target_overlay. */
@Composable
private fun TouchOverlayEditor() {
    val parts = Prefs.getGame("touch_overlay", "top-right 30 85").split(" ").filter { it.isNotEmpty() }
    var corner by remember { mutableStateOf(parts.getOrNull(0)?.takeIf { it != "on" } ?: if (parts.getOrNull(0) == "off") "off" else "top-right") }
    var size by remember { mutableStateOf(parts.getOrNull(1)?.toFloatOrNull() ?: 30f) }
    var opacity by remember { mutableStateOf(parts.getOrNull(2)?.toFloatOrNull() ?: 85f) }
    fun save() = Prefs.setGame("touch_overlay", if (corner == "off") "off" else "$corner ${size.toInt()} ${opacity.toInt()}")

    ChoiceRow("Corner", "Used by the Overlay screen layout, where the game fills the screen and the touch screen sits in a corner.",
        listOf("Top left" to "top-left", "Top right" to "top-right", "Bottom left" to "bottom-left",
            "Bottom right" to "bottom-right", "Off" to "off"), corner) { corner = it; save() }
    SliderRow("Height", null, size, 10f..70f, " %") { size = it; save() }
    SliderRow("Opacity", null, opacity, 10f..100f, " %") { opacity = it; save() }
    RowDivider()

    // the quest's map and the target camera panel: "map 40 at 100 0", "on 12 at 100 30" or "off"
    PanelEditor("Map in quests", "quest_overlay", "map", "map", "Shows only the map while the quest panels are displayed.")
    RowDivider()
    PanelEditor("Target camera panel", "target_overlay", "on", "on", "A small target-camera view, shown below the map.")
}

@Composable
private fun PanelEditor(title: String, key: String, default: String, onWord: String, hint: String) {
    val parts = Prefs.getGame(key, default).split(" ").filter { it.isNotEmpty() }
    var on by remember { mutableStateOf(parts.firstOrNull() == onWord) }
    var height by remember { mutableStateOf(parts.getOrNull(1)?.toFloatOrNull() ?: 0f) }
    val at = parts.indexOf("at")
    var x by remember { mutableStateOf(if (at >= 0) parts.getOrNull(at + 1)?.toFloatOrNull() ?: 100f else 100f) }
    var y by remember { mutableStateOf(if (at >= 0) parts.getOrNull(at + 2)?.toFloatOrNull() ?: 0f else 0f) }
    var placed by remember { mutableStateOf(at >= 0) }
    fun save() {
        val text = if (!on) "off" else buildString {
            append(onWord)
            if (height > 0f) append(" ").append(height.toInt())
            if (placed) append(" at ").append(x.toInt()).append(" ").append(y.toInt())
        }
        Prefs.setGame(key, text)
    }
    ToggleRow(title, hint, on) { on = it; save() }
    if (on) {
        SliderRow("Height (0 matches the touch screen)", null, height, 0f..70f, " %") { height = it; save() }
        ToggleRow("Custom position", "Horizontal and vertical position, as a percentage of the available space.", placed) { placed = it; save() }
        if (placed) {
            SliderRow("Across", null, x, 0f..100f, " %") { x = it; save() }
            SliderRow("Down", null, y, 0f..100f, " %") { y = it; save() }
        }
    }
}

@Composable
fun DisplayPage() = Page {
    SectionTitle("Screens")
    Panel {
        var screens by remember { mutableStateOf(Prefs.getApp("Display", "screens", "overlay")) }
        ChoiceRow("Layout", "Overlay: the game fills the screen with the touch screen in a corner (see Game > Touch screen over the game).",
            listOf("Overlay" to "overlay", "Stacked" to "stacked", "Single screen" to "single",
                "Side by side" to "side", "Large + small" to "large", "Hybrid" to "hybrid"), screens) {
            screens = it; Prefs.setApp("Display", "screens", it); Prefs.apply()
        }
        RowDivider()
        var portrait by remember { mutableStateOf(Prefs.getApp("Android", "portrait", "top")) }
        ChoiceRow("In portrait", null, listOf("Top screen full width" to "top", "Original" to "original", "Custom" to "custom"), portrait) {
            portrait = it; Prefs.setApp("Android", "portrait", it); Prefs.apply()
        }
        RowDivider()
        var swap by remember { mutableStateOf(Prefs.getBool("Android", "swap", false)) }
        ToggleRow("Swap screens", null, swap) { swap = it; Prefs.setBool("Android", "swap", it); Prefs.apply() }
        var upright by remember { mutableStateOf(Prefs.getBool("Android", "upright", false)) }
        ToggleRow("Upright screens", "Rotates the screens by a quarter turn.", upright) { upright = it; Prefs.setBool("Android", "upright", it); Prefs.apply() }
        var gap by remember { mutableStateOf(Prefs.getInt("Android", "gap", 0).toFloat()) }
        SliderRow("Gap between screens", null, gap, 0f..80f) { gap = it; Prefs.setApp("Android", "gap", it.toInt().toString()); Prefs.apply() }
        var large by remember { mutableStateOf(Prefs.getApp("Android", "large_proportion", "4").toFloatOrNull() ?: 4f) }
        SliderRow("Large screen's share", "For Large + small and Hybrid.", large, 1f..8f, " ×", steps = 13) {
            large = it; Prefs.setApp("Android", "large_proportion", "%.1f".format(it)); Prefs.apply()
        }
    }

    SectionTitle("Picture")
    Panel {
        var res by remember { mutableStateOf(Prefs.getInt("Display", "resolution", 2).toFloat()) }
        SliderRow("Internal resolution", "In multiples of the 3DS resolution. 0 matches the screen.", res, 0f..6f, " ×", steps = 5) {
            res = it; Prefs.setApp("Display", "resolution", it.toInt().toString()); Prefs.apply()
        }
        RowDivider()
        var filter by remember { mutableStateOf(Prefs.getBool("Display", "linear_filter", true)) }
        ToggleRow("Smooth scaling", null, filter) { filter = it; Prefs.setBool("Display", "linear_filter", it); Prefs.apply() }
        var vsync by remember { mutableStateOf(Prefs.getBool("Display", "vsync", true)) }
        ToggleRow("VSync", null, vsync) { vsync = it; Prefs.setBool("Display", "vsync", it); Prefs.apply() }
        var accMul by remember { mutableStateOf(Prefs.getBool("Display", "accurate_mul", false)) }
        ToggleRow("Accurate shader math", "Disabling this is faster on most phones. Applies on the next game start.", accMul) {
            accMul = it; Prefs.setBool("Display", "accurate_mul", it)
        }
        var tex by remember { mutableStateOf(Prefs.getBool("Display", "custom_textures", false)) }
        ToggleRow("Custom textures", "Loads replacement textures from the user folder. Applies on the next game start.", tex) {
            tex = it; Prefs.setBool("Display", "custom_textures", it)
        }
        var fpsCounter by remember { mutableStateOf(Prefs.getApp("Display", "fps_counter", "off")) }
        ChoiceRow("Frame counter", null, ON_OFF, fpsCounter) { fpsCounter = it; Prefs.setApp("Display", "fps_counter", it) }
    }

    SectionTitle("Advanced graphics")
    Panel {
        var asyncSh by remember { mutableStateOf(Prefs.getBool("Display", "async_shaders", true)) }
        ToggleRow("Compile shaders in the background", "Prevents stutter when a new effect first appears; the effect may be briefly missing. Applies on the next game start.", asyncSh) {
            asyncSh = it; Prefs.setBool("Display", "async_shaders", it)
        }
        RowDivider()
        var cache by remember { mutableStateOf(Prefs.getBool("Display", "disk_shader_cache", true)) }
        ToggleRow("Disk shader cache", "Improves smoothness on later launches. Applies on the next game start.", cache) {
            cache = it; Prefs.setBool("Display", "disk_shader_cache", it)
        }
        RowDivider()
        var skipDup by remember { mutableStateOf(Prefs.getBool("Display", "skip_duplicate_frames", false)) }
        ToggleRow("Skip repeated frames", "Reduces GPU work when the image has not changed.", skipDup) {
            skipDup = it; Prefs.setBool("Display", "skip_duplicate_frames", it)
        }
        RowDivider()
        var noRight by remember { mutableStateOf(Prefs.getBool("Display", "disable_right_eye", false)) }
        ToggleRow("Disable right-eye rendering", "Skips the right-eye pass in Azahar's renderer. Keep this off when Game > Stereoscopic 3D is Off: the game already renders a single eye, and combining both can cause visible flicker.", noRight) {
            noRight = it; Prefs.setBool("Display", "disable_right_eye", it)
        }
        RowDivider()
        var skipIdle by remember { mutableStateOf(Prefs.getBool("Display", "aot_skip_idle", true)) }
        ToggleRow("Skip idle loops", "The recompiled CPU skips the game's busy-wait loops to reduce power use. Disable it if the game hangs or input feels delayed.", skipIdle) {
            skipIdle = it; Prefs.setBool("Display", "aot_skip_idle", it)
        }
        RowDivider()
        var fewerUnwinds by remember { mutableStateOf(Prefs.getBool("Display", "aot_fewer_unwinds", true)) }
        ToggleRow("Fewer unwinds", "Answers the game's frequent clock reads (GetSystemTick) in place instead of leaving the recompiled code each time. Requires a code library built with RECOMP_INLINE_SVC=1. Disable it if the game misbehaves.", fewerUnwinds) {
            fewerUnwinds = it; Prefs.setBool("Display", "aot_fewer_unwinds", it)
        }
        RowDivider()
        var noStorage by remember {
            mutableStateOf(when (Prefs.getApp("Display", "vk_no_storage", "off").lowercase()) {
                "shadows", "2" -> "shadows"
                "lighter", "on", "1", "yes", "true" -> "lighter"
                else -> "off"
            })
        }
        ChoiceRow("Lighter surfaces (GPU bandwidth)", "Experimental (Vulkan). Creates surfaces without storage usage so Adreno and Mali GPUs can compress them. \"No shadows\" disables shadow effects entirely. \"With shadows\" keeps them: a surface that needs storage is rebuilt once, which causes a brief hitch the first time. Applies on the next game start.",
            listOf("Off (Azahar default)" to "off", "Lighter, no shadows" to "lighter", "Lighter, with shadows" to "shadows"), noStorage) {
            noStorage = it; Prefs.setApp("Display", "vk_no_storage", it)
        }
        RowDivider()
        var flushDraws by remember { mutableStateOf(Prefs.getApp("Display", "vk_flush_draws", "0")) }
        ChoiceRow("Pass flush threshold", "Number of draw calls in a render pass before it is submitted to the GPU (Adreno and Mali). Results vary by device, so compare on your phone. Applies on the next game start.",
            listOf("Default (20)" to "0", "8" to "8", "40" to "40", "100" to "100"), flushDraws) {
            flushDraws = it; Prefs.setApp("Display", "vk_flush_draws", it)
        }
        RowDivider()
        var affinity by remember { mutableStateOf(Prefs.getApp("Display", "cpu_affinity", "default")) }
        ChoiceRow("CPU cores", "Controls which CPU cores the game's threads may use. \"Big + prime\" keeps them off the efficiency cores. \"Prime only\" places the game and the renderer on a single core, which can reduce performance. Applies on the next game start.",
            listOf("Default (Android decides)" to "default", "Big + prime" to "big", "Prime only" to "prime", "Little only" to "little"), affinity) {
            affinity = it; Prefs.setApp("Display", "cpu_affinity", it)
        }
        if (Native.isAdreno()) {
            RowDivider()
            var boost by remember { mutableStateOf(Prefs.getBool("Display", "adreno_boost", false)) }
            ToggleRow("Adreno GPU boost", "Keeps the Adreno GPU at high clock speeds. Helps when the GPU is the bottleneck; increases power draw and heat.", boost) {
                boost = it; Prefs.setBool("Display", "adreno_boost", it)
            }
        }
        GpuDriverRows()   // Adreno only; draws its own divider
        RowDivider()
        RowDivider()
        var texFilter by remember { mutableStateOf(Prefs.getApp("Display", "texture_filter", "none")) }
        ChoiceRow("Texture filter", "Upscales low-resolution textures. Heavier filters reduce performance.",
            listOf("None" to "none", "Bicubic" to "bicubic", "ScaleForce" to "scaleforce", "xBRZ" to "xbrz", "MMPX" to "mmpx", "Anime4K" to "anime4k"), texFilter) {
            texFilter = it; Prefs.setApp("Display", "texture_filter", it)
        }
        RowDivider()
        var texSampling by remember { mutableStateOf(Prefs.getApp("Display", "texture_sampling", "game")) }
        ChoiceRow("Texture sampling", null, listOf("Game's choice" to "game", "Nearest" to "nearest", "Linear" to "linear"), texSampling) {
            texSampling = it; Prefs.setApp("Display", "texture_sampling", it)
        }
    }
    Spacer(Modifier.height(24.dp))
}

/** The chat overlay's look (see ChatOverlay). */
@Composable
fun ChatPage() = Page {
    ChatSettingsPanel()
    Spacer(Modifier.height(24.dp))
}

@Composable
fun AudioPage() = Page {
    SectionTitle("Sound")
    Panel {
        var vol by remember { mutableStateOf(Prefs.getInt("Audio", "volume", 100).toFloat()) }
        SliderRow("Volume", null, vol, 0f..100f, " %") { vol = it; Prefs.setApp("Audio", "volume", it.toInt().toString()); Prefs.apply() }
        RowDivider()
        var audioDriver by remember { mutableStateOf(Prefs.getApp("Audio", "driver", "auto")) }
        ChoiceRow("Audio driver", "AAudio is Android's low-latency audio path (Android 8.1 and newer); OpenSL ES is the older one. The driver can change audio latency and dropouts. Its effect on frame rate depends on the device, so compare using the frame counter. The driver in use is recorded in the log. Applies on the next game start.",
            listOf("Automatic" to "auto", "AAudio" to "aaudio", "OpenSL ES" to "opensl"), audioDriver) {
            audioDriver = it; Prefs.setApp("Audio", "driver", it)
        }
    }
    Spacer(Modifier.height(24.dp))
}

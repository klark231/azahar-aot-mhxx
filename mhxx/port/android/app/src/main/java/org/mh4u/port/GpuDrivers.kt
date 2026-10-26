package org.mh4u.port

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.zip.ZipInputStream
import org.json.JSONObject

/**
 * GPU drivers the player imported (a .zip with a meta.json and the driver's .so, the format the
 * Adreno driver releases use). Each lives in its own folder under the app's private files, which
 * is where Android lets a library be loaded from; the native side loads the chosen one with
 * libadrenotools (see native.cpp, ChooseGpuDriver).
 */
object GpuDrivers {
    class Info(val id: String, val name: String, val description: String, val version: String)

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    val dir: File get() = File(appContext.filesDir, "gpu_drivers").also { it.mkdirs() }

    /** Exists while a custom driver hasn't proven itself: a start that crashed leaves it behind. */
    private val marker: File get() = File(dir, ".loading")

    /** Picking a driver again is the player's retry after a crash. */
    fun clearCrashMarker() { marker.delete() }

    fun list(): List<Info> = (dir.listFiles() ?: emptyArray()).filter { it.isDirectory && !it.name.startsWith(".") }
        .mapNotNull { folder ->
            runCatching {
                val meta = JSONObject(File(folder, "meta.json").readText())
                if (!File(folder, meta.getString("libraryName")).isFile) return@runCatching null
                Info(folder.name, meta.optString("name", folder.name), meta.optString("description"), meta.optString("driverVersion"))
            }.getOrNull()
        }.sortedBy { it.name.lowercase() }

    /** Unpacks a driver .zip. The new driver's id (its folder name), or what went wrong. */
    fun install(uri: Uri): Result<String> = runCatching {
        val temp = File(dir, ".tmp-" + System.nanoTime()).also { it.mkdirs() }
        try {
            var total = 0L
            appContext.contentResolver.openInputStream(uri)!!.use { raw ->
                ZipInputStream(raw).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (entry.isDirectory) continue
                        // flat: only the file's own name, so nothing can land outside the folder
                        val name = entry.name.substringAfterLast('/')
                        if (name.isEmpty() || name.startsWith(".")) continue
                        File(temp, name).outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = zip.read(buf)
                                if (n < 0) break
                                total += n
                                check(total < 300L * 1024 * 1024) { "The archive is too large to be a GPU driver." }
                                out.write(buf, 0, n)
                            }
                        }
                    }
                }
            }
            val metaFile = File(temp, "meta.json")
            check(metaFile.isFile) { "The archive does not contain a meta.json file, so it is not a GPU driver package." }
            val meta = JSONObject(metaFile.readText())
            val library = meta.getString("libraryName")
            check(File(temp, library).isFile) { "The driver library ($library) is missing from the archive." }
            val id = meta.optString("name", library).replace(Regex("[^A-Za-z0-9._-]"), "_").trimStart('.').ifEmpty { "driver" }
            val target = File(dir, id)
            target.deleteRecursively()
            check(temp.renameTo(target)) { "The driver could not be stored." }
            id
        } finally {
            temp.deleteRecursively()
        }
    }

    fun remove(id: String) {
        if (id.isNotEmpty() && !id.startsWith(".") && !id.contains('/')) File(dir, id).deleteRecursively()
    }
}

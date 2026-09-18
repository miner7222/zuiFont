package io.miner7222.zuifont

import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.IBinder
import android.os.Parcel
import android.provider.OpenableColumns
import java.io.IOException
import java.util.Locale

/**
 * Vendor API chain for ZUXOS/ZUI.
 *
 * Upload to content://com.zui.themes.provider.themesprovider, then call
 * transaction 2 (changeSystemFont(fontPath, fontName)) on the binder obtained
 * from ServiceManager.getService("zuithemes"). ThemeService copies the file to
 * /data/system/theme/fonts and updates the system-wide configuration.
 */
class FontSwitchException(val messageRes: Int, val arg: String? = null) : Exception()

object FontSwitcher {

    const val PERM_THEME_MANAGER = "zui.permission.ACCESS_THEME_MANAGER"
    const val PROVIDER_BASE = "content://com.zui.themes.provider.themesprovider"

    private const val THEME_SERVICE_NAME = "zuithemes"
    private const val THEME_DESCRIPTOR = "zui.themes.IThemeService"
    private const val TRANSACTION_CHANGE_SYSTEM_FONT = 2
    private const val TRANSACTION_GET_SYSTEM_FONT = 4
    private const val COPY_BUFFER_SIZE = 64 * 1024
    private const val POLL_ATTEMPTS = 24
    private const val POLL_INTERVAL_MS = 500L

    fun requirePermission(context: Context) {
        if (context.checkSelfPermission(PERM_THEME_MANAGER) != PackageManager.PERMISSION_GRANTED) {
            throw FontSwitchException(R.string.err_permission, PERM_THEME_MANAGER)
        }
    }

    fun themeService(): IBinder {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val getService = serviceManager.getDeclaredMethod("getService", String::class.java)
        getService.isAccessible = true
        val binder = getService.invoke(null, THEME_SERVICE_NAME) as? IBinder
        return binder ?: throw FontSwitchException(R.string.err_service)
    }

    fun queryDisplayName(resolver: ContentResolver, uri: Uri): String {
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor: Cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) {
                        cursor.getString(index)?.takeIf { it.isNotBlank() }?.let { return it }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment.orEmpty()
    }

    fun sniffFontType(resolver: ContentResolver, uri: Uri): String? {
        val header = try {
            resolver.openInputStream(uri)?.use { input ->
                ByteArray(4).also { buffer -> if (input.read(buffer) < 4) return null }
            }
        } catch (e: IOException) {
            return null
        } ?: return null
        val magic = (header[0].toLong() and 0xff shl 24) or
            (header[1].toLong() and 0xff shl 16) or
            (header[2].toLong() and 0xff shl 8) or
            (header[3].toLong() and 0xff)
        return when (magic) {
            0x00010000L, 0x74727565L -> "ttf"
            0x4F54544FL -> "otf"
            else -> null
        }
    }

    fun uploadFont(resolver: ContentResolver, source: Uri, targetName: String): Long {
        val target = Uri.parse("$PROVIDER_BASE/$targetName")
        var total = 0L
        try {
            resolver.openInputStream(source).use { input ->
                if (input == null) throw FontSwitchException(R.string.err_stream)
                resolver.openOutputStream(target, "w").use { output ->
                    if (output == null) throw FontSwitchException(R.string.err_stream)
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        total += read
                    }
                    output.flush()
                }
            }
        } catch (e: NullPointerException) {
            throw FontSwitchException(R.string.err_stream)
        }
        return total
    }

    fun changeSystemFont(binder: IBinder, fontPath: String?, fontName: String) {
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(THEME_DESCRIPTOR)
            data.writeString(fontPath)
            data.writeString(fontName)
            binder.transact(TRANSACTION_CHANGE_SYSTEM_FONT, data, null, IBinder.FLAG_ONEWAY)
        } finally {
            data.recycle()
        }
    }

    fun getSystemFont(binder: IBinder): String? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(THEME_DESCRIPTOR)
            binder.transact(TRANSACTION_GET_SYSTEM_FONT, data, reply, 0)
            reply.readException()
            reply.readString()
        } catch (_: Exception) {
            null
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    fun pollSystemFont(binder: IBinder, expected: String): String? {
        var last: String? = null
        repeat(POLL_ATTEMPTS) {
            try {
                Thread.sleep(POLL_INTERVAL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return last
            }
            last = getSystemFont(binder)
            if (last == expected) return last
        }
        return last
    }

    fun fontExtension(fileName: String?): String? {
        val lower = fileName?.lowercase(Locale.ROOT) ?: return null
        return when {
            lower.endsWith(".ttf") -> "ttf"
            lower.endsWith(".otf") -> "otf"
            else -> null
        }
    }

    fun sanitizeBaseName(fileName: String?): String {
        var base = fileName.orEmpty()
        val dot = base.lastIndexOf('.')
        if (dot > 0) base = base.substring(0, dot)
        base = base.replace(Regex("[^A-Za-z0-9._-]"), "_")
        if (base.length > 24) base = base.substring(0, 24)
        return base.ifEmpty { "Custom" }
    }
}

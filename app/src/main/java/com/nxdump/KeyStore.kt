package com.nxdump

import android.content.Context
import android.net.Uri
import com.nxdump.core.Keys
import java.io.File

/** Keeps prod.keys / title.keys in app-private storage. */
object KeyStore {
    private fun dir(ctx: Context) = File(ctx.filesDir, "keys").apply { mkdirs() }
    fun prodFile(ctx: Context) = File(dir(ctx), "prod.keys")
    fun titleFile(ctx: Context) = File(dir(ctx), "title.keys")

    fun load(ctx: Context): Keys? {
        val p = prodFile(ctx)
        if (!p.exists()) return null
        return try {
            val k = Keys()
            p.inputStream().use { k.parse(it, false) }
            val t = titleFile(ctx)
            if (t.exists()) t.inputStream().use { k.parse(it, true) }
            k
        } catch (e: Exception) {
            null
        }
    }

    /** Returns an error message, or null on success. */
    fun import(ctx: Context, uri: Uri, title: Boolean): String? {
        return try {
            val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return "Could not read the selected file"
            if (bytes.size > 8 * 1024 * 1024) return "That file is too large to be a keys file"
            val probe = Keys()
            probe.parse(bytes.inputStream(), title)
            if (title) {
                if (probe.titleKeyCount() == 0) return "No title keys found (expected lines like rightsid = key)"
                titleFile(ctx).writeBytes(bytes)
            } else {
                if (!probe.hasHeaderKey()) return "This doesn't look like prod.keys (header_key is missing)"
                if (probe.maxMasterKey() < 0) return "prod.keys has no master_key entries"
                prodFile(ctx).writeBytes(bytes)
            }
            null
        } catch (e: Exception) {
            e.message ?: "Import failed"
        }
    }

    fun summary(keys: Keys): String =
        "${keys.count()} keys \u00B7 master keys up to %02X".format(keys.maxMasterKey())
}

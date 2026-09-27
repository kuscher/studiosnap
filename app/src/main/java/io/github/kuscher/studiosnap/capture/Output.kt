package io.github.kuscher.studiosnap.capture

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import io.github.kuscher.studiosnap.service.SnapService
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Writes captures to the clipboard and to the gallery. Pure device I/O; no network. */
object Output {

    fun defaultName(now: Date = Date()): String =
        "Snap " + SimpleDateFormat("yyyy-MM-dd 'at' HH.mm.ss", Locale.US).format(now)

    /** Writes a working PNG into cache/captures for the editor to reopen. Returns the file. */
    fun saveWorkingFile(ctx: Context, bmp: Bitmap, name: String): File {
        val dir = File(ctx.cacheDir, "captures").apply { mkdirs() }
        val f = File(dir, "$name.png")
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f
    }

    /** Puts a PNG of [bmp] on the clipboard as an image content URI other apps can paste. */
    fun copyToClipboard(ctx: Context, bmp: Bitmap, name: String) {
        try {
            val dir = File(ctx.cacheDir, "clip").apply { mkdirs() }
            // one stable file so we don't leak; overwrite each copy
            val f = File(dir, "clip.png")
            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
            val clip = ClipData.newUri(ctx.contentResolver, name, uri)
            val cm = ctx.getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(clip)
        } catch (e: Exception) {
            Log.w(SnapService.TAG, "clipboard failed: $e")
        }
    }

    /** Puts plain text on the clipboard. */
    fun copyText(ctx: Context, text: String) {
        try {
            val cm = ctx.getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("StudioSnap text", text))
        } catch (e: Exception) { Log.w(SnapService.TAG, "copyText failed: $e") }
    }

    /** Saves a PNG into Pictures/StudioSnap via MediaStore. Returns the new item's Uri. */
    fun saveToGallery(ctx: Context, bmp: Bitmap, name: String): Uri? {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/StudioSnap")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = ctx.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            resolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (e: Exception) {
            Log.w(SnapService.TAG, "gallery save failed: $e")
            null
        }
    }
}

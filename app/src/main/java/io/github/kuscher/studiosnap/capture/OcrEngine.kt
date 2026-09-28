package io.github.kuscher.studiosnap.capture

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import io.github.kuscher.studiosnap.service.SnapService

/**
 * On-device OCR via ML Kit's bundled Latin text recognizer. Fully offline — the model ships in the
 * APK, so this needs no internet (and StudioSnap has no INTERNET permission). Used to pull text out
 * of pixels the accessibility tree can't see (images, canvas, PDFs, remote desktops).
 */
object OcrEngine {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /** Recognizes text in [bmp]; [cb] receives the text (empty on failure), on the main thread. */
    fun recognize(bmp: Bitmap, cb: (String) -> Unit) {
        runCatching {
            recognizer.process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener { cb(it.text) }
                .addOnFailureListener { e -> Log.w(SnapService.TAG, "ocr: $e"); cb("") }
        }.onFailure { Log.w(SnapService.TAG, "ocr setup: $it"); cb("") }
    }
}

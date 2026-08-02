// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package utils

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.createBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

internal data class QrCodePixels(
    val size: Int,
    val values: IntArray,
)

internal fun generateQrCodePixels(
    text: String,
    sizePx: Int,
): QrCodePixels {
    require(text.isNotBlank()) { "QR code text is required" }
    require(sizePx > 0) { "QR code size must be positive" }
    val matrix = MultiFormatWriter().encode(
        text,
        BarcodeFormat.QR_CODE,
        sizePx,
        sizePx,
        mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
            EncodeHintType.MARGIN to 1,
        ),
    )
    val pixels = IntArray(sizePx * sizePx)
    for (y in 0 until sizePx) {
        val offset = y * sizePx
        for (x in 0 until sizePx) {
            pixels[offset + x] = if (matrix[x, y]) QrBlack else QrWhite
        }
    }
    return QrCodePixels(size = sizePx, values = pixels)
}

internal fun generateQrCodeImageBitmap(
    text: String,
    sizePx: Int,
): ImageBitmap {
    val pixels = generateQrCodePixels(text, sizePx)
    return createBitmap(pixels.size, pixels.size)
        .apply {
            setPixels(
                pixels.values,
                0,
                pixels.size,
                0,
                0,
                pixels.size,
                pixels.size,
            )
        }
        .asImageBitmap()
}

private const val QrBlack = -0x1000000
private const val QrWhite = -0x1

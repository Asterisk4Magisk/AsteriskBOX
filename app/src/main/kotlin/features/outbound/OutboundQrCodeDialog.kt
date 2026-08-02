// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.outbound

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.asterisk.zcc.abox.R
import ui.theme.AsteriskShapeTokens
import utils.generateQrCodeImageBitmap

@Composable
internal fun OutboundQrCodeDialog(
    title: String,
    url: String,
    onDismissRequest: () -> Unit,
) {
    val qrCodeState by produceState<QrCodeState>(QrCodeState.Loading, url) {
        value = withContext(Dispatchers.Default) {
            runCatching { generateQrCodeImageBitmap(url, QrCodeBitmapSizePx) }
                .fold(
                    onSuccess = QrCodeState::Ready,
                    onFailure = { QrCodeState.Failed },
                )
        }
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .widthIn(max = 380.dp),
            shape = AsteriskShapeTokens.PageCard,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = title,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(18.dp))
                when (val state = qrCodeState) {
                    QrCodeState.Loading -> CircularProgressIndicator()
                    QrCodeState.Failed -> {
                        Text(
                            text = stringResource(R.string.outbound_qr_generate_failed),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                        )
                    }
                    is QrCodeState.Ready -> {
                        Image(
                            bitmap = state.bitmap,
                            contentDescription = null,
                            modifier = Modifier
                                .size(288.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.White)
                                .padding(8.dp),
                        )
                    }
                }
            }
        }
    }
}

private sealed interface QrCodeState {
    data object Loading : QrCodeState
    data object Failed : QrCodeState
    data class Ready(val bitmap: ImageBitmap) : QrCodeState
}

private const val QrCodeBitmapSizePx = 768

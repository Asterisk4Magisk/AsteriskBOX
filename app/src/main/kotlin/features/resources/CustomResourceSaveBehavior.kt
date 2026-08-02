// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.resources

import app.CustomResourceFileState

internal sealed interface CustomResourceSaveFollowUp {
    data class EnqueueDownload(
        val request: ResourceFileUpdateRequest.Custom,
    ) : CustomResourceSaveFollowUp

    data object SelectLocalFile : CustomResourceSaveFollowUp

    data object None : CustomResourceSaveFollowUp
}

internal fun planCustomResourceSaveFollowUp(
    file: CustomResourceFileState,
    isNew: Boolean,
    options: ResourceFileUpdateOptions,
    customResourceFiles: List<CustomResourceFileState>,
): CustomResourceSaveFollowUp {
    if (file.url.isNotBlank()) {
        return CustomResourceSaveFollowUp.EnqueueDownload(
            request = ResourceFileUpdateRequest.Custom(
                file = file,
                options = options,
                customResourceFiles = customResourceFiles,
            ),
        )
    }
    return if (isNew) {
        CustomResourceSaveFollowUp.SelectLocalFile
    } else {
        CustomResourceSaveFollowUp.None
    }
}

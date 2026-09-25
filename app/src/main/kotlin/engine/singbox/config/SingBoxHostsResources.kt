// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.singbox.config

import app.CustomResourceFileState
import features.resources.isHostsResource

internal fun resolveHostsResourcePaths(
    resourceIds: List<Int>,
    resources: List<CustomResourceFileState>,
    paths: Map<Int, String>,
): List<String> = resourceIds.distinct().mapNotNull { id ->
    if (resources.none { it.id == id && it.name.isHostsResource() }) return@mapNotNull null
    paths[id]?.takeIf(String::isNotBlank)
}

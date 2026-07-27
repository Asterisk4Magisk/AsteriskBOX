package ui.theme

import app.modes.ColorModeDark
import app.modes.ColorModeLight

internal data class ThemeResolution(
    val isDark: Boolean,
    val usesSystemDynamicColor: Boolean,
    val usesCustomSeed: Boolean,
)

internal fun resolveTheme(
    colorMode: Int,
    systemDark: Boolean,
    supportsSystemDynamicColor: Boolean,
    hasCustomSeed: Boolean,
): ThemeResolution {
    val isDark = when (colorMode) {
        ColorModeLight -> false
        ColorModeDark -> true
        else -> systemDark
    }
    return ThemeResolution(
        isDark = isDark,
        usesSystemDynamicColor = supportsSystemDynamicColor && !hasCustomSeed,
        usesCustomSeed = hasCustomSeed,
    )
}

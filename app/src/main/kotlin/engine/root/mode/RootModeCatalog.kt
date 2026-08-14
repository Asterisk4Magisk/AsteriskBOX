// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.mode

import app.modes.RunModeBpf2Socks
import app.modes.RunModeEbpf
import app.modes.RunModeTproxy
import app.modes.RunModeTun
import app.modes.RunModeTun2Socks
import engine.root.daemon.config.AsteriskdMode
import engine.root.config.RootConfigBuildContext
import org.asterisk.zcc.abox.R

internal object RootModeCatalog {
    val definitions: List<RootModeDefinition> = validatedDefinitions(
        RootModeDefinition(
            runMode = RunModeTproxy,
            daemonMode = AsteriskdMode.Tproxy,
            rootRequiredErrorResId = R.string.error_tproxy_root_required,
            startFailedErrorResId = R.string.error_tproxy_start_failed,
            buildConfig = RootConfigBuildContext::buildTproxyStartConfig,
        ),
        RootModeDefinition(
            runMode = RunModeTun,
            daemonMode = AsteriskdMode.Tun,
            rootRequiredErrorResId = R.string.error_tun_root_required,
            startFailedErrorResId = R.string.error_tun_start_failed,
            buildConfig = RootConfigBuildContext::buildTunStartConfig,
        ),
        RootModeDefinition(
            runMode = RunModeTun2Socks,
            daemonMode = AsteriskdMode.Tun2Socks,
            rootRequiredErrorResId = R.string.error_tun2socks_root_required,
            startFailedErrorResId = R.string.error_tun2socks_start_failed,
            buildConfig = RootConfigBuildContext::buildTun2SocksStartConfig,
        ),
        RootModeDefinition(
            runMode = RunModeBpf2Socks,
            daemonMode = AsteriskdMode.Bpf2Socks,
            rootRequiredErrorResId = R.string.error_bpf2socks_root_required,
            startFailedErrorResId = R.string.error_bpf2socks_start_failed,
            buildConfig = RootConfigBuildContext::buildBpf2SocksStartConfig,
        ),
        RootModeDefinition(
            runMode = RunModeEbpf,
            daemonMode = AsteriskdMode.Ebpf,
            rootRequiredErrorResId = R.string.error_ebpf_root_required,
            startFailedErrorResId = R.string.error_ebpf_start_failed,
            buildConfig = RootConfigBuildContext::buildEbpfStartConfig,
        ),
    )

    private val byRunMode = definitions.associateBy(RootModeDefinition::runMode)

    fun find(runMode: Int): RootModeDefinition? = byRunMode[runMode]

    fun require(runMode: Int): RootModeDefinition {
        return requireNotNull(find(runMode)) { "Unsupported ROOT run mode: $runMode" }
    }
}

private fun validatedDefinitions(vararg values: RootModeDefinition): List<RootModeDefinition> {
    val definitions = values.toList()
    require(definitions.map(RootModeDefinition::runMode).distinct().size == definitions.size) {
        "Duplicate ROOT run mode"
    }
    require(definitions.map(RootModeDefinition::daemonMode).distinct().size == definitions.size) {
        "Duplicate asteriskd mode"
    }
    return definitions
}

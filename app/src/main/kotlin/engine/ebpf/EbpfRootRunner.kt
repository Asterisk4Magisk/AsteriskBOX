// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.ebpf

import engine.root.RootModeRunner
import engine.root.RootReadinessCheck
import engine.root.appendScript
import engine.root.buildRootPortReadyCommand
import engine.root.toNetstatPortHexMarker
import system.AndroidRootShellGateway
import system.ShellExecOptions
import utils.shellQuote

internal class EbpfRootRunner(
    rootAccess: AndroidRootShellGateway,
) : RootModeRunner<EbpfStartConfig>(
    rootAccess = rootAccess,
    modeName = "eBPF",
    logTag = LogTag,
) {
    override fun buildSetupRulesCommand(
        config: EbpfStartConfig,
        cleanupExistingRules: Boolean,
    ): String = ":"

    override fun buildCleanupRulesCommand(): String = ""

    override fun buildReadinessCheck(config: EbpfStartConfig): RootReadinessCheck {
        return RootReadinessCheck(
            description = "eBPF inbound port ${config.listenPort}",
            command = buildRootPortReadyCommand(config.listenPort),
            failureMessage = "sing-box started but eBPF inbound port ${config.listenPort} is not ready",
        )
    }

    override suspend fun collectReadinessDiagnostics(config: EbpfStartConfig): String {
        val portHex = config.listenPort.toNetstatPortHexMarker()
        val command = $$"""
            pid="$(cat $${config.root.runtimeLayout.pidPath.shellQuote()} 2>/dev/null || true)"
            echo "== eBPF inbound =="
            echo "listenPort=$${config.listenPort}"
            echo "listenPortHex=$$portHex"
            echo "sharedNetwork=$${config.sharedNetworkInterfaces.joinToString(",").shellQuote()}"
            echo "== netstat =="
            netstat -an 2>&1 | head -n 40 || true
            if [ -n "$pid" ]; then
                for proc_file in /proc/"$pid"/net/tcp6 /proc/"$pid"/net/tcp; do
                    echo "== $proc_file =="
                    head -n 12 "$proc_file" 2>&1 || true
                done
            fi
            echo "== core error log =="
            tail -n 80 $${config.root.coreLogPaths.errorLogPath.shellQuote()} 2>&1 || true
        """.trimIndent()
        val result = rootAccess.exec(command, ShellExecOptions(logFailure = false))
        return result.stdout.ifBlank { result.stderr }
    }

    override fun StringBuilder.appendStartupSummary(config: EbpfStartConfig) {
        appendScript("echo \"eBPF inbound port: ${config.listenPort}\"")
        appendScript(
            "echo \"eBPF shared interfaces: ${config.sharedNetworkInterfaces.joinToString(",").ifBlank { "disabled" }.shellQuote()}\"",
        )
    }

    override fun StringBuilder.appendStartupFailureDiagnostics(config: EbpfStartConfig) {
        appendScript(
            """
                echo
                echo "eBPF inbound netstat snapshot:"
                netstat -an || true
            """,
        )
    }

    private companion object {
        private const val LogTag = "EbpfRootRunner"
    }
}

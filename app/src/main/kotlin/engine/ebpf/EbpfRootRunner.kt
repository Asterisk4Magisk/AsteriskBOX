// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.ebpf

import engine.root.RootModeRunner
import engine.root.RootReadinessCheck
import engine.root.RootSingBoxGid
import engine.root.RootSingBoxUid
import engine.root.appendScript
import engine.root.buildRootProcessMatchTest
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
            description = "stable eBPF sing-box process",
            command = buildStableProcessReadyCommand(config),
            failureMessage = "sing-box eBPF process did not remain ready",
        )
    }

    override suspend fun collectReadinessDiagnostics(config: EbpfStartConfig): String {
        val command = $$"""
            pid="$(cat $${config.root.runtimeLayout.pidPath.shellQuote()} 2>/dev/null || true)"
            echo "== eBPF inbound =="
            echo "sharedNetwork=$${config.sharedNetworkInterfaces.joinToString(",").shellQuote()}"
            echo "== netstat =="
            netstat -an 2>&1 | head -n 40 || true
            if [ -n "$pid" ]; then
                for proc_file in /proc/"$pid"/net/tcp6 /proc/"$pid"/net/tcp /proc/"$pid"/net/udp6 /proc/"$pid"/net/udp; do
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

    private fun buildStableProcessReadyCommand(config: EbpfStartConfig): String {
        val runtimeLayout = config.root.runtimeLayout
        return buildString {
            appendScript(
                $$"""
                pid="$(cat $${runtimeLayout.pidPath.shellQuote()} 2>/dev/null || true)"
                [ -n "$pid" ] || exit 1
                """,
            )
            appendProcessMatchOrExit(runtimeLayout.singBoxCorePath)
            appendScript(
                $$"""
                initial_pid="$pid"
                sleep 1
                pid="$(cat $${runtimeLayout.pidPath.shellQuote()} 2>/dev/null || true)"
                [ "$pid" = "$initial_pid" ] || exit 1
                """,
            )
            appendProcessMatchOrExit(runtimeLayout.singBoxCorePath)
        }
    }

    private fun StringBuilder.appendProcessMatchOrExit(executablePath: String) {
        append(
            buildRootProcessMatchTest(
                executablePath = executablePath,
                uid = RootSingBoxUid,
                gid = RootSingBoxGid,
            ).trimEnd(),
        )
        append(" || exit 1\n")
    }

    private companion object {
        private const val LogTag = "EbpfRootRunner"
    }
}

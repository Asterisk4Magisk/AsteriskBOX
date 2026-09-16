// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.subscription

import app.AppState
import app.OutboundGroupState
import app.nextAvailableOutboundGroupId
import features.outbound.OutboundCommandResult
import features.outbound.OutboundRepository
import features.subscription.usecase.OutboundSubscriptionUpdateResult
import features.subscription.usecase.OutboundSubscriptionUpdater
import features.subscription.usecase.SubscriptionUpdateTrigger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class SubscriptionInstallConfigUseCase(
    private val currentState: () -> AppState,
    private val repository: OutboundRepository,
    private val updater: OutboundSubscriptionUpdater,
) {
    private val installMutex = Mutex()

    suspend fun install(config: SubscriptionInstallConfig): OutboundSubscriptionUpdateResult {
        val group = installMutex.withLock { findOrCreateGroup(config) }
        return updater.update(group.id, SubscriptionUpdateTrigger.MANUAL)
    }

    private suspend fun findOrCreateGroup(config: SubscriptionInstallConfig): OutboundGroupState {
        repeat(3) {
            val state = currentState()
            state.outboundGroups.firstOrNull { it.url == config.url }?.let { return it }
            val group = OutboundGroupState(
                id = state.nextAvailableOutboundGroupId(),
                name = config.name,
                url = config.url,
                userAgent = config.userAgent,
            )
            when (val result = repository.saveGroup(expected = null, replacement = group)) {
                is OutboundCommandResult.GroupSaved -> return result.group
                OutboundCommandResult.Conflict -> Unit
                is OutboundCommandResult.Invalid -> throw result.error
                is OutboundCommandResult.PersistenceFailed -> throw result.error
                else -> error("Unexpected subscription group save result")
            }
        }
        error("Settings changed during subscription import")
    }
}

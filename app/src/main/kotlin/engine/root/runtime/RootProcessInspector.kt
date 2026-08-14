// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.runtime

import engine.root.daemon.config.AsteriskdOwner
import engine.root.daemon.control.AsteriskdPhase

internal suspend fun RootSupervisorController.runningCorePid(): Int? =
    status().result.snapshot
        ?.takeIf { snapshot -> snapshot.owner == AsteriskdOwner.AsteriskBox && snapshot.phase == AsteriskdPhase.Running }
        ?.corePid

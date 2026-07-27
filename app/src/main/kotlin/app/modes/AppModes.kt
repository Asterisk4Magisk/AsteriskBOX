// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package app.modes

const val RunModeVpnService = 0
const val RunModeTproxy = 1
const val RunModeTun2Socks = 2
const val RunModeTun = 3
const val RunModeBpf2Socks = 4

fun Int.isRootRunMode(): Boolean {
    return this == RunModeTproxy ||
        this == RunModeTun ||
        this == RunModeTun2Socks ||
        this == RunModeBpf2Socks
}

const val SingBoxModeRule = 0
const val SingBoxModeGlobal = 1
const val SingBoxModeDirect = 2

const val SingBoxTunStackSystem = 0
const val SingBoxTunStackGvisor = 1
const val SingBoxTunStackMixed = 2

const val ProxyAppListModeBlacklist = 0
const val ProxyAppListModeWhitelist = 1
const val ProxyAppListModeGlobal = 2

const val SingBoxProxyLayoutAuto = 0
const val SingBoxProxyLayoutSingle = 1
const val SingBoxProxyLayoutDouble = 2
const val SingBoxProxyLayoutMultiple = 3

const val SingBoxProxySortDefault = 0
const val SingBoxProxySortName = 1
const val SingBoxProxySortDelay = 2

const val OutboundListLayoutAuto = 0
const val OutboundListLayoutSingle = 1
const val OutboundListLayoutDouble = 2
const val OutboundListLayoutMultiple = 3

const val OutboundListSortDefault = 0
const val OutboundListSortName = 1
const val OutboundListSortLatency = 2
const val OutboundListSortType = 3

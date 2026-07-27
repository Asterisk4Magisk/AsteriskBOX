// Copyright 2026, Asterisk4Magisk contributors
// SPDX-License-Identifier: GPL-3.0

#ifndef BPF2SOCKS_CONNECTED_UDP_REDIRECT_H
#define BPF2SOCKS_CONNECTED_UDP_REDIRECT_H

#include <stdbool.h>

bool bpf2socks_connected_udp_rewrites_peer_during_connect(int family);

#endif

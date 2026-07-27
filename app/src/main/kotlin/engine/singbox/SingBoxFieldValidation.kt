// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.singbox

internal const val SingBoxUnsigned16Max = 65_535
internal const val SingBoxUnsigned32Max = 4_294_967_295L

internal fun isSingBoxUnsigned16(value: String): Boolean =
    value.trim().toIntOrNull() in 0..SingBoxUnsigned16Max

internal fun isSingBoxUnsigned32(value: String): Boolean =
    value.trim().toLongOrNull() in 0..SingBoxUnsigned32Max

internal fun isSingBoxPortRange(value: String): Boolean {
    val parts = value.trim().split(':', limit = 2)
    if (parts.size != 2 || parts.all(String::isBlank)) return false
    val start = parts[0].takeIf(String::isNotBlank)?.toIntOrNull()
    val end = parts[1].takeIf(String::isNotBlank)?.toIntOrNull()
    if (parts[0].isNotBlank() && start !in 0..SingBoxUnsigned16Max) return false
    if (parts[1].isNotBlank() && end !in 0..SingBoxUnsigned16Max) return false
    return start == null || end == null || start <= end
}

internal fun isSingBoxDnsQueryType(value: String): Boolean {
    val normalized = value.trim()
    return isSingBoxUnsigned16(normalized) || normalized in SingBoxDnsQueryTypeNames
}

internal fun isSingBoxDnsRCode(value: String): Boolean {
    val normalized = value.trim()
    return normalized.toIntOrNull() != null || normalized in SingBoxDnsRCodeNames
}

private val SingBoxDnsQueryTypeNames = setOf(
    "A", "AAAA", "AFSDB", "AMTRELAY", "ANY", "APL", "ATMA", "AVC", "AXFR",
    "CAA", "CDNSKEY", "CDS", "CERT", "CNAME", "CSYNC", "DHCID", "DLV",
    "DNAME", "DNSKEY", "DS", "EID", "EUI48", "EUI64", "GID", "GPOS",
    "HINFO", "HIP", "HTTPS", "IPSECKEY", "ISDN", "IXFR", "KEY", "KX",
    "L32", "L64", "LOC", "LP", "MAILA", "MAILB", "MB", "MD", "MF", "MG",
    "MINFO", "MR", "MX", "NAPTR", "NID", "NIMLOC", "NINFO", "NS",
    "NSAP-PTR", "NSEC", "NSEC3", "NSEC3PARAM", "NULL", "NXNAME", "NXT",
    "None", "OPENPGPKEY", "OPT", "PTR", "PX", "RESINFO", "RKEY", "RP",
    "RRSIG", "RT", "Reserved", "SIG", "SMIMEA", "SOA", "SPF", "SRV",
    "SSHFP", "SVCB", "TA", "TALINK", "TKEY", "TLSA", "TSIG", "TXT", "UID",
    "UINFO", "UNSPEC", "URI", "X25", "ZONEMD",
)

private val SingBoxDnsRCodeNames = setOf(
    "NOERROR", "FORMERR", "SERVFAIL", "NXDOMAIN", "NOTIMP", "NOTIMPL",
    "REFUSED", "YXDOMAIN", "YXRRSET", "NXRRSET", "NOTAUTH", "NOTZONE",
    "DSOTYPENI", "BADSIG", "BADKEY", "BADTIME", "BADMODE", "BADNAME",
    "BADALG", "BADTRUNC", "BADCOOKIE",
)

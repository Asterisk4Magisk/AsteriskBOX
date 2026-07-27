// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.resources

import app.ProjectInfo

const val ResourceFileSourceDefault = 0
const val ResourceFileSourceCustom = 1

const val ResourceFileSingBoxCoreName = "sing-box"
const val ResourceFileGeositeCategoryAdsAllName = "geosite-category-ads-all.srs"
const val ResourceFileGeositeGoogleName = "geosite-google.srs"
const val ResourceFileGeositeCnName = "geosite-cn.srs"
const val ResourceFileGeoipCnName = "geoip-cn.srs"
const val ResourceFileDirectCidrIpv4Name = "direct-cidr-v4.txt"
const val ResourceFileDirectCidrIpv6Name = "direct-cidr-v6.txt"

const val SingBoxCoreVersion = ProjectInfo.SING_BOX_VERSION

const val ResourceFileGeositeCategoryAdsAllUrl =
    "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-category-ads-all.srs"
const val ResourceFileGeositeGoogleUrl =
    "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-google.srs"
const val ResourceFileGeositeCnUrl =
    "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-cn.srs"
const val ResourceFileGeoipCnUrl =
    "https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set/geoip-cn.srs"
const val ResourceFileDirectCidrIpv4Url =
    "https://raw.githubusercontent.com/mayaxcn/china-ip-list/master/chnroute.txt"
const val ResourceFileDirectCidrIpv6Url =
    "https://raw.githubusercontent.com/mayaxcn/china-ip-list/master/chnroute_v6.txt"

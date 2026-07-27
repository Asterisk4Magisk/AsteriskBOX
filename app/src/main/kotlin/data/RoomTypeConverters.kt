// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package data

import androidx.room.TypeConverter

internal class RoomTypeConverters {
    @TypeConverter
    fun encodeStringList(values: List<String>): String = StringListJson.encode(values)

    @TypeConverter
    fun decodeStringList(payload: String): List<String> = StringListJson.decode(payload)
}

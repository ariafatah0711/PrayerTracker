package com.prayertracker.app.core.database

import androidx.room.TypeConverter
import com.prayertracker.app.core.model.PrayerName
import com.prayertracker.app.core.model.PrayerStatus
import com.prayertracker.app.core.model.ReminderType
import com.prayertracker.app.core.model.SyncStatus
import com.prayertracker.app.core.model.UserResponse

class Converters {
    @TypeConverter
    fun fromPrayerName(value: PrayerName?): String? = value?.name

    @TypeConverter
    fun toPrayerName(value: String?): PrayerName? = value?.let { PrayerName.valueOf(it) }

    @TypeConverter
    fun fromPrayerStatus(value: PrayerStatus?): String? = value?.name

    @TypeConverter
    fun toPrayerStatus(value: String?): PrayerStatus? = value?.let { PrayerStatus.valueOf(it) }

    @TypeConverter
    fun fromSyncStatus(value: SyncStatus?): String? = value?.name

    @TypeConverter
    fun toSyncStatus(value: String?): SyncStatus? = value?.let { SyncStatus.valueOf(it) }

    @TypeConverter
    fun fromReminderType(value: ReminderType?): String? = value?.name

    @TypeConverter
    fun toReminderType(value: String?): ReminderType? = value?.let { ReminderType.valueOf(it) }

    @TypeConverter
    fun fromUserResponse(value: UserResponse?): String? = value?.name

    @TypeConverter
    fun toUserResponse(value: String?): UserResponse? = value?.let { UserResponse.valueOf(it) }
}

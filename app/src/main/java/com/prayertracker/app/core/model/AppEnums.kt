package com.prayertracker.app.core.model

enum class SyncStatus {
    PENDING_SYNC,
    SYNCED,
    FAILED
}

enum class ReminderType {
    INITIAL,
    SNOOZE_NO,
    OTW_FOLLOWUP
}

enum class UserResponse {
    YES,
    NO,
    OTW,
    TIMEOUT,
    DISMISSED
}

package com.prayertracker.app.core.model

enum class PrayerStatus(val displayName: String) {
    PENDING("Menunggu"),
    OTW("OTW / Bersiap"),
    COMPLETED("Selesai"),
    MISSED("Terlewat"),
    QADHA_COMPLETED("Qadha Selesai");

    val isTerminal: Boolean
        get() = this == COMPLETED || this == QADHA_COMPLETED

    val canQadha: Boolean
        get() = this == MISSED

    val canConfirm: Boolean
        get() = this == PENDING || this == OTW
}

package com.prayertracker.app.core.util

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Utilitas terpusat untuk parsing, pemformatan waktu & tanggal,
 * serta normalisasi serial Google Sheets untuk seluruh aplikasi.
 */
object PrayerDateTimeUtils {

    val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        .withZone(ZoneId.systemDefault())

    val DATE_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .withZone(ZoneId.systemDefault())

    val DISPLAY_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale("id", "ID"))

    /**
     * Format ISO date string "yyyy-MM-dd" ke tampilan bahasa Indonesia "d MMMM yyyy".
     */
    fun formatDateNice(dateStr: String): String {
        return try {
            val parsed = LocalDate.parse(normalizeSheetDate(dateStr))
            parsed.format(DISPLAY_DATE_FORMATTER)
        } catch (_: Exception) {
            dateStr
        }
    }

    /**
     * Format epoch millis ke format jam:menit "HH:mm".
     */
    fun formatEpoch(epoch: Long?): String {
        if (epoch == null || epoch <= 0L) return "-"
        return try {
            TIME_FORMATTER.format(Instant.ofEpochMilli(epoch))
        } catch (_: Exception) {
            "-"
        }
    }

    /**
     * Format epoch millis ke format tanggal + jam "yyyy-MM-dd HH:mm" (standar sheet & backup).
     */
    fun formatEpochWithDate(epoch: Long?): String {
        if (epoch == null || epoch <= 0L) return "-"
        return try {
            DATE_TIME_FORMATTER.format(Instant.ofEpochMilli(epoch))
        } catch (_: Exception) {
            "-"
        }
    }

    /**
     * Format epoch millis ke format ISO tanggal "yyyy-MM-dd".
     */
    fun formatEpochDate(epoch: Long?): String {
        if (epoch == null || epoch <= 0L) return "-"
        return try {
            Instant.ofEpochMilli(epoch)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
                .toString()
        } catch (_: Exception) {
            "-"
        }
    }

    /**
     * Menghitung batas akhir efektif.
     * Untuk salat Isya yang batas akhirnya Subuh esok harinya (endEpoch <= schedEpoch),
     * tambahkan 24 jam (86.400.000 ms) agar tidak dianggap kadaluarsa di tengah malam.
     */
    fun calculateEffectiveEndTime(scheduledEpoch: Long, endEpoch: Long): Long {
        return if (endEpoch > 0 && scheduledEpoch > 0 && endEpoch <= scheduledEpoch) {
            endEpoch + 86_400_000L
        } else {
            endEpoch
        }
    }

    /**
     * Parser cerdas multi-format untuk Jam Selesai dari Google Sheets:
     * 1. Mendukung serial number Sheets (e.g. 46278.69)
     * 2. Mendukung datetime "yyyy-MM-dd HH:mm" atau "yyyy-MM-dd H:mm" (1 digit jam)
     * 3. Mendukung format slash "dd/MM/yyyy HH:mm"
     * 4. Mendukung format jam saja "HH:mm" atau "H:mm" (digabung dengan fallbackDateStr)
     */
    fun parseDateTimeToEpoch(dateTimeStr: String, fallbackDateStr: String, schedEpoch: Long = 0L): Long {
        if (dateTimeStr.isBlank() || dateTimeStr == "-") return 0L
        val clean = dateTimeStr.replace("WIB", "").replace("WITA", "").replace("WIT", "")
            .replace("(Qadha)", "").trim()

        // 1. Jika angka serial Sheets
        val asDouble = clean.toDoubleOrNull()
        if (asDouble != null && asDouble > 1000.0) {
            val millisUtc = ((asDouble - 25569.0) * 86400.0 * 1000.0).toLong()
            val tzOffset = ZoneId.systemDefault().rules.getOffset(Instant.ofEpochMilli(millisUtc)).totalSeconds * 1000L
            return millisUtc - tzOffset
        }

        // 2. Jika berisi tanggal + jam (ada pemisah spasi atau 'T')
        if (clean.contains(" ") || clean.contains("T")) {
            val delim = if (clean.contains("T")) "T" else " "
            val datePart = clean.substringBefore(delim).trim()
            val timePart = clean.substringAfter(delim).trim()

            val normDate = normalizeSheetDate(datePart).ifBlank { normalizeSheetDate(fallbackDateStr) }
            val timeSubParts = timePart.split(":")
            if (timeSubParts.size >= 2) {
                val h = timeSubParts[0].trim().toIntOrNull()
                val m = timeSubParts[1].trim().toIntOrNull()
                if (h != null && m != null) {
                    val epoch = try {
                        val pDate = LocalDate.parse(normDate)
                        pDate.atTime(h, m).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    } catch (_: Exception) { 0L }
                    if (epoch > 0L) return epoch
                }
            }
        }

        // 3. Jika hanya jam saja (misal "16:35" atau "4:43")
        val timeParts = clean.split(":")
        if (timeParts.size >= 2) {
            val h = timeParts[0].trim().toIntOrNull()
            val m = timeParts[1].trim().toIntOrNull()
            if (h != null && m != null) {
                val parsed = try {
                    val pDate = LocalDate.parse(normalizeSheetDate(fallbackDateStr))
                    pDate.atTime(h, m).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                } catch (_: Exception) { 0L }

                if (parsed > 0L) {
                    return if (schedEpoch > 0 && parsed < schedEpoch) parsed + 86_400_000L else parsed
                }
            }
        }

        return 0L
    }

    /**
     * Parse jam (HH:mm) + tanggal (yyyy-MM-dd) ke epoch millis.
     */
    fun parseTimeToEpoch(dateStr: String, timeStr: String): Long {
        return try {
            val clean = timeStr.replace("WIB", "").replace("WITA", "").replace("WIT", "")
                .replace("(Qadha)", "").trim()
            val parts = clean.split(":")
            if (parts.size >= 2) {
                val h = parts[0].trim().toInt()
                val m = parts[1].trim().toInt()
                val pDate = LocalDate.parse(normalizeSheetDate(dateStr))
                pDate.atTime(h, m).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            } else {
                0L
            }
        } catch (_: Exception) {
            0L
        }
    }

    /**
     * Menormalisasi berbagai format tanggal dari Sheets ke standar ISO "yyyy-MM-dd".
     */
    fun normalizeSheetDate(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return ""

        if (trimmed.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) return trimmed

        // "d MMMM yyyy" (Indonesia)
        try {
            val idFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale("id", "ID"))
            return LocalDate.parse(trimmed, idFormatter).toString()
        } catch (_: Exception) { }

        // "MMMM d, yyyy" (English)
        try {
            val enFormatter = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH)
            return LocalDate.parse(trimmed, enFormatter).toString()
        } catch (_: Exception) { }

        // "d/M/yyyy"
        try {
            val slashFormatter = DateTimeFormatter.ofPattern("d/M/yyyy")
            return LocalDate.parse(trimmed, slashFormatter).toString()
        } catch (_: Exception) { }

        // Angka serial Google Sheets
        try {
            val serial = trimmed.toDouble().toLong()
            if (serial in 1..100000) {
                val baseDate = LocalDate.of(1899, 12, 30)
                return baseDate.plusDays(serial).toString()
            }
        } catch (_: Exception) { }

        return trimmed
    }
}

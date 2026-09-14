package com.prayertracker.app.core.sync

/**
 * Satu-satunya tempat untuk membangun rumus yang ditulis ke Google Sheets.
 *
 * Google Sheets memakai pemisah argumen yang mengikuti locale spreadsheet.
 * Semua rumus wajib memperoleh pemisah dari instance yang sama supaya tidak
 * ada formula setengah koma dan setengah titik-koma.
 */
class GoogleSheetsFormulaFactory(private val separator: String) {

    init {
        require(separator == "," || separator == ";") {
            "Pemisah formula harus koma atau titik-koma"
        }
    }

    private val s = "$"
    private val checkMark = "\u2713"

    fun dailySummary(): String = """
        =IFERROR(LET(
          rawDates$separator 'Data Mentah'!${s}B${s}2:${s}B${s}1000$separator
          normalizedDates$separator ARRAYFORMULA(IF(ISNUMBER(rawDates)$separator rawDates$separator IFERROR(DATEVALUE(rawDates)$separator 0)))$separator
          uniqueDates$separator SORT(UNIQUE(FILTER(normalizedDates$separator normalizedDates>0))$separator 1$separator FALSE)$separator
          HSTACK(
            ARRAYFORMULA(TEXT(uniqueDates$separator "d MMMM yyyy"))$separator
            ARRAYFORMULA(IF(COUNTIFS('Data Mentah'!${s}B${s}2:${s}B${s}1000$separator uniqueDates$separator 'Data Mentah'!${s}C${s}2:${s}C${s}1000$separator "Subuh"$separator 'Data Mentah'!${s}G${s}2:${s}G${s}1000$separator "Sudah*")>0$separator "Sudah"$separator "Belum"))$separator
            ARRAYFORMULA(IF(COUNTIFS('Data Mentah'!${s}B${s}2:${s}B${s}1000$separator uniqueDates$separator 'Data Mentah'!${s}C${s}2:${s}C${s}1000$separator "Dzuhur"$separator 'Data Mentah'!${s}G${s}2:${s}G${s}1000$separator "Sudah*")>0$separator "Sudah"$separator "Belum"))$separator
            ARRAYFORMULA(IF(COUNTIFS('Data Mentah'!${s}B${s}2:${s}B${s}1000$separator uniqueDates$separator 'Data Mentah'!${s}C${s}2:${s}C${s}1000$separator "Ashar"$separator 'Data Mentah'!${s}G${s}2:${s}G${s}1000$separator "Sudah*")>0$separator "Sudah"$separator "Belum"))$separator
            ARRAYFORMULA(IF(COUNTIFS('Data Mentah'!${s}B${s}2:${s}B${s}1000$separator uniqueDates$separator 'Data Mentah'!${s}C${s}2:${s}C${s}1000$separator "Maghrib"$separator 'Data Mentah'!${s}G${s}2:${s}G${s}1000$separator "Sudah*")>0$separator "Sudah"$separator "Belum"))$separator
            ARRAYFORMULA(IF(COUNTIFS('Data Mentah'!${s}B${s}2:${s}B${s}1000$separator uniqueDates$separator 'Data Mentah'!${s}C${s}2:${s}C${s}1000$separator "Isya"$separator 'Data Mentah'!${s}G${s}2:${s}G${s}1000$separator "Sudah*")>0$separator "Sudah"$separator "Belum"))$separator
            ARRAYFORMULA(IF(uniqueDates=""$separator ""$separator COUNTIFS('Data Mentah'!${s}B${s}2:${s}B${s}1000$separator uniqueDates$separator 'Data Mentah'!${s}G${s}2:${s}G${s}1000$separator "Sudah*")&"/5"))
          )
        )$separator "")
    """.compact()

    /**
     * Ringkasan 7 hari x 5 salat.
     *
     * CHOOSE dipakai untuk nama salat, bukan array literal {…}. Array literal
     * punya pemisah kolom berbeda antar locale dan menjadi sumber parse error
     * yang sulit dilacak ketika formula dikirim melalui API.
     */
    fun weeklySummary(): String = """
        =IFERROR(LET(
          rawDates$separator 'Data Mentah'!${s}B${s}2:${s}B${s}1000$separator
          normalizedDates$separator ARRAYFORMULA(IF(ISNUMBER(rawDates)$separator INT(rawDates)$separator IFERROR(DATEVALUE(rawDates)$separator 0)))$separator
          weekStarts$separator SORT(UNIQUE(FILTER(normalizedDates-WEEKDAY(normalizedDates$separator 2)+1$separator normalizedDates>0))$separator 1$separator FALSE)$separator
          checks$separator MAKEARRAY(ROWS(weekStarts)$separator 35$separator LAMBDA(rowIndex$separator columnIndex$separator LET(
            targetDate$separator INDEX(weekStarts$separator rowIndex)+INT((columnIndex-1)/5)$separator
            prayerName$separator CHOOSE(MOD(columnIndex-1$separator 5)+1$separator "Subuh"$separator "Dzuhur"$separator "Ashar"$separator "Maghrib"$separator "Isya")$separator
            IF(targetDate>TODAY()$separator ""$separator IF(COUNTIFS(normalizedDates$separator targetDate$separator 'Data Mentah'!${s}C${s}2:${s}C${s}1000$separator prayerName$separator 'Data Mentah'!${s}G${s}2:${s}G${s}1000$separator "Sudah*")>0$separator "$checkMark"$separator "-"))
          )))$separator
          totals$separator BYROW(checks$separator LAMBDA(checkRow$separator LET(
            completed$separator COUNTIF(checkRow$separator "$checkMark")$separator
            active$separator completed+COUNTIF(checkRow$separator "-")$separator
            IF(active=0$separator "-"$separator completed&"/"&active&" ("&TEXT(completed/active$separator "0%")&")")
          )))$separator
          HSTACK(
            ARRAYFORMULA(TEXT(weekStarts$separator "mmmm yyyy"))$separator
            ARRAYFORMULA("Minggu "&IFERROR(INT((weekStarts - (DATE(YEAR(weekStarts)$separator MONTH(weekStarts)$separator 1) + MOD(1 - WEEKDAY(DATE(YEAR(weekStarts)$separator MONTH(weekStarts)$separator 1)$separator 2)$separator 7))) / 7) + 1$separator "1"))$separator
            ARRAYFORMULA(TEXT(weekStarts$separator "d MMM")&" - "&TEXT(weekStarts+6$separator "d MMM"))$separator
            checks$separator totals
          )
        )$separator "")
    """.compact()

    fun timeRecap(): String = """
        =IFERROR(LET(
          rawDates$separator 'Data Mentah'!${s}B${s}2:${s}B${s}1000$separator
          normalizedDates$separator ARRAYFORMULA(IF(ISNUMBER(rawDates)$separator rawDates$separator IFERROR(DATEVALUE(rawDates)$separator 0)))$separator
          uniqueDates$separator SORT(UNIQUE(FILTER(normalizedDates$separator normalizedDates>0))$separator 1$separator FALSE)$separator
          HSTACK(
            ARRAYFORMULA(TEXT(uniqueDates$separator "d MMMM yyyy"))$separator
            ${recapPrayerColumn("Subuh")}$separator
            ${recapPrayerColumn("Dzuhur")}$separator
            ${recapPrayerColumn("Ashar")}$separator
            ${recapPrayerColumn("Maghrib")}$separator
            ${recapPrayerColumn("Isya")}$separator
            ARRAYFORMULA(IF(uniqueDates=""$separator ""$separator COUNTIFS('Data Mentah'!${s}B${s}2:${s}B${s}1000$separator uniqueDates$separator 'Data Mentah'!${s}G${s}2:${s}G${s}1000$separator "Sudah*"$separator 'Data Mentah'!${s}F${s}2:${s}F${s}1000$separator "<>")&"/5"))
          )
        )$separator "")
    """.compact()

    fun rawStatus(): String =
        "=ARRAYFORMULA(IF(B2:B1000=\"\"$separator \"\"$separator IF(F2:F1000=\"\"$separator \"\"$separator IF(F2:F1000=\"-\"$separator \"Belum\"$separator \"Sudah\"))))"

    fun rawDescription(): String {
        val doneExpr = "IF(ISNUMBER(F2:F1000)$separator IF(F2:F1000<1$separator B2:B1000+F2:F1000$separator F2:F1000)$separator IFERROR(DATEVALUE(LEFT(F2:F1000$separator 10))+TIMEVALUE(RIGHT(F2:F1000$separator 5))$separator IFERROR(DATEVALUE(F2:F1000)+TIMEVALUE(F2:F1000)$separator 0)))"
        val scheduledExpr = "B2:B1000+D2:D1000"
        val deadlineExpr = "B2:B1000+E2:E1000+IF(E2:E1000<D2:D1000$separator 1$separator 0)+0*NOW()"
        return "=ARRAYFORMULA(IF(B2:B1000=\"\"$separator \"\"$separator " +
            "IF((F2:F1000=\"\")+(F2:F1000=\"-\")$separator " +
            "IF(NOW()>$deadlineExpr$separator \"Terlewat (Belum Qadha)\"$separator \"Belum Salat\")$separator " +
            "IF(ISNUMBER(SEARCH(\"Qadha\"$separator \"\"&F2:F1000))+($doneExpr>$deadlineExpr)$separator \"Qadha Selesai\"$separator " +
            "IF($doneExpr<$scheduledExpr$separator \"Sebelum Waktu Masuk\"$separator " +
            "IF($doneExpr<=$scheduledExpr+(30/1440)$separator \"Tepat Waktu (Awal Waktu)\"$separator " +
            "IF($doneExpr<=$deadlineExpr-(15/1440)$separator \"Tepat Waktu\"$separator \"Akhir Waktu\")))))))"
    }

    private fun recapPrayerColumn(prayerName: String): String = """
        BYROW(uniqueDates$separator LAMBDA(d$separator LET(
          done$separator COUNTIFS('Data Mentah'!${s}B${s}2:${s}B${s}1000$separator d$separator 'Data Mentah'!${s}C${s}2:${s}C${s}1000$separator "$prayerName"$separator 'Data Mentah'!${s}G${s}2:${s}G${s}1000$separator "Sudah*"$separator 'Data Mentah'!${s}F${s}2:${s}F${s}1000$separator "<>")$separator
          tm$separator IF(done=0$separator ""$separator TEXT(INDEX(FILTER('Data Mentah'!${s}F${s}2:${s}F${s}1000$separator ('Data Mentah'!${s}B${s}2:${s}B${s}1000=d)*('Data Mentah'!${s}C${s}2:${s}C${s}1000="$prayerName"))$separator 1)$separator "HH:mm"))$separator
          qd$separator IF(COUNTIFS('Data Mentah'!${s}B${s}2:${s}B${s}1000$separator d$separator 'Data Mentah'!${s}C${s}2:${s}C${s}1000$separator "$prayerName"$separator 'Data Mentah'!${s}H${s}2:${s}H${s}1000$separator "*Qadha*")>0$separator " (Qadha)"$separator "")$separator
          IF(done>0$separator tm&qd$separator "Belum")
        )))
    """.compact()

    private fun String.compact(): String = trimIndent().replace("\n", "")
}

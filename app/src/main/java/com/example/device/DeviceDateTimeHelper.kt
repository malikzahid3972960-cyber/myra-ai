package com.example.device

import com.example.data.model.AssistantLanguage
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Provides accurate live device date, day of week, time, and timezone from Android's system clock,
 * plus instant local responses for direct date/time/day questions in English, Urdu, and Roman Urdu.
 */
object DeviceDateTimeHelper {

    private fun resolveEffectiveTimeZone(): TimeZone {
        val deviceTz = TimeZone.getDefault()
        return if (deviceTz != null && !deviceTz.id.isNullOrBlank()) {
            deviceTz
        } else {
            TimeZone.getTimeZone("Asia/Karachi")
        }
    }

    fun buildSystemInstructionDateTime(nowMillis: Long = System.currentTimeMillis()): String {
        val cal = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val tz = resolveEffectiveTimeZone()
        val formatted = SimpleDateFormat("EEEE, MMMM d, yyyy, hh:mm a", Locale.US)
            .apply { timeZone = tz }
            .format(cal.time)
        return "$formatted (${tz.id})"
    }

    fun buildLiveClockContext(nowMillis: Long = System.currentTimeMillis()): String {
        val cal = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val tz = resolveEffectiveTimeZone()
        val fullDateEn = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.US).apply { timeZone = tz }.format(cal.time)
        val isoDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = tz }.format(cal.time)
        val time12 = SimpleDateFormat("h:mm a", Locale.US).apply { timeZone = tz }.format(cal.time)
        val urduDay = getUrduDayName(cal.get(Calendar.DAY_OF_WEEK))
        val urduMonth = getUrduMonthName(cal.get(Calendar.MONTH))
        val dayOfMonth = cal.get(Calendar.DAY_OF_MONTH)
        val year = cal.get(Calendar.YEAR)
        return "Current Device Date: $fullDateEn ($isoDate) | Urdu Date: $urduDay، $dayOfMonth $urduMonth $year | Current Time: $time12 (${tz.id})"
    }

    /**
     * Returns a direct, accurate reply if the user is asking for today's date, current day, or current time;
     * otherwise returns null so the message proceeds normally.
     */
    fun resolveDirectDateTimeQuery(
        prompt: String,
        preferredLanguage: AssistantLanguage,
        nowMillis: Long = System.currentTimeMillis()
    ): String? {
        val trimmed = prompt.trim()
        if (trimmed.isBlank()) return null
        val lower = trimmed.lowercase()

        // Never hijack longer analytical questions, coding prompts, or app-opening commands
        if (trimmed.length > 90 || trimmed.split(Regex("\\s+")).size > 12) return null

        val isDateQuery = matchesDateQuestion(lower)
        val isTimeQuery = matchesTimeQuestion(lower)
        val isDayQuery = matchesDayQuestion(lower)

        if (!isDateQuery && !isTimeQuery && !isDayQuery) return null

        val cal = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val tz = resolveEffectiveTimeZone()
        val fullDateEn = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.US).apply { timeZone = tz }.format(cal.time)
        val time12 = SimpleDateFormat("h:mm a", Locale.US).apply { timeZone = tz }.format(cal.time)
        val dayEn = SimpleDateFormat("EEEE", Locale.US).apply { timeZone = tz }.format(cal.time)
        val urduDay = getUrduDayName(cal.get(Calendar.DAY_OF_WEEK))
        val urduMonth = getUrduMonthName(cal.get(Calendar.MONTH))
        val dayNum = cal.get(Calendar.DAY_OF_MONTH)
        val year = cal.get(Calendar.YEAR)

        val useEnglish = hasMostlyAsciiLetters(trimmed) &&
            !containsRomanUrduDateWords(lower) &&
            (preferredLanguage == AssistantLanguage.ENGLISH || preferredLanguage == AssistantLanguage.AUTO || containsEnglishQuestionStructure(lower))

        val greetingPrefix = if (useEnglish && lower.contains("how are you")) {
            "I am doing well, thank you! "
        } else {
            ""
        }

        return when {
            isDateQuery && isTimeQuery -> {
                if (useEnglish) {
                    "${greetingPrefix}Today's date is $fullDateEn, and the current time is $time12."
                } else {
                    "آج کی تاریخ $urduDay، $dayNum $urduMonth $year ہے اور ابھی وقت $time12 ہے۔"
                }
            }
            isDateQuery -> {
                if (useEnglish) {
                    "${greetingPrefix}Today's date is $fullDateEn."
                } else {
                    "آج کی تاریخ $urduDay، $dayNum $urduMonth $year ہے۔"
                }
            }
            isDayQuery -> {
                if (useEnglish) {
                    "${greetingPrefix}Today is $dayEn ($fullDateEn)."
                } else {
                    "آج $urduDay ہے ($dayNum $urduMonth $year)۔"
                }
            }
            else -> {
                if (useEnglish) {
                    "${greetingPrefix}The current time is $time12."
                } else {
                    "ابھی وقت $time12 ہے ($urduDay، $dayNum $urduMonth $year)۔"
                }
            }
        }
    }

    private fun matchesDateQuestion(lower: String): Boolean {
        val exactOrPhrase = listOf(
            "today's date",
            "todays date",
            "today date",
            "date today",
            "current date",
            "what is the date",
            "what's the date",
            "whats the date",
            "what date is it",
            "tell me the date",
            "aaj ki tareekh",
            "aaj ki tarikh",
            "aaj kya date",
            "aaj date kya",
            "aaj kya tareekh",
            "aaj tareekh kya",
            "آج کی تاریخ",
            "آج تاریخ",
            "آج کیا تاریخ",
            "تاریخ کیا ہے",
            "تاریخ بتاؤ",
            "تاریخ بتائیں"
        )
        if (exactOrPhrase.any { lower.contains(it) }) return true

        val stripped = lower.replace(Regex("[?؟!.,،]"), "").trim()
        return stripped in setOf("date", "today", "tareekh", "tarikh", "تاریخ")
    }

    private fun matchesTimeQuestion(lower: String): Boolean {
        val phrases = listOf(
            "what time is it",
            "what's the time",
            "whats the time",
            "current time",
            "tell me the time",
            "time now",
            "abhi kya time",
            "abhi time kya",
            "abhi kya waqt",
            "waqt kya hai",
            "کیا وقت ہے",
            "کیتنے بجے",
            "کتنے بجے",
            "ٹائم کیا ہے",
            "وقت بتاؤ",
            "وقت بتائیں"
        )
        if (phrases.any { lower.contains(it) }) return true

        val stripped = lower.replace(Regex("[?؟!.,،]"), "").trim()
        return stripped in setOf("time", "waqt", "وقت", "ٹائم")
    }

    private fun matchesDayQuestion(lower: String): Boolean {
        val phrases = listOf(
            "what day is it",
            "what day is today",
            "today's day",
            "todays day",
            "aaj ka din",
            "aaj konsa din",
            "aaj kaun sa din",
            "آج کون سا دن",
            "آج کونسادن",
            "آج کیا دن"
        )
        return phrases.any { lower.contains(it) }
    }

    private fun hasMostlyAsciiLetters(text: String): Boolean {
        val urduChars = text.count { it in '\u0600'..'\u06FF' }
        val asciiLetters = text.count { it in 'a'..'z' || it in 'A'..'Z' }
        return urduChars == 0 && asciiLetters > 0
    }

    private fun containsRomanUrduDateWords(lower: String): Boolean {
        return lower.contains("aaj") || lower.contains("tareekh") || lower.contains("tarikh") ||
            lower.contains("kya") || lower.contains("waqt") || lower.contains("konsa") ||
            lower.contains("kaun") || lower.contains("abhi") || lower.contains("din")
    }

    private fun containsEnglishQuestionStructure(lower: String): Boolean {
        return lower.contains("today") || lower.contains("what") || lower.contains("current") ||
            lower.contains("tell me") || lower == "date" || lower == "time"
    }

    private fun getUrduDayName(dayOfWeek: Int): String = when (dayOfWeek) {
        Calendar.SUNDAY -> "اتوار"
        Calendar.MONDAY -> "پیر"
        Calendar.TUESDAY -> "منگل"
        Calendar.WEDNESDAY -> "بدھ"
        Calendar.THURSDAY -> "جمعرات"
        Calendar.FRIDAY -> "جمعہ"
        Calendar.SATURDAY -> "ہفتہ"
        else -> ""
    }

    private fun getUrduMonthName(month: Int): String = when (month) {
        Calendar.JANUARY -> "جنوری"
        Calendar.FEBRUARY -> "فروری"
        Calendar.MARCH -> "مارچ"
        Calendar.APRIL -> "اپریل"
        Calendar.MAY -> "مئی"
        Calendar.JUNE -> "جون"
        Calendar.JULY -> "جولائی"
        Calendar.AUGUST -> "اگست"
        Calendar.SEPTEMBER -> "ستمبر"
        Calendar.OCTOBER -> "اکتوبر"
        Calendar.NOVEMBER -> "نومبر"
        Calendar.DECEMBER -> "دسمبر"
        else -> ""
    }
}

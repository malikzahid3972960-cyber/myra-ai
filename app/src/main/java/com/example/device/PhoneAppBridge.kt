package com.example.device

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

data class InstalledLaunchableApp(
    val label: String,
    val packageName: String,
    val isLaunchable: Boolean = true
)

data class ActionExecutionResult(
    val success: Boolean,
    val verificationMessage: String,
    val matchedAppLabel: String? = null,
    val matchedPackageName: String? = null
)

sealed interface ImmediatePromptAction {
    data class AppLaunched(val result: ActionExecutionResult) : ImmediatePromptAction
    data class StudioShortcut(val actionType: String, val actionTarget: String) : ImmediatePromptAction
}

/**
 * Android Application Bridge for Jarvis.
 * - Uses QUERY_ALL_PACKAGES + explicit <queries> backup to scan all installed applications.
 * - Implements openApp(query: String): Boolean with filler stripping, Urdu/Roman Urdu/English
 *   normalization, exact/contains matching (shortest label wins), known package alias fallback,
 *   immediate launch via getLaunchIntentForPackage, real error catching, and Log.d("JarvisApps", ...).
 */
class PhoneAppBridge(private val context: Context) {

    private val packageManager: PackageManager = context.packageManager

    @Volatile
    private var cachedInstalledApps: List<InstalledLaunchableApp> = loadAllInstalledApps()

    /**
     * Loads every installed application using PackageManager.getInstalledApplications(GET_META_DATA)
     * and resolves each app's label via getApplicationLabel(appInfo).
     * Also merges launcher activities for any activity-specific label aliases.
     */
    fun loadAllInstalledApps(): List<InstalledLaunchableApp> {
        val byPackage = LinkedHashMap<String, InstalledLaunchableApp>()

        val installedApps = try {
            packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
        } catch (e: Exception) {
            Log.e(LOG_TAG, "getInstalledApplications failed", e)
            emptyList()
        }

        for (appInfo in installedApps) {
            val pkg = appInfo.packageName ?: continue
            if (pkg.isBlank() || pkg == context.packageName) continue

            val label = try {
                packageManager.getApplicationLabel(appInfo)?.toString()?.trim()
            } catch (_: Exception) {
                null
            }.orEmpty().ifBlank { pkg }

            val launchable = try {
                packageManager.getLaunchIntentForPackage(pkg) != null
            } catch (_: Exception) {
                false
            }

            byPackage[pkg] = InstalledLaunchableApp(
                label = label,
                packageName = pkg,
                isLaunchable = launchable
            )
        }

        // Merge launcher activity labels in case an app exposes its user-facing title on the launcher activity
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos = try {
            packageManager.queryIntentActivities(launcherIntent, 0)
        } catch (_: Exception) {
            emptyList()
        }
        for (ri in resolveInfos) {
            val pkg = ri.activityInfo?.packageName ?: continue
            if (pkg.isBlank() || pkg == context.packageName) continue
            val activityLabel = try {
                ri.loadLabel(packageManager)?.toString()?.trim()
            } catch (_: Exception) {
                null
            }
            val existing = byPackage[pkg]
            if (existing == null) {
                byPackage[pkg] = InstalledLaunchableApp(
                    label = activityLabel?.takeIf { it.isNotBlank() } ?: pkg,
                    packageName = pkg,
                    isLaunchable = true
                )
            } else if (!existing.isLaunchable || (existing.label == pkg && !activityLabel.isNullOrBlank())) {
                byPackage[pkg] = existing.copy(
                    label = activityLabel?.takeIf { it.isNotBlank() } ?: existing.label,
                    isLaunchable = true
                )
            }
        }

        val sorted = byPackage.values.sortedBy { it.label.lowercase() }
        cachedInstalledApps = sorted
        Log.d(LOG_TAG, "loadAllInstalledApps: found ${sorted.size} installed packages")
        return sorted
    }

    fun refreshInstalledApps(): List<InstalledLaunchableApp> = loadAllInstalledApps()

    fun getAllInstalledApps(forceRefresh: Boolean = false): List<InstalledLaunchableApp> {
        return if (forceRefresh || cachedInstalledApps.isEmpty()) {
            loadAllInstalledApps()
        } else {
            cachedInstalledApps
        }
    }

    /**
     * Returns launchable apps (used when only launcher apps are preferred),
     * or falls back to all installed apps.
     */
    fun getLaunchableInstalledApps(forceRefresh: Boolean = false): List<InstalledLaunchableApp> {
        return getAllInstalledApps(forceRefresh)
    }

    /**
     * Opens the app matching [query] immediately without confirmation.
     * @return true if startActivity succeeded, false otherwise.
     */
    fun openApp(query: String): Boolean {
        return openAppWithResult(query).success
    }

    /**
     * Opens an already-resolved [InstalledLaunchableApp] directly from the Settings list.
     */
    fun openApp(app: InstalledLaunchableApp): ActionExecutionResult {
        return launchPackageInternal(app.packageName, app.label)
    }

    /**
     * Full app-opening pipeline returning structured status + exact chat reply message.
     */
    fun openAppWithResult(rawQuery: String): ActionExecutionResult {
        Log.d(LOG_TAG, "openApp start: rawQuery='$rawQuery'")
        val cleanedQuery = stripFillerWords(rawQuery)
        if (cleanedQuery.isBlank()) {
            val displayQuery = rawQuery.trim().ifBlank { "app" }
            Log.d(LOG_TAG, "Query became blank after stripping fillers; returning not found for '$displayQuery'")
            return ActionExecutionResult(
                success = false,
                verificationMessage = formatNotFoundMessage(displayQuery)
            )
        }

        val mappedAlias = mapUrduNameToEnglish(cleanedQuery)
        val normCleaned = normalizeText(cleanedQuery)
        val normMapped = normalizeText(mappedAlias)
        Log.d(
            LOG_TAG,
            "Normalized query: cleaned='$cleanedQuery' (norm='$normCleaned'), mapped='$mappedAlias' (norm='$normMapped')"
        )

        // 2a. Get all installed apps with PackageManager.getInstalledApplications(GET_META_DATA)
        val installedApps = loadAllInstalledApps()
        Log.d(LOG_TAG, "Scanning ${installedApps.size} installed apps for '$cleanedQuery'")

        // 2c. Exact match on label, then contains match in either direction (shortest label wins)
        val labelMatch = findBestMatchingInstalledApp(
            normCleaned = normCleaned,
            normMapped = normMapped,
            installedApps = installedApps
        )
        if (labelMatch != null) {
            Log.d(
                LOG_TAG,
                "Matched installed app by label: label='${labelMatch.label}', pkg='${labelMatch.packageName}'"
            )
            return launchPackageInternal(labelMatch.packageName, labelMatch.label)
        }

        // 2d. No label matched -> try known package alias table
        val aliasPkgEntry = resolveKnownAliasPackage(normCleaned, normMapped)
        if (aliasPkgEntry != null) {
            val (pkg, defaultLabel) = aliasPkgEntry
            Log.d(LOG_TAG, "Trying known package alias fallback: pkg='$pkg' (defaultLabel='$defaultLabel')")
            val installedLabel = getInstalledAppLabelOrNull(pkg)
            val launchIntentAvailable = try {
                packageManager.getLaunchIntentForPackage(pkg) != null
            } catch (_: Exception) {
                false
            }
            if (installedLabel != null || launchIntentAvailable) {
                val resolvedLabel = installedLabel ?: defaultLabel
                Log.d(LOG_TAG, "Known package found on device: label='$resolvedLabel', pkg='$pkg'")
                return launchPackageInternal(pkg, resolvedLabel)
            } else {
                Log.d(LOG_TAG, "Known alias pkg='$pkg' is NOT installed on device")
            }
        }

        Log.d(LOG_TAG, "No match found for '$cleanedQuery'")
        return ActionExecutionResult(
            success = false,
            verificationMessage = formatNotFoundMessage(cleanedQuery)
        )
    }

    /**
     * Checks whether a user prompt should be handled locally as an app-opening request
     * (either with filler words like "open Facebook", or as a bare app name like "Telegram")
     * instead of being sent to the AI model.
     */
    fun isAppOpenIntent(prompt: String): Boolean {
        val trimmed = prompt.trim()
        if (trimmed.isBlank()) return false

        // Explicit open/launch trigger words always route to openApp (never Gemini)
        if (containsOpenTriggerWord(trimmed)) return true

        // Bare app name without trigger words (guard against questions or conversational sentences)
        if (!looksLikeBareAppName(trimmed)) return false

        val cleaned = stripFillerWords(trimmed)
        if (cleaned.isBlank()) return false

        val mapped = mapUrduNameToEnglish(cleaned)
        val normCleaned = normalizeText(cleaned)
        val normMapped = normalizeText(mapped)

        if (resolveKnownAliasPackage(normCleaned, normMapped) != null) {
            return true
        }

        val installedApps = getAllInstalledApps()
        return findBestMatchingInstalledApp(normCleaned, normMapped, installedApps) != null
    }

    /**
     * Executes immediate prompt actions for studio shortcuts or app-opening commands.
     */
    fun handlePromptCommand(prompt: String): ImmediatePromptAction? {
        val trimmed = prompt.trim()
        if (trimmed.isBlank()) return null

        if (isAppOpenIntent(trimmed)) {
            return ImmediatePromptAction.AppLaunched(openAppWithResult(trimmed))
        }

        val lower = trimmed.lowercase()
        if (
            (lower.contains("website") || lower.contains("ویب سائٹ") || lower.contains("web site") || lower.contains("landing page")) &&
            (lower.contains("create") || lower.contains("build") || lower.contains("make") || lower.contains("بناؤ") || lower.contains("بنائیں") || lower.contains("banao"))
        ) {
            return ImmediatePromptAction.StudioShortcut(
                actionType = "website_builder",
                actionTarget = trimmed.take(180)
            )
        }

        if (
            (lower.contains("logo") || lower.contains("لوگو") || lower.contains("brand identity") || lower.contains("graphic design")) &&
            (lower.contains("create") || lower.contains("design") || lower.contains("make") || lower.contains("generate") || lower.contains("بناؤ") || lower.contains("بنائیں") || lower.contains("banao"))
        ) {
            return ImmediatePromptAction.StudioShortcut(
                actionType = "design_studio",
                actionTarget = trimmed.take(180)
            )
        }

        return null
    }

    fun isPackageInstalled(packageName: String): Boolean {
        return try {
            packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    fun launchAppByPackage(packageName: String, displayLabel: String = packageName): ActionExecutionResult {
        return launchPackageInternal(packageName, displayLabel)
    }

    private fun launchPackageInternal(pkg: String, appName: String): ActionExecutionResult {
        val intent = try {
            packageManager.getLaunchIntentForPackage(pkg)
        } catch (e: Exception) {
            Log.e(LOG_TAG, "getLaunchIntentForPackage threw exception for pkg='$pkg'", e)
            null
        }

        if (intent == null) {
            Log.d(LOG_TAG, "getLaunchIntentForPackage returned null for pkg='$pkg' ($appName)")
            return ActionExecutionResult(
                success = false,
                verificationMessage = "$appName ($pkg) is not launchable.",
                matchedAppLabel = appName,
                matchedPackageName = pkg
            )
        }

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            Log.d(LOG_TAG, "Calling startActivity for pkg='$pkg', appName='$appName'")
            context.startActivity(intent)
            Log.d(LOG_TAG, "startActivity succeeded for pkg='$pkg', appName='$appName'")
            ActionExecutionResult(
                success = true,
                verificationMessage = formatOpeningMessage(appName),
                matchedAppLabel = appName,
                matchedPackageName = pkg
            )
        } catch (e: Exception) {
            val realError = e.localizedMessage ?: e.message ?: e::class.java.simpleName
            Log.e(LOG_TAG, "startActivity failed for pkg='$pkg': $realError", e)
            ActionExecutionResult(
                success = false,
                verificationMessage = "Failed to open $appName ($pkg): $realError",
                matchedAppLabel = appName,
                matchedPackageName = pkg
            )
        }
    }

    private fun getInstalledAppLabelOrNull(pkg: String): String? {
        return try {
            val appInfo = packageManager.getApplicationInfo(pkg, PackageManager.GET_META_DATA)
            packageManager.getApplicationLabel(appInfo)?.toString()?.trim()?.takeIf { it.isNotBlank() } ?: pkg
        } catch (_: PackageManager.NameNotFoundException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun findBestMatchingInstalledApp(
        normCleaned: String,
        normMapped: String,
        installedApps: List<InstalledLaunchableApp>
    ): InstalledLaunchableApp? {
        if (installedApps.isEmpty()) return null
        val candidates = linkedSetOf(normCleaned, normMapped).filter { it.isNotBlank() }
        if (candidates.isEmpty()) return null

        // 1. Exact match on normalized label
        val exactMatches = installedApps.filter { app ->
            val normLabel = normalizeText(app.label)
            normLabel.isNotBlank() && (candidates.any { normLabel == it || app.packageName.equals(it, ignoreCase = true) })
        }
        if (exactMatches.isNotEmpty()) {
            val chosen = exactMatches.minWithOrNull(
                compareByDescending<InstalledLaunchableApp> { it.isLaunchable }
                    .thenBy { it.label.length }
                    .thenBy { it.label.lowercase() }
            )
            Log.d(LOG_TAG, "Exact matches count=${exactMatches.size}, chose='${chosen?.label}' (${chosen?.packageName})")
            return chosen
        }

        // 2. "Contains" match in either direction; choose shortest label if several match
        val containsMatches = installedApps.filter { app ->
            val normLabel = normalizeText(app.label)
            normLabel.length >= 2 && candidates.any { q ->
                q.length >= 2 && (normLabel.contains(q) || q.contains(normLabel))
            }
        }
        if (containsMatches.isNotEmpty()) {
            val chosen = containsMatches.minWithOrNull(
                compareBy<InstalledLaunchableApp> { it.label.length }
                    .thenByDescending { it.isLaunchable }
                    .thenBy { it.label.lowercase() }
            )
            Log.d(
                LOG_TAG,
                "Contains matches count=${containsMatches.size} -> ${containsMatches.map { it.label }}; chose shortest='${chosen?.label}' (${chosen?.packageName})"
            )
            return chosen
        }

        return null
    }

    private fun resolveKnownAliasPackage(normCleaned: String, normMapped: String): Pair<String, String>? {
        for (key in listOf(normCleaned, normMapped)) {
            if (key.isBlank()) continue
            KNOWN_PACKAGE_ALIASES[key]?.let { return it }
        }
        return null
    }

    internal fun stripFillerWords(rawQuery: String): String {
        val protected = rawQuery.trim()
            .replace("واٹس ایپ", "واٹس_ایپ")
            .replace("وٹس ایپ", "وٹس_ایپ")

        val tokens = protected
            .split(Regex("[\\s,،؟?!.:;\"'()\\[\\]{}]+"))
            .filter { it.isNotBlank() }

        val kept = tokens.filter { token ->
            val lower = token.lowercase()
            lower !in FILLER_WORDS_LOWER && token !in FILLER_WORDS_EXACT
        }
        return kept.joinToString(" ")
            .replace("واٹس_ایپ", "واٹس ایپ")
            .replace("وٹس_ایپ", "وٹس ایپ")
            .trim()
    }

    private fun mapUrduNameToEnglish(cleanedQuery: String): String {
        val trimmedLower = cleanedQuery.trim().lowercase()
        URDU_TO_ENGLISH_ALIASES[trimmedLower]?.let { return it }
        val normalizedUrdu = normalizeText(cleanedQuery)
        URDU_TO_ENGLISH_NORMALIZED[normalizedUrdu]?.let { return it }
        return cleanedQuery
    }

    private fun containsOpenTriggerWord(prompt: String): Boolean {
        val tokens = prompt.trim()
            .split(Regex("[\\s,،؟?!.:;\"'()\\[\\]{}]+"))
            .filter { it.isNotBlank() }
        return tokens.any { token ->
            token.lowercase() in TRIGGER_WORDS_LOWER || token in TRIGGER_WORDS_EXACT
        }
    }

    private fun looksLikeBareAppName(query: String): Boolean {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.contains('?') || trimmed.contains('؟')) return false
        val words = trimmed.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size > 4 || trimmed.length > 40) return false
        val lower = trimmed.lowercase()
        if (NON_APP_CONVERSATION_PHRASES.any { lower == it || lower.startsWith("$it ") }) {
            return false
        }
        return true
    }

    private fun normalizeText(input: String): String {
        return input.lowercase()
            .trim()
            .replace(Regex("[^\\p{L}\\p{N}]"), "")
    }

    companion object {
        const val LOG_TAG = "JarvisApps"

        fun formatOpeningMessage(appLabel: String): String {
            return "OK, opening $appLabel."
        }

        fun formatNotFoundMessage(query: String): String {
            return "I could not find $query among installed apps. Installed apps list is in Settings."
        }

        private val TRIGGER_WORDS_LOWER = setOf(
            "open", "kholo", "khol", "chalao", "start", "kholen", "launch"
        )

        private val TRIGGER_WORDS_EXACT = setOf(
            "کھولو", "کھول", "کھولیں", "اوپن", "چلاؤ", "چلائیں"
        )

        private val FILLER_WORDS_LOWER = setOf(
            "open", "kholo", "khol", "chalao", "start", "kholen", "please", "app",
            "launch", "the", "my", "pls", "plz", "jarvis"
        )

        private val FILLER_WORDS_EXACT = setOf(
            "کھولو", "کھول", "کھولیں", "اوپن", "جلدی", "چلاؤ", "چلائیں", "ایپ", "ذرا", "جاروس"
        )

        private val URDU_TO_ENGLISH_ALIASES = mapOf(
            "یوٹیوب" to "youtube",
            "یو ٹیوب" to "youtube",
            "فیس بک" to "facebook",
            "فیسبک" to "facebook",
            "فیس بُک" to "facebook",
            "انسٹاگرام" to "instagram",
            "انسٹا گرام" to "instagram",
            "ٹیلیگرام" to "telegram",
            "ٹیلی گرام" to "telegram",
            "ٹیلے گرام" to "telegram",
            "واٹس ایپ" to "whatsapp",
            "وٹس ایپ" to "whatsapp",
            "ٹک ٹاک" to "tiktok",
            "ٹکٹاک" to "tiktok",
            "کروم" to "chrome",
            "سیٹنگز" to "settings",
            "سیٹنگس" to "settings",
            "سیٹنگ" to "settings",
            "کیلکولیٹر" to "calculator",
            "گیلری" to "gallery",
            "گالری" to "gallery",
            "فوٹوز" to "photos",
            "جی میل" to "gmail",
            "میپس" to "maps",
            "میسنجر" to "messenger",
            "میسینجر" to "messenger",
            "فائر فاکس" to "firefox",
            "فائرفاکس" to "firefox"
        )

        private val URDU_TO_ENGLISH_NORMALIZED: Map<String, String> =
            URDU_TO_ENGLISH_ALIASES.entries.associate { (k, v) ->
                k.lowercase().trim().replace(Regex("[^\\p{L}\\p{N}]"), "") to v
            }

        private val KNOWN_PACKAGE_ALIASES: Map<String, Pair<String, String>> = mapOf(
            "youtube" to ("com.google.android.youtube" to "YouTube"),
            "facebook" to ("com.facebook.katana" to "Facebook"),
            "fb" to ("com.facebook.katana" to "Facebook"),
            "messenger" to ("com.facebook.orca" to "Messenger"),
            "instagram" to ("com.instagram.android" to "Instagram"),
            "insta" to ("com.instagram.android" to "Instagram"),
            "telegram" to ("org.telegram.messenger" to "Telegram"),
            "whatsapp" to ("com.whatsapp" to "WhatsApp"),
            "tiktok" to ("com.zhiliaoapp.musically" to "TikTok"),
            "chrome" to ("com.android.chrome" to "Chrome"),
            "settings" to ("com.android.settings" to "Settings"),
            "calculator" to ("com.google.android.calculator" to "Calculator"),
            "gallery" to ("com.google.android.apps.photos" to "Photos"),
            "photos" to ("com.google.android.apps.photos" to "Photos"),
            "gmail" to ("com.google.android.gm" to "Gmail"),
            "maps" to ("com.google.android.apps.maps" to "Maps"),
            "firefox" to ("org.mozilla.firefox" to "Firefox")
        )

        private val NON_APP_CONVERSATION_PHRASES = setOf(
            "hi", "hello", "hey", "salam", "assalam", "assalamualaikum", "salam alaikum",
            "السلام علیکم", "وعلیکم السلام", "ہیلو", "سلام", "شکریہ", "thanks", "thank you",
            "ok", "okay", "yes", "no", "جی", "ہاں", "نہیں", "ٹھیک", "کیسے ہو", "کیا حال",
            "who are you", "what is", "how to", "why", "when", "where", "help", "مدد",
            "date", "time", "today", "tareekh", "tarikh", "تاریخ", "وقت", "ٹائم"
        )
    }
}

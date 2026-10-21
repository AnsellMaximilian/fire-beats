package io.github.ansellmaximilian.firebeats

import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

internal data class LocalScoreEntry(
    val id: String,
    val initials: String,
    val score: Int,
    val misses: Int,
    val trackId: String,
    val trackName: String,
    val achievedAtEpochMs: Long,
    val avatarFileName: String?
)

internal class LocalScoreboard(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val avatarDirectory = File(context.filesDir, AVATAR_DIRECTORY_NAME)

    @Synchronized
    fun lastInitials(): String = sanitizeInitials(
        preferences.getString(LAST_INITIALS_KEY, DEFAULT_INITIALS).orEmpty()
    )

    @Synchronized
    fun entriesForTrack(trackId: String): List<LocalScoreEntry> = readEntries()
        .filter { it.trackId == trackId }
        .sortedWith(scoreOrder)
        .take(MAX_SCORES_PER_TRACK)

    @Synchronized
    fun save(
        initials: String,
        score: Int,
        misses: Int,
        track: BeatTrack,
        avatarDataUrl: String?
    ): Pair<LocalScoreEntry, List<LocalScoreEntry>> {
        val cleanInitials = sanitizeInitials(initials)
        val entry = LocalScoreEntry(
            id = UUID.randomUUID().toString(),
            initials = cleanInitials,
            score = score.coerceAtLeast(0),
            misses = misses.coerceAtLeast(0),
            trackId = track.id,
            trackName = track.name,
            achievedAtEpochMs = System.currentTimeMillis(),
            avatarFileName = persistAvatar(avatarDataUrl)
        )
        val retained = (readEntries() + entry)
            .groupBy(LocalScoreEntry::trackId)
            .values
            .flatMap { trackEntries ->
                trackEntries.sortedWith(scoreOrder).take(MAX_SCORES_PER_TRACK)
            }
            .sortedByDescending(LocalScoreEntry::achievedAtEpochMs)
            .take(MAX_TOTAL_SCORES)

        val payload = JSONArray().apply {
            retained.forEach { scoreEntry -> put(scoreEntry.toJson()) }
        }.toString()
        preferences.edit()
            .putString(SCORES_KEY, payload)
            .putString(LAST_INITIALS_KEY, cleanInitials)
            .apply()
        removeUnusedAvatars(retained)

        return entry to retained
            .filter { it.trackId == track.id }
            .sortedWith(scoreOrder)
    }

    fun avatarFile(entry: LocalScoreEntry): File? = entry.avatarFileName
        ?.takeIf { it.matches(AVATAR_FILE_PATTERN) }
        ?.let { File(avatarDirectory, it) }
        ?.takeIf(File::isFile)

    private fun readEntries(): List<LocalScoreEntry> {
        val raw = preferences.getString(SCORES_KEY, null) ?: return emptyList()
        return runCatching {
            val values = JSONArray(raw)
            buildList(values.length()) {
                for (index in 0 until values.length()) {
                    values.optJSONObject(index)?.toScoreEntry()?.let(::add)
                }
            }
        }.onFailure { error ->
            Log.w(TAG, "Could not read local leaderboard", error)
        }.getOrDefault(emptyList())
    }

    private fun persistAvatar(dataUrl: String?): String? {
        if (dataUrl.isNullOrBlank()) return null
        return runCatching {
            val encoded = dataUrl.substringAfter(',', missingDelimiterValue = "")
            require(encoded.isNotBlank())
            val bytes = Base64.decode(encoded, Base64.DEFAULT)
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .take(12)
                .joinToString(separator = "") { byte -> "%02x".format(byte) }
            val fileName = "$digest.jpg"
            if (!avatarDirectory.exists()) avatarDirectory.mkdirs()
            val avatarFile = File(avatarDirectory, fileName)
            if (!avatarFile.exists()) avatarFile.writeBytes(bytes)
            fileName
        }.onFailure { error ->
            Log.w(TAG, "Could not save leaderboard avatar", error)
        }.getOrNull()
    }

    private fun removeUnusedAvatars(entries: List<LocalScoreEntry>) {
        val retainedNames = entries.mapNotNull(LocalScoreEntry::avatarFileName).toSet()
        avatarDirectory.listFiles()?.forEach { file ->
            if (file.isFile && file.name.matches(AVATAR_FILE_PATTERN) && file.name !in retainedNames) {
                file.delete()
            }
        }
    }

    private fun LocalScoreEntry.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("initials", initials)
        .put("score", score)
        .put("misses", misses)
        .put("trackId", trackId)
        .put("trackName", trackName)
        .put("achievedAt", achievedAtEpochMs)
        .put("avatar", avatarFileName)

    private fun JSONObject.toScoreEntry(): LocalScoreEntry? = runCatching {
        LocalScoreEntry(
            id = getString("id"),
            initials = sanitizeInitials(getString("initials")),
            score = getInt("score").coerceAtLeast(0),
            misses = optInt("misses", 0).coerceAtLeast(0),
            trackId = getString("trackId"),
            trackName = optString("trackName", "Beat track"),
            achievedAtEpochMs = optLong("achievedAt", 0L),
            avatarFileName = optString("avatar").takeIf { it.matches(AVATAR_FILE_PATTERN) }
        )
    }.getOrNull()

    private companion object {
        const val TAG = "FireTV"
        const val PREFERENCES_NAME = "firebeats_local_scoreboard"
        const val SCORES_KEY = "scores.v1"
        const val LAST_INITIALS_KEY = "last_initials.v1"
        const val AVATAR_DIRECTORY_NAME = "scoreboard-avatars"
        const val DEFAULT_INITIALS = "AAA"
        const val MAX_SCORES_PER_TRACK = 10
        const val MAX_TOTAL_SCORES = 100
        val AVATAR_FILE_PATTERN = Regex("[a-f0-9]{24}\\.jpg")
        val scoreOrder = compareByDescending<LocalScoreEntry> { it.score }
            .thenBy { it.misses }
            .thenBy { it.achievedAtEpochMs }

        fun sanitizeInitials(value: String): String = value
            .uppercase()
            .filter { it in 'A'..'Z' }
            .take(3)
            .padEnd(3, 'A')
    }
}

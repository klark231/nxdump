package com.nxdump

import android.content.Context

class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("nxdump", Context.MODE_PRIVATE)

    companion object {
        /** Upper bound of the "Parallel jobs" slider. */
        val maxJobs: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
        /** SAF writes stop scaling after a few threads, so don't default to every core. */
        val defaultJobs: Int = maxJobs.coerceAtMost(4)
    }

    /** Files copied in parallel while extracting (1 = one at a time). */
    var jobs: Int
        get() = sp.getInt("jobs", defaultJobs).coerceIn(1, maxJobs)
        set(v) = sp.edit().putInt("jobs", v.coerceIn(1, maxJobs)).apply()

    /** Use plain file access instead of SAF when "All files access" is granted and the output folder allows it. */
    var directAccess: Boolean
        get() = sp.getBoolean("direct", true)
        set(v) = sp.edit().putBoolean("direct", v).apply()

    var setupDone: Boolean
        get() = sp.getBoolean("setup", false)
        set(v) = sp.edit().putBoolean("setup", v).apply()

    var gamesTree: String?
        get() = sp.getString("games", null)
        set(v) = sp.edit().putString("games", v).apply()

    var outputTree: String?
        get() = sp.getString("output", null)
        set(v) = sp.edit().putString("output", v).apply()

    var extraFiles: Set<String>
        get() = HashSet(sp.getStringSet("extra", emptySet()) ?: emptySet())
        set(v) = sp.edit().putStringSet("extra", HashSet(v)).apply()
}

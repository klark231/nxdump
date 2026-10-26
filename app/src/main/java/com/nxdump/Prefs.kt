package com.nxdump

import android.content.Context

class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("nxdump", Context.MODE_PRIVATE)

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

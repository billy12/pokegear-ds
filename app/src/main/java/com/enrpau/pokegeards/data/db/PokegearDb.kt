package com.enrpau.pokegeards.data.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Raw-SQLite data layer for the Habitat / Route tracker. Mirrors
 * `projects/pokegear-ds/schema.sql`. Follows the existing [PokedexHelper] pattern
 * (CSV import in onCreate, raw-query getters) rather than Room, to keep the build
 * plugin-free for now — migrating to Room is design.md milestone 2.
 *
 * On first open it imports one "data pack" from assets/packs/<id>/:
 *   - "bdsp"       full pack, written by tooling / data agents (species+locations+encounters)
 *   - "_bootstrap" hand-entered sample, always shipped, used when "bdsp" is absent/short
 */
class PokegearDb private constructor(private val context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        const val DB_NAME = "pokegear.db"
        const val DB_VERSION = 2   // v2: location.zone_id
        private const val TAG = "PokegearDb"
        private const val KEY_PACK = "pack_id"

        @Volatile private var instance: PokegearDb? = null
        fun get(context: Context): PokegearDb =
            instance ?: synchronized(this) {
                instance ?: PokegearDb(context.applicationContext).also { instance = it }
            }
    }

    // ---------------------------------------------------------------- schema

    override fun onCreate(db: SQLiteDatabase) {
        createSchema(db)
        importActivePack(db)
    }

    private fun createSchema(db: SQLiteDatabase) {
        createSchemaFor(db)
        db.execSQL(
            """CREATE TABLE player_state (
                 pack_id TEXT NOT NULL, species_id INTEGER NOT NULL,
                 is_caught INTEGER NOT NULL DEFAULT 0, is_seen INTEGER NOT NULL DEFAULT 0,
                 note TEXT, updated_at INTEGER NOT NULL,
                 PRIMARY KEY (pack_id, species_id) )"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        dropContent(db)
        db.execSQL("DROP TABLE IF EXISTS player_state")
        onCreate(db)
    }

    private fun dropContent(db: SQLiteDatabase) {
        for (t in listOf("encounter", "location", "species", "pack")) {
            db.execSQL("DROP TABLE IF EXISTS $t")
        }
    }

    /**
     * Re-import if the resolved pack no longer matches what's loaded (the user
     * switched packs). player_state is preserved — catch flags are keyed by
     * (pack_id, species_id), so each pack keeps its own progress. Returns true
     * if a rebuild happened. Call off the main thread.
     */
    fun syncPack(): Boolean {
        val want = resolvePackId()
        if (safeActivePackId() == want) return false
        Log.d(TAG, "Pack change -> rebuilding as '$want'")
        val db = writableDatabase
        dropContent(db)
        createSchemaFor(db)
        importActivePack(db)
        return true
    }

    /** createSchema minus player_state (kept across pack switches). */
    private fun createSchemaFor(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE pack ( id TEXT PRIMARY KEY, name TEXT NOT NULL, mechanics TEXT NOT NULL,
                 dex_count INTEGER NOT NULL, version TEXT NOT NULL, source_note TEXT )"""
        )
        db.execSQL(
            """CREATE TABLE species ( pack_id TEXT NOT NULL, id INTEGER NOT NULL, name TEXT NOT NULL,
                 type1 TEXT NOT NULL, type2 TEXT, base_hp INTEGER, base_atk INTEGER, base_def INTEGER,
                 base_spa INTEGER, base_spd INTEGER, base_spe INTEGER, sprite_key TEXT NOT NULL,
                 PRIMARY KEY (pack_id, id) )"""
        )
        db.execSQL(
            """CREATE TABLE location ( pack_id TEXT NOT NULL, id INTEGER NOT NULL, name TEXT NOT NULL,
                 region TEXT, map_group TEXT, sort_order INTEGER NOT NULL, zone_id INTEGER,
                 PRIMARY KEY (pack_id, id) )"""
        )
        db.execSQL(
            """CREATE TABLE encounter ( id INTEGER PRIMARY KEY AUTOINCREMENT, pack_id TEXT NOT NULL,
                 location_id INTEGER NOT NULL, species_id INTEGER NOT NULL, method TEXT NOT NULL,
                 time_of_day TEXT NOT NULL, rate INTEGER, min_level INTEGER NOT NULL,
                 max_level INTEGER NOT NULL, condition_note TEXT )"""
        )
        db.execSQL("CREATE INDEX idx_encounter_loc ON encounter(pack_id, location_id)")
    }

    private fun safeActivePackId(): String = try { activePackId() } catch (e: Exception) { "" }

    // ---------------------------------------------------------------- import

    private fun importActivePack(db: SQLiteDatabase) {
        val packId = resolvePackId()
        Log.d(TAG, "Importing pack '$packId'")
        val base = "packs/$packId"

        val meta = readPackJson("$base/pack.json")
        db.insert("pack", null, ContentValues().apply {
            put("id", packId)
            put("name", meta["name"] ?: packId)
            put("mechanics", meta["mechanics"] ?: "GEN_6_PLUS")
            put("dex_count", (meta["dex_count"] ?: "0").toIntOrNull() ?: 0)
            put("version", meta["version"] ?: "0")
            put("source_note", meta["source_note"])
        })

        db.beginTransaction()
        try {
            forEachCsvRow("$base/species.csv") { c ->
                db.insert("species", null, ContentValues().apply {
                    put("pack_id", packId)
                    put("id", c["id"]!!.toInt())
                    put("name", c["name"])
                    put("type1", c["type1"]!!.uppercase())
                    put("type2", c["type2"]?.ifBlank { null }?.uppercase())
                    put("base_hp", c["base_hp"]?.toIntOrNull())
                    put("base_atk", c["base_atk"]?.toIntOrNull())
                    put("base_def", c["base_def"]?.toIntOrNull())
                    put("base_spa", c["base_spa"]?.toIntOrNull())
                    put("base_spd", c["base_spd"]?.toIntOrNull())
                    put("base_spe", c["base_spe"]?.toIntOrNull())
                    put("sprite_key", c["sprite_key"]?.ifBlank { null } ?: "%03d".format(c["id"]!!.toInt()))
                })
            }
            forEachCsvRow("$base/locations.csv") { c ->
                db.insert("location", null, ContentValues().apply {
                    put("pack_id", packId)
                    put("id", c["id"]!!.toInt())
                    put("name", c["name"])
                    put("region", c["region"])
                    put("map_group", c["map_group"])
                    put("sort_order", c["sort_order"]?.toIntOrNull() ?: c["id"]!!.toInt())
                    put("zone_id", c["zone_id"]?.toIntOrNull())
                })
            }
            forEachCsvRow("$base/encounters.csv") { c ->
                db.insert("encounter", null, ContentValues().apply {
                    put("pack_id", packId)
                    put("location_id", c["location_id"]!!.toInt())
                    put("species_id", c["species_id"]!!.toInt())
                    put("method", c["method"]!!.uppercase())
                    put("time_of_day", (c["time_of_day"]?.ifBlank { null } ?: "ANY").uppercase())
                    put("rate", c["rate"]?.toIntOrNull())
                    put("min_level", c["min_level"]?.toIntOrNull() ?: 1)
                    put("max_level", c["max_level"]?.toIntOrNull() ?: (c["min_level"]?.toIntOrNull() ?: 1))
                    put("condition_note", c["condition_note"]?.ifBlank { null })
                })
            }
            db.setTransactionSuccessful()
        } catch (e: Exception) {
            Log.e(TAG, "Pack import failed", e)
        } finally {
            db.endTransaction()
        }
    }

    /**
     * The pack to load: an explicit user override if it's valid, else "bdsp" if
     * its assets look complete, else "_bootstrap".
     */
    private fun resolvePackId(): String {
        prefs().getString(KEY_PACK, null)?.let { if (packAssetsOk(it)) return it }
        return if (packAssetsOk("bdsp")) "bdsp" else "_bootstrap"
    }

    private fun packAssetsOk(id: String): Boolean = try {
        (context.assets.list("packs")?.toSet() ?: emptySet()).contains(id) &&
            countLines("packs/$id/species.csv") > 5 &&
            countLines("packs/$id/encounters.csv") > 3
    } catch (e: Exception) {
        false
    }

    private fun prefs() = context.getSharedPreferences("pokegear", Context.MODE_PRIVATE)

    /** Packs with complete assets: (id, display name). */
    fun availablePacks(): List<Pair<String, String>> {
        val ids = try { context.assets.list("packs")?.toList() ?: emptyList() } catch (e: Exception) { emptyList() }
        return ids.filter { packAssetsOk(it) }
            .map { it to (readPackJson("packs/$it/pack.json")["name"] ?: it) }
            .sortedBy { it.second }
    }

    /** (packId, lowercase title keywords) for boot-time title-screen auto-detect. */
    fun packTitleMatchers(): List<Pair<String, List<String>>> {
        return availablePacks().mapNotNull { (id, _) ->
            val raw = readPackJson("packs/$id/pack.json")["title_match"]?.lowercase()?.trim().orEmpty()
            val kw = raw.split(",").map { it.trim() }.filter { it.length >= 4 }
            if (kw.isEmpty()) null else id to kw
        }
    }

    /** Set the active pack. Caller then runs [syncPack] off the main thread. */
    fun setPackOverride(id: String?) {
        prefs().edit().apply { if (id == null) remove(KEY_PACK) else putString(KEY_PACK, id) }.apply()
    }

    private fun countLines(path: String): Int = try {
        context.assets.open(path).bufferedReader().useLines { it.count() }
    } catch (e: Exception) {
        0
    }

    private fun readPackJson(path: String): Map<String, String> {
        return try {
            val text = context.assets.open(path).bufferedReader().use { it.readText() }
            Regex("\"(\\w+)\"\\s*:\\s*\"([^\"]*)\"").findAll(text)
                .associate { it.groupValues[1] to it.groupValues[2] }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private inline fun forEachCsvRow(path: String, crossinline body: (Map<String, String>) -> Unit) {
        val reader = BufferedReader(InputStreamReader(context.assets.open(path)))
        reader.use { r ->
            val header = splitCsv(r.readLine() ?: return).map { it.trim().removePrefix("﻿") }
            var line = r.readLine()
            while (line != null) {
                if (line.isNotBlank()) {
                    val cells = splitCsv(line)
                    if (cells.isNotEmpty() && cells[0].isNotBlank()) {
                        val row = HashMap<String, String>(header.size)
                        header.forEachIndexed { i, key -> row[key] = cells.getOrElse(i) { "" }.trim() }
                        try { body(row) } catch (e: Exception) { Log.w(TAG, "bad row in $path: $line", e) }
                    }
                }
                line = r.readLine()
            }
        }
    }

    private fun splitCsv(line: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var inQuotes = false
        for (ch in line) when {
            ch == '"' -> inQuotes = !inQuotes
            ch == ',' && !inQuotes -> { out.add(sb.toString()); sb.clear() }
            else -> sb.append(ch)
        }
        out.add(sb.toString())
        return out
    }

    // ---------------------------------------------------------------- queries

    fun activePackId(): String {
        readableDatabase.rawQuery("SELECT id FROM pack LIMIT 1", null).use { c ->
            return if (c.moveToFirst()) c.getString(0) else "_bootstrap"
        }
    }

    fun getLocations(): List<LocationRow> {
        val pack = activePackId()
        val out = ArrayList<LocationRow>()
        readableDatabase.rawQuery(
            "SELECT id,name,region,map_group,sort_order FROM location WHERE pack_id=? ORDER BY sort_order,name",
            arrayOf(pack)
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    LocationRow(
                        id = c.getInt(0),
                        name = c.getString(1),
                        region = c.getString(2),
                        mapGroup = c.getString(3),
                        sortOrder = c.getInt(4),
                    )
                )
            }
        }
        return out
    }

    /** (species id, name) for the active pack — for fuzzy-matching OCR text. */
    fun speciesNames(): List<Pair<Int, String>> {
        val pack = activePackId()
        val out = ArrayList<Pair<Int, String>>()
        readableDatabase.rawQuery(
            "SELECT id,name FROM species WHERE pack_id=?", arrayOf(pack)
        ).use { c -> while (c.moveToNext()) out.add(c.getInt(0) to c.getString(1)) }
        return out
    }

    /** All caught species ids in the active pack — for the Battledex list markers. */
    fun caughtIds(): Set<Int> {
        val pack = activePackId()
        val out = HashSet<Int>()
        readableDatabase.rawQuery(
            "SELECT species_id FROM player_state WHERE pack_id=? AND is_caught=1", arrayOf(pack)
        ).use { c -> while (c.moveToNext()) out.add(c.getInt(0)) }
        return out
    }

    fun isCaught(speciesId: Int): Boolean {
        val pack = activePackId()
        readableDatabase.rawQuery(
            "SELECT is_caught FROM player_state WHERE pack_id=? AND species_id=?",
            arrayOf(pack, speciesId.toString())
        ).use { c -> return c.moveToFirst() && c.getInt(0) == 1 }
    }

    /** Map a raw in-game ZoneID (from the emulator bridge) to this pack's location id. */
    fun locationForZone(zoneId: Int): Int? {
        val pack = activePackId()
        readableDatabase.rawQuery(
            "SELECT id FROM location WHERE pack_id=? AND (zone_id=? OR (zone_id IS NULL AND id=?)) LIMIT 1",
            arrayOf(pack, zoneId.toString(), zoneId.toString())
        ).use { c -> return if (c.moveToFirst()) c.getInt(0) else null }
    }

    /** Methods present at a location, in a stable display order. */
    fun methodsAt(locationId: Int): List<String> = distinctAt(locationId, "method", METHOD_ORDER)

    /** Times of day present at a location (excluding ANY), in display order. */
    fun timesAt(locationId: Int): List<String> =
        distinctAt(locationId, "time_of_day", TIME_ORDER).filter { it != "ANY" }

    private fun distinctAt(locationId: Int, col: String, order: List<String>): List<String> {
        val pack = activePackId()
        val found = HashSet<String>()
        readableDatabase.rawQuery(
            "SELECT DISTINCT $col FROM encounter WHERE pack_id=? AND location_id=?",
            arrayOf(pack, locationId.toString())
        ).use { c -> while (c.moveToNext()) c.getString(0)?.let { found.add(it) } }
        return order.filter { it in found } + found.filter { it !in order }.sorted()
    }

    fun getEncounters(
        locationId: Int,
        methods: Set<String> = emptySet(),
        times: Set<String> = emptySet(),
    ): List<EncounterRow> {
        val pack = activePackId()
        val args = ArrayList<String>()
        args.add(pack); args.add(pack); args.add(locationId.toString())
        val sb = StringBuilder(
            """SELECT MIN(e.id) AS enc_id,
                      e.method,
                      CASE WHEN COUNT(DISTINCT e.time_of_day) > 1 THEN 'ANY' ELSE MIN(e.time_of_day) END AS tod,
                      CASE WHEN COUNT(e.rate) = 0 THEN NULL ELSE SUM(e.rate) END AS agg_rate,
                      MIN(e.min_level), MAX(e.max_level), MAX(e.condition_note),
                      s.id, s.name, s.type1, s.type2, s.base_hp, s.base_atk, s.base_def,
                      s.base_spa, s.base_spd, s.base_spe, s.sprite_key,
                      MAX(COALESCE(ps.is_caught, 0))
               FROM encounter e
               JOIN species s ON s.pack_id = e.pack_id AND s.id = e.species_id
               LEFT JOIN player_state ps ON ps.pack_id = e.pack_id AND ps.species_id = e.species_id
               WHERE e.pack_id = ? AND s.pack_id = ? AND e.location_id = ?"""
        )
        if (methods.isNotEmpty()) {
            sb.append(" AND e.method IN (").append(methods.joinToString(",") { "?" }).append(")")
            args.addAll(methods)
        }
        if (times.isNotEmpty()) {
            // a time filter matches that time OR encounters that are ANY-time
            sb.append(" AND (e.time_of_day = 'ANY' OR e.time_of_day IN (")
                .append(times.joinToString(",") { "?" }).append("))")
            args.addAll(times)
        }
        sb.append(" GROUP BY e.species_id, e.method")
        sb.append(" ORDER BY (agg_rate IS NULL), agg_rate DESC, s.id")

        val out = ArrayList<EncounterRow>()
        readableDatabase.rawQuery(sb.toString(), args.toTypedArray()).use { c ->
            while (c.moveToNext()) {
                val species = SpeciesRow(
                    id = c.getInt(7),
                    name = c.getString(8),
                    type1 = c.getString(9),
                    type2 = if (c.isNull(10)) null else c.getString(10),
                    baseHp = c.getInt(11), baseAtk = c.getInt(12), baseDef = c.getInt(13),
                    baseSpa = c.getInt(14), baseSpd = c.getInt(15), baseSpe = c.getInt(16),
                    spriteKey = c.getString(17),
                )
                out.add(
                    EncounterRow(
                        encounterId = c.getLong(0),
                        species = species,
                        method = c.getString(1),
                        timeOfDay = c.getString(2),
                        rate = if (c.isNull(3)) null else c.getInt(3),
                        minLevel = c.getInt(4),
                        maxLevel = c.getInt(5),
                        conditionNote = if (c.isNull(6)) null else c.getString(6),
                        isCaught = c.getInt(18) == 1,
                    )
                )
            }
        }
        return out
    }

    fun setCaught(speciesId: Int, caught: Boolean) {
        val pack = activePackId()
        writableDatabase.execSQL(
            """INSERT INTO player_state (pack_id, species_id, is_caught, is_seen, updated_at)
               VALUES (?, ?, ?, 1, ?)
               ON CONFLICT(pack_id, species_id)
               DO UPDATE SET is_caught = excluded.is_caught, updated_at = excluded.updated_at""",
            arrayOf<Any>(pack, speciesId, if (caught) 1 else 0, System.currentTimeMillis())
        )
    }

    /** Total caught species in the active pack (for the Pokédex rebuild counter). */
    fun caughtCount(): Int {
        val pack = activePackId()
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM player_state WHERE pack_id=? AND is_caught=1", arrayOf(pack)
        ).use { c -> return if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    /**
     * Wipe caught flags for the active pack only — the "clear" half of the
     * Pokédex rebuild. Other packs' progress is untouched (keyed by pack_id).
     * Call off the main thread.
     */
    fun clearCaughtForActivePack() {
        val pack = activePackId()
        writableDatabase.execSQL(
            "UPDATE player_state SET is_caught=0, updated_at=? WHERE pack_id=?",
            arrayOf<Any>(System.currentTimeMillis(), pack)
        )
    }

    fun progressAt(locationId: Int): AreaProgress {
        val pack = activePackId()
        var total = 0
        var caught = 0
        readableDatabase.rawQuery(
            """SELECT COUNT(DISTINCT e.species_id),
                      COUNT(DISTINCT CASE WHEN ps.is_caught = 1 THEN e.species_id END)
               FROM encounter e
               LEFT JOIN player_state ps ON ps.pack_id = e.pack_id AND ps.species_id = e.species_id
               WHERE e.pack_id = ? AND e.location_id = ?""",
            arrayOf(pack, locationId.toString())
        ).use { c -> if (c.moveToFirst()) { total = c.getInt(0); caught = c.getInt(1) } }
        return AreaProgress(caught, total)
    }
}

private val METHOD_ORDER = listOf(
    "WALK", "SURF", "OLD_ROD", "GOOD_ROD", "SUPER_ROD",
    "ROCK_SMASH", "HONEY_TREE", "RADAR", "SWARM", "GRAND_UNDERGROUND", "STATIC"
)
private val TIME_ORDER = listOf("MORNING", "DAY", "NIGHT", "ANY")

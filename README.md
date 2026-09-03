# PokéGear DS

**PokéGear DS** is a companion app for dual-screen Android gaming handhelds — built
for the **AYN Thor**. It runs on the lower touchscreen while a Pokémon game plays
on the main screen, and answers three questions in real time without breaking
immersion:

1. **Where am I, and what lives here?** — a habitat / route encounter tracker.
2. **Who am I fighting right now?** — live battle type-effectiveness analysis.
3. **What am I still missing from this area?** — caught / uncaught checklists with
   the exact encounter conditions (method, time of day, rate, level range).

Modelled on the HeartGold/SoulSilver bottom-screen PokéGear / Pokédex Habitat mode.
First-class support for **Brilliant Diamond / Shining Pearl** and **Luminescent
Platinum**.

> Forked from [enrique-paulino/DualScreenDex](https://github.com/enrique-paulino/DualScreenDex)
> (MIT), which contributes the OCR battle scanner and the ROM-profile / CSV system.
> PokéGear DS adds the habitat tracker, an encounter/location data model, and
> per-game data packs.

---

## Feature pillars

### Habitat & Route tracker (core)

* **Encounter grid** for the current area — every species that can appear, from a
  local SQLite database.
* **Catch-status matrix** — caught species in full colour with a level range;
  uncaught ones as dimmed silhouettes. Tap to toggle; it persists.
* **Filters** by encounter method (Grass, Surf, the fishing rods, Rock Smash,
  PokéRadar, Swarm, Grand Underground…) and time of day, plus an "uncaught only"
  toggle.
* **Encounter card** (long-press) — rate, level range, spawn conditions, base
  stats, types.
* **Per-area progress** ("17 / 24 caught here").
* **Swappable data packs** — BDSP and Luminescent Platinum ship built in; each
  keeps its own catch progress.

### Live battle context (from DualScreenDex)

* OCR battle scanner (AccessibilityService + Google ML Kit) identifies the
  opposing Pokémon and shows defensive weaknesses / resistances.
* Supports multi-Pokémon battles, generation-specific type logic, and custom
  matchup charts.

### Lower-screen UX

* Touch-optimised, non-blocking layout for a secondary landscape strip.
* OLED / battery-friendly: the scanner sleeps when backgrounded and polls on a
  timer.

---

## How location detection works

The data engine is **detection-agnostic** — the UI takes a `location_id` and
`active_species_id` from a shared state provider, and any of these can feed it:

| Tier | Method | Status |
| --- | --- | --- |
| Manual | A sticky location picker on the lower screen | **working** |
| OCR | Read the in-game area banner on zone entry, fuzzy-match against the pack's location list | planned |
| Emulator bridge | Speak the GDB Remote Serial Protocol to Eden's debug stub (`localhost:6543`) and read game RAM directly | investigating — feasible (Eden inherits Yuzu's GDB stub) |

See [`docs/NEXT.md`](docs/NEXT.md) for the roadmap.

---

## Data packs

A pack is one game's data as CSV, under `app/src/main/assets/packs/<id>/`:

```
species.csv    id,name,type1,type2,base_hp,base_atk,base_def,base_spa,base_spd,base_spe,sprite_key
locations.csv  id,name,region,map_group,sort_order
encounters.csv location_id,species_id,method,time_of_day,rate,min_level,max_level,condition_note
pack.json      { id, name, mechanics, dex_count, version, source_note }
```

Built in:

* **`bdsp`** — 493 species, 73 Sinnoh locations, 1161 encounters (PokéAPI
  Diamond/Pearl tables; Grand Underground is approximate).
* **`lumi_plat`** — 513 species, 158 locations, 4034 encounters, from the LP 3.0
  gamedata behind [luminescent.team/mapper](https://luminescent.team/mapper).

Schema and pack format live in the design docs (see below).

---

## Tech stack

* **Language:** Kotlin
* **UI:** XML layouts / Material 3, MVVM (`ViewModel` + `LiveData`)
* **OCR:** Google ML Kit (on-device)
* **Data:** SQLite (`android.database.sqlite`), CSV-seeded on first launch
* **Detection:** `AccessibilityService` + a shared `GameStateProvider`

Design docs (PRD, technical design, schema, data-pack format) are kept in a
separate planning repo, summarised in [`docs/NEXT.md`](docs/NEXT.md).

---

## Building

```
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Needs JDK 17+ and the Android SDK (compileSdk 36, minSdk 30).

---

## License

MIT — see [LICENSE](LICENSE). Inherited from DualScreenDex.

Bundled sprite icons are from the PokéAPI sprites collection (fan-sourced game
rips, used here as a placeholder under fair use).

---

*Disclaimer: PokéGear DS is an unofficial, free, fan-made app and is NOT
affiliated with, endorsed, or supported by Nintendo, Game Freak, or The Pokémon
Company. Pokémon and Pokémon character names are trademarks of Nintendo.*

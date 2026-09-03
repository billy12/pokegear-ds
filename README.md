# PokéGear DS

PokéGear DS is a companion app for dual-screen Android handhelds, built for the
AYN Thor. It runs on the lower touchscreen while a Pokémon game plays on the main
screen, and covers what the HeartGold/SoulSilver PokéGear did from the bottom
screen: where you are and what lives there, what you're fighting, and what you
still haven't caught in the current area.

Specifically:

- A habitat/route tracker: every species that can appear in your current area,
  with the method, time of day, rate, and level range for each.
- Live battle analysis: the opposing Pokémon's type weaknesses and resistances.
- Caught/uncaught checklists per area.

Brilliant Diamond / Shining Pearl and Luminescent Platinum both ship with their
own data.

> Forked from [enrique-paulino/DualScreenDex](https://github.com/enrique-paulino/DualScreenDex)
> (MIT). DualScreenDex provides the OCR battle scanner and the ROM-profile / CSV
> system; PokéGear DS adds the habitat tracker, the encounter/location data
> model, and the per-game data packs.

## Habitat & route tracker

This is the main feature. For the selected area it shows:

- An encounter grid of every species that can appear there, read from a local
  SQLite database. Caught species show in full colour with their level range;
  uncaught ones show as dimmed silhouettes. Tapping a species toggles its caught
  state, which persists.
- Filters by encounter method (grass, surf, the three rods, Rock Smash,
  PokéRadar, Swarm, Grand Underground, and so on) and by time of day, plus an
  "uncaught only" toggle.
- An encounter card on long-press with the rate, level range, spawn conditions,
  base stats, and types.
- A per-area count ("17 / 24 caught here").

Data packs are swappable in-app. BDSP and Luminescent Platinum are built in, and
each keeps its own catch progress.

## Live battle context

Carried over from DualScreenDex. The OCR scanner (AccessibilityService + Google
ML Kit) reads the opposing Pokémon's name off the screen and shows its defensive
weaknesses and resistances. It handles multi-Pokémon battles, generation-specific
type logic, and custom matchup charts.

## Lower-screen UX

The layout is built for a secondary landscape strip: touch targets sized for it,
nothing that blocks the view. The scanner sleeps when the app is backgrounded and
polls on a timer to keep heat and battery down.

## How location detection works

The data engine doesn't care where `location_id` and `active_species_id` come
from. A shared state provider supplies them, and any of these can be that
provider:

| Tier | Method | Status |
| --- | --- | --- |
| Manual | A sticky location picker on the lower screen | Working |
| OCR | Read the in-game area banner on zone entry and fuzzy-match it against the pack's location list | Planned |
| Emulator bridge | Speak the GDB Remote Serial Protocol to Eden's debug stub (`localhost:6543`) and read game RAM directly | Investigating; looks feasible, since Eden inherits Yuzu's GDB stub |

Roadmap: [`docs/NEXT.md`](docs/NEXT.md).

## Data packs

A pack is one game's data as CSV, under `app/src/main/assets/packs/<id>/`:

```
species.csv    id,name,type1,type2,base_hp,base_atk,base_def,base_spa,base_spd,base_spe,sprite_key
locations.csv  id,name,region,map_group,sort_order
encounters.csv location_id,species_id,method,time_of_day,rate,min_level,max_level,condition_note
pack.json      { id, name, mechanics, dex_count, version, source_note }
```

Built in:

- `bdsp`: 493 species, 73 Sinnoh locations, 1161 encounters, from PokéAPI's
  Diamond/Pearl tables. Grand Underground data is approximate.
- `lumi_plat`: 513 species, 158 locations, 4034 encounters, from the LP 3.0
  gamedata that backs [luminescent.team/mapper](https://luminescent.team/mapper).

The schema and pack format are in the design docs (see below).

## Tech stack

- Kotlin
- XML layouts / Material 3, MVVM (`ViewModel` + `LiveData`)
- Google ML Kit for on-device OCR
- SQLite (`android.database.sqlite`), seeded from the CSV packs on first launch
- `AccessibilityService` plus a shared `GameStateProvider` for detection

The design docs (PRD, technical design, schema, data-pack format) live in a
separate planning repo. [`docs/NEXT.md`](docs/NEXT.md) summarises them.

## Building

```
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Needs JDK 17+ and the Android SDK (compileSdk 36, minSdk 30).

## License

MIT, see [LICENSE](LICENSE). Inherited from DualScreenDex.

The bundled sprite icons come from the PokéAPI sprites collection (fan-sourced
game rips, used here as a placeholder under fair use).

---

*Disclaimer: PokéGear DS is an unofficial, free, fan-made app and is NOT
affiliated with, endorsed, or supported by Nintendo, Game Freak, or The Pokémon
Company. Pokémon and Pokémon character names are trademarks of Nintendo.*

# Next steps

Milestones 1–4 done: fork + rename (**app is now "PokéGear DS"**), SQLite data
layer, BDSP + Luminescent Platinum packs with in-app switching, Habitat/Route
tracker UI. Emulator-verified. Battle mode untouched.

Design reference is in the **my-brain** repo at `projects/pokegear-ds/`
(`design.md` §3 module plan / §4 milestones, `schema.sql`, `data/format.md`,
`context.md` = current status).

---

## Decisions — settled

* **Name:** PokéGear DS. `app_name` string updated; the launcher label and the
  Accessibility-settings entry now read "PokéGear DS". *(Still internal-only:
  the theme style `Theme.DualScreenDex` and the class
  `DualDexAccessibilityService` — rename later if you want, it's refactor churn
  with no user-facing effect.)*
* **Sprites:** keep the PokéAPI fan-rip icons as-is.
* **Room:** skip. "Room" is Android's ORM library — you write annotated Kotlin
  classes and it generates the SQLite code. The app currently uses hand-written
  SQL in `PokegearDb`, which works fine. Room would only save boilerplate and add
  compile-time query checking, at the cost of a Gradle plugin. Not worth it now.
* **Detection approach:** go for the **Eden emulator bridge** first (section 3).

---

## 0. Get it on the AYN Thor

Highest-value step — everything else reprioritises once you've used it in a real
session.

* **Android Studio** (optional): `winget install --id Google.AndroidStudio`
  (approve UAC), open this repo, Settings → Android SDK → point at
  `%LOCALAPPDATA%\Android\Sdk`.
* **Or CLI:** `.\gradlew.bat assembleDebug`, then plug in the Thor (USB debugging
  on) and `adb install -r app\build\outputs\apk\debug\app-debug.apk`.
* Reaching Habitat right now: the "🌿 Habitat" button on the main screen.
  `MainActivity` bounces to Accessibility Settings on launch (DualScreenDex
  onboarding) — enable the service once, back out, tap the button. Section 4
  makes Habitat a proper entry point.
* Play a real BDSP / LP session on the lower screen for ~30 min and note what's
  annoying.

---

## 1. Quick wins (~30–60 min each)

* **Location spinner truncates** ("Rou.."). `activity_habitat.xml` — widen it or
  move the AREA row above the filters.
* **Detail card has no type matchups.** design.md §2.4 wants defensive
  weaknesses/resistances. DualScreenDex already has `TypeMatchup.kt` +
  `MainViewModel.calculateMatchups()` — lift that into `EncounterCardDialog`.
* **Grid shows some species twice** (two slot entries, different level ranges).
  Merge in `PokegearDb.getEncounters` or leave it.

---

## 2. Data completeness

* **LP:** add Grand Underground, Honey Tree, static/legendary/gift, roaming — LP
  documents these separately from route data; check `TeamLumi/luminescent-team`
  for other gamedata JSON.
* **BDSP:** replace the 15 approximate Grand Underground rows with real biome
  tables (Serebii / Bulbapedia BDSP Grand Underground).
* **Both:** `species_variant` in `schema.sql` exists but is unused — regional
  forms are folded into base ids. Split them for per-form encounters.

---

## 3. Eden emulator bridge (the main advanced feature)

**Feasibility: yes.** Eden's source (`git.eden-emu.dev/eden-emu/eden`) includes
the Yuzu **GDB stub** at `src/core/debugger/gdbstub.cpp`, and the Android build
starts it (`src/android/.../native.cpp` calls `InitializeDebugger()` when
enabled). Settings: `use_gdbstub` (default off) + `gdbstub_port` (default
**6543**), in Eden's `config.ini` under `[Debugging]`. The stub listens on
**localhost**, so an on-device companion can connect with **no root**. The LP
team already uses this stub for exefs debugging
(`luminescent.team/rom-hacking/exefs/debugging`).

### Plan

1. **Confirm on the Thor**: enable `use_gdbstub=true` in Eden (edit `config.ini`
   if there's no Android UI toggle), launch BDSP, and check that port 6543 is
   listening (`adb shell` → `netstat`, or just try to connect).
2. **Speak GDB RSP from Kotlin.** No GDB binary — it's a simple ASCII protocol:
   `$<packet>#<hexchecksum>`, ack with `+`. The only packets you need:
   * `?` / `qSupported` on connect
   * `m<addr>,<len>` — read guest memory (returns hex)
   * `c` — continue, and `\x03` (interrupt) to halt again
   ~200–300 lines. Put it behind `EmulatorBridgeStateProvider` in `detection/`
   (currently a stub) so the UI doesn't care which provider feeds state.
3. **Poll pattern.** Attaching halts the guest CPU. Per poll: interrupt → a few
   small `m` reads → `c`. At a 1–2 s cadence (what the OCR path already uses) this
   *may* cause a slight hitch — **test this early**, it's the main risk. If it's
   too stuttery, fall back to OCR (section 5); the abstraction makes that a
   config switch, not a rewrite.
4. **RAM address maps** — the real work. Need pointer chains for: current zone /
   location id, wild-encounter state (active species or the route's table
   pointer), battle flag + opponent species. Sources: the Atmosphère/Switch cheat
   repos (BDSP cheat `.txt` files use the same pointer-chain format), and the LP
   ROM-hacking community for LP's shifted exefs addresses. BDSP is Unity/IL2CPP.
   Maintain one map per pack (`bdsp`, `lumi_plat`).

### If the bridge stalls

OCR (section 5) is the fallback and needs the same `GameStateProvider` plumbing,
so nothing is wasted.

---

## 4. Battle ↔ habitat mode switch

* Make `HabitatActivity` a real entry point: `LAUNCHER` intent-filter (or make it
  MainActivity's default view) and drop the forced Accessibility-Settings
  redirect for Habitat-only use.
* On `GamePhase.BATTLE` from `GameStateRepository`, auto-show the battle view
  (reuse DualScreenDex's battle UI + `calculateMatchups`); on `OVERWORLD`, show
  Habitat.
* Wire `POKEMON_DETECTED` → `GameStateRepository.manual.setBattleSpecies(...)` so
  both features read one shared state.

---

## 5. Location-banner OCR (fallback / alternative to section 3)

* Second OCR pass in `DualDexAccessibilityService` on a configurable crop (the
  area banner, ~1–2 s window).
* Per-pack location gazetteer — `PokegearDb.getLocations()` gives the names;
  fuzzy-match with the same Levenshtein as the species matcher.
* Debounce: change location only after N consistent reads, never mid-battle.
* Feed `GameStateRepository` via the `OcrStateProvider` stub.

---

## 6. Real-device polish (after section 0 feedback)

* Layout for the Thor lower screen's real dimensions / aspect.
* OLED + power-saving options (PRD §3C, design.md §2.6).
* Bigger touch targets if the grid feels cramped in hand.

---

## Suggested order

0 → 1 → 3 (spike the Eden bridge early: steps 1–3 of section 3 answer "is this
viable?" in an afternoon) → 4 → 2 → 6. Section 5 only if section 3 proves
unworkable.

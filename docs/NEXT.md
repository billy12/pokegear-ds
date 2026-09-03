# Next steps

Where things stand: milestones 1–4 done (fork + rename, SQLite data layer, BDSP +
Luminescent Platinum packs with in-app switching, Habitat/Route tracker UI).
Emulator-verified. Battle mode still works untouched.

Design reference lives in the **my-brain** repo at `projects/pokegear-ds/`
(`design.md` §3 module plan / §4 milestones, `schema.sql`, `data/format.md`,
`context.md` = current status).

---

## 0. Get it on real hardware first

The single highest-value step. Everything below is easier to prioritise once
you've used it next to an actual game on the AYN Thor.

- **Android Studio** (optional but nice): `winget install --id Google.AndroidStudio`
  (approve the UAC prompt), open this repo, Settings → Android SDK → point at
  `%LOCALAPPDATA%\Android\Sdk` so it shares the existing SDK.
- **Or stay CLI:** `.\gradlew.bat assembleDebug`, then plug in the Thor over USB
  (USB debugging on) and `adb install -r app\build\outputs\apk\debug\app-debug.apk`.
- **Reaching the Habitat screen right now:** it's behind the "🌿 Habitat" button
  on the main screen, and `MainActivity` bounces to Accessibility Settings on
  launch (DualScreenDex onboarding). Enable the accessibility service once, back
  out, tap the button. Step 4 makes Habitat a proper entry point.
- Use it during a real BDSP or LP session on the lower screen for ~30 min. Note
  what's annoying — that list should reorder everything below.

---

## 1. Decisions only you can make

- **App identity.** Still "DualScreenDex" in
  `app/src/main/res/values/strings.xml` (`app_name`); launcher icon is the
  original author's. Rename? (`applicationId` is already `com.enrpau.pokegeards`.)
- **Which detection tier first** (design.md §2.2). OCR battle detection already
  works. Location-banner OCR (step 3) extends it but is fiddly. The **Eden
  emulator bridge** (Tier 3) would be exact and skip most of the OCR pain — does
  Eden expose any IPC / socket / memory-read hook? If yes, prioritise that.
- **Sprites.** Current set = PokéAPI fan-rips (fine personally, not for
  distributing the APK). Swap for PokéSprite or your own if you ever share it.
- **Room?** Data layer is raw SQLite and works. Room adds compile-checked queries
  but needs a KSP/KAPT Gradle plugin. Only worth it if the hand-written SQL
  starts hurting.

---

## 2. Quick wins (~30–60 min each)

- **Location spinner truncates** ("Rou.."). `activity_habitat.xml` — widen the
  Spinner or move the AREA row above the filters.
- **Detail card has no type matchups.** design.md §2.4 wants defensive
  weaknesses/resistances. DualScreenDex already has `TypeMatchup.kt` +
  `MainViewModel.calculateMatchups()` — lift that into `EncounterCardDialog`.
- **Grid shows some species twice** (two slot entries, different level ranges).
  Merge in `PokegearDb.getEncounters` or leave it — decide.
- **Rename the app** if you decided to in step 1.

---

## 3. Milestone 5 — location-banner OCR

Goal: walking into "Route 210" auto-selects it in Habitat; manual picker stays
the fallback.

- Add a second OCR pass in `DualDexAccessibilityService` on a configurable crop
  (where BDSP's area banner appears, ~1–2 s window).
- Per-pack location gazetteer — `PokegearDb.getLocations()` gives the names;
  fuzzy-match OCR text with the same Levenshtein the species matcher uses.
- Debounce: change location only after N consistent reads, never mid-battle.
- Feed into `GameStateRepository` — flesh out the `OcrStateProvider` stub in
  `detection/`, or bridge the existing `POKEMON_DETECTED` broadcast.
- Testing without a game: screenshot real BDSP banners → run through ML Kit
  offline, or fake state via `adb`.

---

## 4. Milestone 6 — battle ↔ habitat mode switch

- Make `HabitatActivity` a real entry point: add a `LAUNCHER` intent-filter (or
  make it MainActivity's default view) and drop the forced Accessibility-Settings
  redirect for Habitat-only use.
- On `GamePhase.BATTLE` from `GameStateRepository`, auto-show the battle view
  (reuse DualScreenDex's battle UI + `calculateMatchups`); on `OVERWORLD`, show
  Habitat.
- Wire `POKEMON_DETECTED` → `GameStateRepository.manual.setBattleSpecies(...)` so
  both features read one shared state.

---

## 5. Data completeness

- **LP:** add Grand Underground, Honey Tree, static/legendary/gift, roaming — LP
  documents these separately from route data; check `TeamLumi/luminescent-team`
  for other gamedata JSON.
- **BDSP:** replace the 15 approximate Grand Underground rows with real biome
  tables (Serebii/Bulbapedia BDSP Grand Underground).
- **Both:** `species_variant` table in `schema.sql` exists but is unused —
  regional forms are folded into base ids. Split them if you want per-form
  encounters.

---

## 6. Real-device polish (after step 0 feedback)

- Tune the layout for the Thor lower screen's real dimensions / aspect.
- OLED + power-saving options (PRD §3C, design.md §2.6).
- Bigger touch targets if the grid feels cramped in hand.

---

## Suggested order

1 → 0 → 2 (whatever step 0 makes obvious) → 3 **or** Tier 3 bridge → 4 → 5 → 6.

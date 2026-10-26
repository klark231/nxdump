# NxDump

Android 10+ tool to extract **ExeFS** and **RomFS** from your own XCI / NSP / NCA dumps,
with **updates merged on top** (BKTR patching). Material 3, dynamic colour, dark mode.
No emulation — extraction only.

## Using it
1. **Setup wizard**: pick `prod.keys` (required; `title.keys` optional), your game folder, and an output folder.
2. **Library**: games are listed with icon, version and detected update. Updates found in the folder attach automatically;
   use **Add file** to add any NSP/XCI (e.g. a loose update) manually.
3. Tap a game → choose ExeFS / RomFS, toggle **Merge update**, **Extract**. Runs as a foreground service with a notification.

Output: `<Output folder>/<Game> [TITLEID] (+ update)/exefs/…` and `/romfs/…`

## Build
* Android Studio (Ladybug+): open this folder → Run. JDK 17.
* CLI: `gradle assembleRelease` (Gradle 8.10). The release APK is signed with the debug key so it installs directly.
* No PC? Push the folder to GitHub — `.github/workflows/build.yml` builds the APK as a downloadable artifact.

## Layout
* `core/src/com/nxdump/core` – pure-Java format/crypto core (PFS0/HFS0, NCA, AES-XTS/CTR/CTR-Ex, BKTR, RomFS, CNMT/NACP, tickets).
* `app/src/main/java/com/nxdump` – Kotlin + Jetpack Compose UI, SAF access, library cache, foreground extraction service.
* `core/test/run_tests.sh` – builds synthetic encrypted NSP/XCI + update on a desktop JVM and verifies byte-exact output.

## Notes / limits
* Keys: `prod.keys` needs `header_key`, and the master key generation the game uses (key-area/titlekek keys are derived if absent).
* Tickets embedded in an NSP are used automatically (common titlekey type). Personalised tickets are not supported.
* Not supported: NSZ/XCZ (compressed), NCA0, sparse/compressed NCAs, DLC extraction.
* Android 11+ won't let you pick the storage root or Download as the output folder – choose a subfolder.

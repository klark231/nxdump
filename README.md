# NxDump

Android 10+ tool to extract **ExeFS**, **RomFS** and **DLC** from your own XCI / NSP / NCA dumps,
with **updates merged on top** (BKTR patching). Material 3, dynamic colour, dark mode.
No emulation — extraction only.

## Using it
1. **Setup wizard**: pick `prod.keys` (required; `title.keys` optional), your game folder, and an output folder.
2. **Library**: games are listed with icon, version and detected update. Updates found in the folder attach automatically;
   use **Add file** to add any NSP/XCI (e.g. a loose update) manually.
3. Tap a game → choose ExeFS / RomFS, toggle **Merge update**, optionally enable **DLC** and tick the packs you want, then **Extract**. Runs as a foreground service with a notification.

Output: `<Output folder>/<Game> [TITLEID] (+ update) (+ DLC)/exefs/…`, `/romfs/…` and `/dlc/<DLC TITLEID>/romfs/…`
(a DLC with several data NCAs gets `romfs`, `romfs_2`, …). DLC-only extraction works too – just switch ExeFS and RomFS off.

## Build
* Android Studio (Ladybug+): open this folder → Run. JDK 17.
* CLI: `gradle assembleRelease` (Gradle 8.10). The release APK is signed with the debug key so it installs directly.
* No PC? Push the folder to GitHub — `.github/workflows/build.yml` builds the APK as a downloadable artifact.

## Layout
* `core/src/com/nxdump/core` – pure-Java format/crypto core (PFS0/HFS0, NCA, AES-XTS/CTR/CTR-Ex, BKTR, RomFS, CNMT/NACP, tickets, DLC data NCAs).
* `app/src/main/java/com/nxdump` – Kotlin + Jetpack Compose UI, SAF access, library cache, foreground extraction service.
* `core/test/run_tests.sh` – builds synthetic encrypted NSP/XCI + update on a desktop JVM and verifies byte-exact output.

## Speed
**Settings → Performance → Parallel jobs** copies several files at once (default: up to 4, max = CPU cores). With more than one job,
files over 4 MiB also use a read-ahead pipeline (one thread decrypts while another writes). 1 job = the old sequential behaviour.
Output is byte-identical either way; `core/test/run_tests.sh` checks 1 and 4 jobs, plus error/cancel handling.

## Notes / limits
* Keys: `prod.keys` needs `header_key`, and the master key generation the game uses (key-area/titlekek keys are derived if absent).
* Tickets embedded in an NSP are used automatically (common titlekey type). Personalised tickets are not supported.
* Not supported: NSZ/XCZ (compressed), NCA0, sparse/compressed NCAs.
* DLC: listed under the base game it belongs to; if several versions of the same DLC are present the newest is used. DLC files without the base game in your library are not shown.
* Android 11+ won't let you pick the storage root or Download as the output folder – choose a subfolder.

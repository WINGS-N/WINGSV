# AGENTS.md

Working guide for AI agents in the WINGS V repository. Read this first. The `README.md`
(Russian) is the user-facing overview; this file is the build/lint/commit/UI guide. When a
rule here is marked HARD RULE or MANDATORY, follow it exactly - the user has corrected these
before.

## 1. What this is

WINGS V is a Java Android VPN client: Xray/VLESS, VK TURN over WireGuard/AmneziaWG, and plain
WireGuard/AmneziaWG, with a Samsung One UI (SESL 8) interface.

- `applicationId` / `namespace` = `wings.v`; `compileSdk` 37, `minSdk` 26, `targetSdk` 36;
  Java 17 (`app/build.gradle.kts`).
- The `:app` module is 100% Java (246 `.java`, 0 `.kt`). Do NOT introduce Kotlin into `:app`.
- Entry point: `wings.v.WingsApplication`.

## 2. Repo / module layout

Gradle modules (`settings.gradle.kts`):

- `:app` - the application, all Java.
- `:vpnhotspot:bridge`, `:vpnhotspot:sharing-bridge`, `:vpnhotspot:sharing-runtime`,
  `:vpnhotspot:upstream-runtime` - root-tethering / VPN-sharing support under the top-level
  `vpnhotspot/` directory. These are mixed JVM (Kotlin and Java), not all Kotlin: `bridge` is
  Kotlin, `upstream-runtime` is Kotlin with a native Rust daemon built via cargo-ndk,
  `sharing-runtime` is mixed, `sharing-bridge` is pure Java.
- `:amneziawg-tunnel` - mapped to `external/amneziawg-android/tunnel`.

App subpackages under `app/src/main/java/wings/v/`: core, ui, service, guardian, root, xray,
vk, qs, byedpi, wbstream, widget, worker, xposed, receiver, vpnhotspot.

Multi-process: the tunnel runtime and Guardian run in `android:process=":tunnel"`; there are
also `:xray_test*` probe processes. Cross-process state must be process-safe because the main
and `:tunnel` processes both read prefs. Prefs are MMKV-backed (multi-process safe) via
`wings.v.core.MmkvPrefs` / `AppPrefs` / `MmkvSharedPreferences` / `MmkvPreferenceDataStore` -
NOT raw `SharedPreferences`. The one deliberate exception is `wings.v.core.XposedModulePrefs`
(prefs file `xposed_module_preferences`), which is world-readable so hooked apps can read it.

## 3. UI: SESL 8 + oneui-design (STRICT)

The UI is built on Samsung SESL 8 forks of androidx/material plus the
`io.github.tribalfs:oneui-design` library. Maven coordinates resolve from three private GitHub
Packages repos declared in `settings.gradle.kts` (tribalfs/sesl-androidx,
sesl-material-components-android, oneui-design), gated by the Gradle properties `seslUser` /
`seslToken`.

HARD RULE: never add a stock `androidx.appcompat`, `androidx.fragment`, `androidx.preference`,
`androidx.recyclerview`, `androidx.core`, `com.google.android.material`, etc. dependency. The
`configurations.configureEach { exclude(...) }` block in `app/build.gradle.kts` (around line
537) strips every stock androidx/material artifact off the classpath so only the SESL forks
remain. The forks keep the same package names, so `androidx.appcompat.app.AppCompatActivity`
already IS the SESL one. Adding a stock dependency either breaks the build or silently
duplicates classes. New SESL artifacts go through `gradle/libs.versions.toml` (the `sesl-*`
version keys; `oneui-design = io.github.tribalfs:oneui-design`).

Build screens with oneui-design widgets, matching existing screens line-for-line. Canonical
pieces used here:

- `dev.oneuiproject.oneui.layout.ToolbarLayout` - the screen scaffold.
- `widget.CardItemView`, `widget.SwitchItemView`, `widget.RoundedLinearLayout`,
  `widget.Separator`, `widget.TipsCard`.
- `widget.BottomTabLayout` - multi-select action bars.
- `layout.Badge`, `qr.app.QrScanActivity`, `preference.UpdatableWidgetPreference`.

Settings screens are ToolbarLayout Activities (e.g. `XraySettingsActivity`). For "make it like
X" UI work, copy X's layout + strings + wiring exactly. The user has rejected ad-hoc
plain-button bars that do not match the Xray reference.

## 4. Build, lint, run

Prereqs:

- Clone with submodules: `git submodule update --init --recursive`.
- A full assemble needs Android SDK + NDK, protoc, Go (gomobile, libXray, Xray, vk-turn), and
  Rust + cargo-ndk (the upstream-runtime daemon). It runs Go/proto/libXray codegen via
  preBuild; lint-only tasks skip that.
- SESL access needs `seslUser` / `seslToken` set OUTSIDE the repo (gradle.properties in `$HOME`
  or `-PseslUser=... -PseslToken=...`).

Commands:

- Debug APK: `./gradlew :app:assembleDebug`. Release: `./gradlew :app:assembleRelease`.
- Install to a device: `./gradlew :app:installDebug` ONLY. Never use `adb install` - the
  Gradle install pins `--user 0`, which the root / sharing logic assumes.
- Unit tests: `./gradlew :app:testDebugUnitTest` (JUnit4 + Robolectric + Mockito).

Robolectric gotcha: a Robolectric test must be annotated
`@Config(sdk = 34, application = android.app.Application.class)` (see
`app/src/test/java/wings/v/core/TetherTypeTest.java`) or `WingsApplication.onCreate` loads the
MMKV native lib and the test fails with `UnsatisfiedLinkError`. Pure non-Android logic stays on
plain JUnit; MMKV-backed `AppPrefs` is not host-JVM testable.

## 5. Pre-commit gates (MANDATORY - do not skip, not even for one-liners)

1. Format Java: `pnpm format:java` (writes in place). There is NO `pnpm format` script. Verify
   with `pnpm format:java:check`.
2. Lint: `pnpm lint:java` (= `:app:checkstyleJava :app:pmdJava`, both fail on violation).
3. Compile sanity for Java changes: `./gradlew :app:compileDebugJavaWithJavac`.

CI (`.github/workflows/ci.yml`, runs on push to `main` / `dev`, on every PR, and on dispatch)
gates exactly, in order: `pnpm format:java:check`, then `pnpm lint:java`, then
`:app:assembleDebug`. Skipping the formatter is the single most common way agents have turned
CI red. CI does NOT run `lintDebug` or unit tests - run those locally when relevant. A
`[SKIP CI]` marker in the commit / PR subject skips the CI job.

## 6. Commit conventions

Format: `[scope] short lowercase imperative` - a single subject line, NO colon after the scope,
NO body, NO `Co-Authored-By` or AI-mention trailer. (Older history has a colon variant like
`[external/...]: bump`; write new commits without the colon.)

Pick the NARROWEST accurate scope. Real examples from this repo's log:

- `[app/service] stop an empty whitelist from tunneling every app`
- `[app/core] route apps by explicit whitelist/bypass only, not the hide-VPN list`
- `[app/ui] clear leftover root routing immediately when root mode is turned off`
- `[app/xray] drop gVisor UID filter for plain whitelist, matching plain bypass`
- `[app/guardian] push config snapshot on connect in foreground and periodic sync`
- `[app/vk] add browser fingerprint selector for VK TURN relay impersonation`

Other valid scopes: `[vpnhotspot]` (the vpnhotspot modules), `[docs]` (README / docs),
`[build]`, `[ci]`, `[ignore]` (.gitignore only), `[app/test]` (unit tests - use this even when
the commit also makes a tiny test-enabling tweak to main code, e.g. dropping `private` to make a
method package-visible). Use bare `[app]` only when a change truly spans many subsystems. Split
one task across scopes into separate commits rather than one bundled `[app]`.

Submodule pointer bump in the parent repo: the subject is literally `[external/<path>] bump`
and nothing more (e.g. `[external/vk-turn-proxy] bump`). The real description lives in the
submodule's own commit. Inside a WINGS-N submodule, still use this `[scope]` style, NOT upstream
Conventional Commits (`feat:` / `fix:`), even if the upstream history uses them.

Do NOT `git push` without explicit user confirmation - lint and CI fixes included. Commit, then
stop and report; wait for the user to say "push".

## 7. Code-text style (comments, logs, UI strings in source)

- ASCII only in anything written into the source tree: use `-` not an em/en-dash, straight
  `"` `'` not curly quotes, `...` not the ellipsis character, words not arrow glyphs.
  (Established on-screen UI strings like a "Next ->" label may keep an existing arrow.)
- No markdown in code comments: no backticks, no `**bold**`, no `<placeholder>` angle brackets,
  no `[text](url)` links. Plain prose; name a symbol by writing its name. Javadoc `{@link ...}`
  tags are fine. README and docs are markdown and stay markdown (this file included).
- Comments only for genuinely non-obvious WHY (kernel quirks, compat shims, protocol gotchas).
  Do not restate what the code does; default to no comment.

## 8. external/ submodules

All are WINGS-N forks (the single source of truth) except `external/zstd-jni`
(upstream luben/zstd-jni):

- `external/Xray-core` - WINGS-N fork of XTLS/Xray-core, carrying WINGS patches (WG peer
  traffic stats, gVisor TUN per-app UID filter). Bump only from the WINGS-N fork; never add an
  XTLS remote.
- `external/libXray` - Go-to-AAR bridge (gomobile) that `:app` builds into libXray.aar.
- `external/vk-turn-proxy` - native VK TURN client (Go, built to libvkturn.so by the app's
  Gradle codegen). WRAP SRTP-mimicry obfuscation with in-band key delivery, on by default.
- `external/amneziawg-android` - AmneziaWG; its `tunnel/` is the `:amneziawg-tunnel` module.
- `external/byedpi` - ByeDPI DPI-bypass.
- `external/VPNHotspot` - Mygod VPNHotspot fork for root tethering (the in-repo
  `:vpnhotspot:*` modules are the bridge to it).
- `external/librustoreparser` - RuStore recommended-apps parsing for codegen assets.
- `external/zstd-jni` - upstream luben/zstd-jni (the one non-WINGS-N submodule).

Bump rule: commit inside the submodule first (zstd-jni uses upstream's style; WINGS-N forks use
this `[scope]` style), then `[external/<path>] bump` the pointer in the parent repo. Do not push
either without confirmation.

## 9. Pointers

The server side (panel) is a separate repo, WINGS-N/3x-ui, with the vk-turn-proxy inbound built
in; the landing / panel UI lives in v.wingsnet.org (pnpm). Both are out of scope for this repo's
agents.

# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

BiliSeek is a search-driven, audio-first, no-recommendation third-party Bilibili Android client. Default behavior is "listen only" (audio track); a single button switches to video **seamlessly** (no source rebuild, no seek, no position loss). See `README.md` for the product thesis.

## Critical safety rules

- **Never let automated code (tests, agents) hit Bilibili servers** — search, sync, playurl, login, dynamic feeds, etc. This risks flagging the device/account with risk-control (`-352`). All automated tests must use local/mock data. Real-server verification is done by a human. See `AGENTS.md`.
- **Risk-control error codes are not interchangeable** (`core/network` interceptor chain):
  - `-101` (not logged in) → refresh Cookie, **retry once**, then log out.
  - `-352` (risk-control block) → **never retry**; fail and back off exponentially. Retrying here escalates a temporary limit into a long-term ban.
  - `-412` (missing fingerprint) → don't retry; re-trigger the credential gate.

## Build & test

```bash
./gradlew installDebug                 # build + install debug to connected device
./gradlew test                         # all JVM unit tests (offline, safe)
./gradlew :core:network:test           # one module's unit tests
./gradlew :core:network:test --tests "com.biliseek.core.network.WbiSignerTest"   # single test class
./gradlew connectedDebugAndroidTest    # instrumented + Compose UI tests (needs a device/emulator)
./gradlew assembleRelease              # R8 + resource shrink (see release note below)
./gradlew archiveReleaseArtifacts      # archive APK + mapping.txt to releases/<versionName>/
```

CI (`.github/workflows/ci.yml`) runs `./gradlew test assembleDebug` on every push; `build-debug-apk.yml` is a manual job that publishes a debug APK to Releases. Shared SDK setup lives in `.github/actions/setup-android` — keep its `platforms;android-XX.Y` in sync with `compileSdk`.

Environment (see `docs/01-开发环境搭建.md` for a clean-machine setup): minSdk 26 / targetSdk 35 / compileSdk 37, AGP 9.3.1, Gradle 9.7.1, Kotlin 2.4.10, JDK 21, Media3 1.11.0. Requires `local.properties` with `sdk.dir`. Point Gradle at the IDE's JBR via `org.gradle.java.home` to avoid dueling daemons.

- **AGP 9 has built-in Kotlin support** — do **not** declare the `org.jetbrains.kotlin.android` plugin; it errors. Modules use `kotlin.compose` / `kotlin.serialization` / `ksp` plugins as needed.
- **Release builds run R8 + resource shrink.** Over-stripping does not fail compilation — it crashes at runtime or silently breaks API parsing. A release build must pass a full regression pass. **No `mapping.txt` = not a valid release** (crash stacks can't be de-obfuscated).
- Room schemas are exported to `app/schemas/` (KSP arg); migration tests read them from there.

## Module architecture

```
:app            UI, navigation, feature ViewModels, Room database, repositories
:core:network   risk-control credentials, WBI signing, Cookie, Bilibili API + DTOs
:core:player    playurl resolution, DASH/MPD assembly, ExoPlayer, PlaybackService, playback state machine
:design-system  theme, design tokens, cover-color extraction (Monet)
```

Two cross-cutting invariants that shape everything:

1. **The player lives in a Service, not the UI.** "Listen only" means the user is usually in another app with the screen off, so the player's lifecycle must not bind to any screen. The UI reaches it through `MediaController` via the `PlayerConnection` facade. `AppViewModel` is the central UI-side state holder.
2. **Playback state is derived from presentation state.** Two orthogonal axes — `PlaybackMode` (listen/watch) × `PresentationMode` (where the surface is) — and the invariant "video track enabled ⟺ a visible surface exists" is enforced structurally so it can't be violated. This is what makes listen↔watch switching seamless. Types live in `core/player/PlaybackModels.kt`.

### Conventions to follow

- **Decision logic goes in pure functions named `*Policy.kt`** (`shouldXxx()` / `resolveXxx()`), extracted from stateful components, side-effect-free and unit-tested. This is the primary defense against God-object growth. Examples: `QueuePolicy`, `ResumePolicy`, `QualityPolicy`, `TrackSelectionPolicy` in `core/player/internal/`; `DownloadPolicy`, `VideoGatePolicy`, `ReorderPolicy` in `app/data/`.
- **Public API surface is deliberate.** Ported code keeps Kotlin `internal` (module-scoped). Only types `:app` actually touches are `public`. Adding a public type counts as an API change — don't do it casually. Public in `:core:player`: `PlaybackService`, `PlayerConnection`, `PlaybackRequest`, `PlaybackState`, `PlaybackMode`, `PresentationMode`, `AudioQuality`, `VideoQualityOption` (+ `VIDEO_QUALITY_AUTO`). Public in `:core:network`: `AuthGate`, `BiliApi`, DTOs, `WbiSigner`.
- **Don't persist remote data** (favorites, collections, search results) beyond in-memory cache. Only write to Room when the user explicitly saves something into a local list.
- **The auth gate never blocks the UI.** Business requests wait on a `StateFlow<AuthState>` (`AuthGate`) until credentials are ready; the search box stays interactive. Gate failures retry silently with backoff and don't surface errors unless a user-initiated request ultimately fails.
- **Tapping play never wipes what the user queued.** The queue is two-tier (`docs/10-播放队列工单.md`): items added via 加入队列 / 下一首播放 are *pinned*; items a tap brings in (one video, its parts, or a whole local playlist / collection / watch-later list) are *temporary* and are replaced as one block by the next tap. The rules are pure functions in `QueuePolicy.kt`; `QueueEntry` stays `internal` — the only public additions are `PlaybackState.queuePinned` and `PlayerConnection.playFromContext / removeFromQueue / undoQueueChange`.
- **In-app transient messages use `AppToast` / `AppToastSnackbar`** (`app/ui/components/AppToast.kt`): white pill + app icon, single line, positioned via `AppToastDefaults` to match the system Toast. Don't use M3's default dark `Snackbar` or hand-roll message bars; plain messages without an action can stay system `Toast`s. Video-overlay HUDs (fullscreen gestures/errors) keep their dark style.
- **WBI signing happens at the call site, not in an interceptor** — `WbiSigner.sign(params)` returns a `Map` passed via `@QueryMap`, so the signature covers the final parameter set.

## The `ref/` directory is not part of this project

`ref/` holds external reference projects (BiliPai in Kotlin, BBPlayer in React Native with a large `node_modules/`, plus local JDK toolchains and an AVD) kept only for UI/UX and read-through reference. It is **not** in `settings.gradle.kts` and not built by `./gradlew`. **Exclude `ref/` from code searches** — it dominates `find`/`grep` results. It's disposable (`rm -rf ref/`).

## Documentation map

Deep docs live in `docs/` (Chinese): architecture (`架构设计篇`), feature selection/rationale (`功能选型篇`), milestone planning + outstanding-work ledger (`功能优化篇 §10`), UI tokens / shared components / button rules (`09-UI规范与重构`), play-queue rules and implementation (`10-播放队列工单`), manual test guide (`手动测试指南`, includes known gaps), and acceptance records under `docs/验收记录/`.

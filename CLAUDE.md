# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

BiliSeek is a search-driven, audio-first, no-recommendation third-party Bilibili Android client. Default behavior is "listen only" (audio track); a single button switches to video **seamlessly** (no source rebuild, no seek, no position loss). See `README.md` for the product thesis.

## Critical safety rules

- **Never let automated code (tests, agents) hit Bilibili servers** — search, sync, playurl, login, dynamic feeds, etc. This risks flagging the device/account with risk-control (`-352`). All automated tests must use local/mock data. Real-server verification is done by a human. See `AGENTS.md`.
- **Risk-control error codes are not interchangeable.** They are *not* handled in OkHttp interceptors (those only inject headers/cookies); each call site in `BiliRepository` maps the code through pure functions — `resolveRetryAction` (`internal/RetryPolicy.kt`) for reads, `resolveWriteOutcome` (`internal/WritePolicy.kt`, driven by `internal/WriteRequest.kt`) for writes:
  - `-352` (risk-control block) → **never retry**; `RiskCooldown` enters a global cooldown (60 s, doubling per consecutive hit, max 30 min) during which requests fail fast. Retrying here escalates a temporary limit into a long-term ban.
  - `-412` (missing fingerprint) → don't retry; `authGate.invalidate()` re-seeds credentials.
  - `-111` (csrf invalid, writes only) → refresh Cookie via `CookieRefresher`, retry **exactly once** (the same single retry `-101` uses).
  - `-101` (session expired) → refresh via `CookieRefresher`, retry **exactly once**. Reads wrap the call in `refreshingOn101 { … }` (→ `runWithLoginRefresh`, `internal/LoginRefresh.kt`) *outside* the `RequestThrottle` gate; writes share the `-111` retry in `runWriteRequest`. Log out (`LoginManager.expire()`) **only** when passport itself also answers `-101` (`RefreshOutcome.Expired`); a passport `-352` / HTTP 412 enters the cooldown. `CookieRefresher` is single-flight and asks passport at most once per session per 10 min (`internal/SessionRefreshPolicy.kt`). Deliberately not wired: `danmakuSegment`, best-effort `messageUnread` / `fillSessionPeers`.
  - Search, WBI-signed page reads (`wbiRequest`: UP archives, user search, dynamic detail), comments, charts, message, danmaku, summary and write calls go through `RequestThrottle` buckets (per-bucket min interval + jitter, constants in `BiliRepository`). Account-page reads via `accountRead` (dynamic feed, favorites, collections, follows, watch-later) are deliberately **not** throttled — they only pass the cooldown check and `refreshingOn101`. Use the matching bucket (or `accountRead`) for new calls and never add retry loops around Bilibili requests.

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

CI (`.github/workflows/`): `ci.yml` runs `./gradlew test assembleDebug` on every push; `build-debug-apk.yml` (manual) publishes a debug APK to Releases; `build-release.yml` (manual) runs tests → signed R8 release → archives APK + `mapping.txt` → creates a **draft** Release (needs the four `BILISEEK_*` signing secrets; see `docs/发版清单.md`). Shared SDK setup lives in `.github/actions/setup-android` — when bumping `compileSdk`, update its `platforms;android-XX.Y` **and** `build-tools;XX.Y.Z` (the latter is also hard-coded in `build-release.yml`).

Environment (see `docs/01-开发环境搭建.md` for a clean-machine setup): minSdk 26 / targetSdk 35 / compileSdk 37, AGP 9.3.1, Gradle 9.7.1, Kotlin 2.4.10, JDK 21 toolchain (CI uses JDK 21; a newer IDE JBR works locally), Media3 1.11.0. No NDK needed — `:core:asr` ships prebuilt sherpa-onnx `.so` files; release builds keep only `arm64-v8a`. Requires `local.properties` with `sdk.dir`. Point Gradle at the IDE's JBR via `org.gradle.java.home` to avoid dueling daemons.

- **AGP 9 has built-in Kotlin support** — do **not** declare the `org.jetbrains.kotlin.android` plugin; it errors. Modules use `kotlin.compose` / `kotlin.serialization` / `ksp` plugins as needed.
- **Release builds run R8 + resource shrink.** Over-stripping does not fail compilation — it crashes at runtime or silently breaks API parsing. A release build must pass a full regression pass. **No `mapping.txt` = not a valid release** (crash stacks can't be de-obfuscated).
- Room schemas are exported to `app/schemas/` (KSP arg) and must be committed with every DB version bump; `MigrationTest` (instrumented) reads them.

## Module architecture

```
:app            UI, navigation, AppViewModel + feature controllers, Room database, repositories, download & AI-summary foreground services
:core:network   risk-control credentials, WBI signing, Cookie, Bilibili API + DTOs
:core:player    playurl resolution, DASH/MPD assembly, ExoPlayer, PlaybackService, playback state machine
:core:ai        AI summary: provider presets (OpenAI-compatible), chunked map-reduce Summarizer → Markdown; knows nothing about Bilibili
:core:asr       offline speech recognition (sherpa-onnx + SenseVoice + Silero VAD), model download/import/verify
:design-system  theme, design tokens (BsSpacing/BsSize/BsShapes), shared Bs* components, accent-color policy (cover-color extraction itself is app/ui/CoverAccent.kt)
```

Two cross-cutting invariants that shape everything:

1. **The player lives in a Service, not the UI.** "Listen only" means the user is usually in another app with the screen off, so the player's lifecycle must not bind to any screen. The UI reaches it through `MediaController` via the `PlayerConnection` facade. `AppViewModel` is the single UI-side state holder; it composes plain feature controllers (`SearchController`, `DynamicFeedController`, `RemoteLibraryController`, `UpSpaceController`, `MessageCenterController`, `CommentThreadController`, `VideoInteractionController`, `SummaryController`, …) — add new feature state as a controller, not by growing `AppViewModel`. DI is hand-wired (no Hilt).
2. **Playback state is derived from presentation state.** Two orthogonal axes — `PlaybackMode` (listen/watch) × `PresentationMode` (where the surface is) — and the invariant "video track enabled ⟺ a visible surface exists" is enforced structurally so it can't be violated. This is what makes listen↔watch switching seamless. Types live in `core/player/PlaybackModels.kt`.

### Conventions to follow

- **Decision logic goes in pure functions named `*Policy.kt`** (`shouldXxx()` / `resolveXxx()`), extracted from stateful components, side-effect-free and unit-tested. This is the primary defense against God-object growth. Examples: `QueuePolicy`, `ResumePolicy`, `QualityPolicy`, `TrackSelectionPolicy` in `core/player/internal/`; `DownloadPolicy`, `VideoGatePolicy`, `ReorderPolicy` in `app/data/`; `ChunkPolicy`, `ReasoningStripPolicy` in `core/ai/internal/`; `LocalAsrPolicy`, `FailureActionPolicy` (inside `SummaryPolicies.kt`) in `app/summary/`; `CooldownPolicy`, `WritePolicy`, `RetryPolicy` in `core/network/internal/`. There are ~75 of these — look for an existing one before adding logic to a controller.
- **Public API surface is deliberate.** Ported code keeps Kotlin `internal` (module-scoped). Only types `:app` actually touches are `public`. Adding a public type counts as an API change — don't do it casually. The current surface is whatever is non-`internal` in the code; the main entry points are:
  - `:core:player`: `PlaybackService`, `PlayerConnection`, and the types in `PlaybackModels.kt` (`PlaybackRequest`, `PlaybackState`, `PlaybackMode`, `PresentationMode`, `AudioQuality`, `VideoQualityOption` + `VIDEO_QUALITY_AUTO`, `PlayMode`, `PlaybackPreferences`, `PersistedSession`, `OfflineMedia`, …).
  - `:core:network`: `BiliNetwork`, `AuthGate`/`AuthState`, `BiliApi`, `BiliRepository`, `LoginManager`, `SessionStore`, `CookieRefresher`, `WbiSigner`, `SearchError`/`WriteError`, DTOs. (A few types under `internal/` — `BiliCredentials`, `RetryAction`, `RetryBudget` — lack the `internal` modifier by mistake; don't build on them.)
  - `:core:ai`: `Summarizer`, `SummaryRequest`/`SummaryResult`/`SummaryProgress`/`SummaryStyle`, `Transcript`/`TranscriptSegment`/`TranscriptSource`, `Chapter`, `AiError`, provider config types incl. `ProviderCatalog`, `AiConnectionTester`, `SummaryMarkdown`. `ProviderFactory` / `ChatClient` stay `internal`.
  - `:core:asr`: `OfflineTranscriber`, `AsrModelManager`, `AsrModelCatalog`, `AsrSegment`/`AsrProgress`/`AsrError`, `AsrModelSpec`/`ModelFile`/`ModelSource`/`ModelState`.
- **Don't persist remote data** (favorite contents, collections, feeds, search results) beyond in-memory cache. Only write to Room when the user explicitly saves something into a local list. Deliberate exceptions: the favorite-folder *directory* snapshot (`favorite_folder`), `up_visit`, and the AI-summary index (`ai_summary`).
- **The auth gate never blocks the UI.** Business requests wait on a `StateFlow<AuthState>` (`AuthGate`) until credentials are ready; the search box stays interactive. Gate failures retry silently with backoff and don't surface errors unless a user-initiated request ultimately fails.
- **AI HTTP never carries Bilibili cookies or interceptors.** `:core:ai` and `:core:asr` model downloads use their own `OkHttpClient`; tests use MockWebServer only — never real AI services or model hosts.
- **Tapping play never wipes what the user queued.** The queue is two-tier ([`docs/10-播放队列工单.md`](docs/10-播放队列工单.md)): items added via 加入队列 / 下一首播放 are *pinned*; items a tap brings in (one video, its parts, or a whole local playlist / collection / watch-later list) are *temporary* and are replaced as one block by the next tap. The rules are pure functions in `QueuePolicy.kt`; `QueueEntry` stays a `private` class inside `PlayerConnection` — the only public additions are `PlaybackState.queuePinned` and `PlayerConnection.playFromContext / removeFromQueue / undoQueueChange`.
- **In-app transient messages use `AppToast` / `AppToastSnackbar`** (`app/ui/components/AppToast.kt`): white pill + app icon, single line. The main host (in `AppShell`) sits `AppToastDefaults.GapAboveBottomBar` above the measured bottom bar, so it never covers the minibar. Plain messages (no action) go through `AppViewModel.transientMessage` (or `LocalShowMessage` in composables); `MainActivity` routes them with `resolveToastTarget` — in-app while the app is in the foreground, system `Toast` only in background / PiP or when another sheet/dialog window covers the main window (a system Toast can't be repositioned and would cover the minibar). Don't call `Toast.makeText` directly (the one exception: 「已保存到相册」 in the full-screen image preview, which is drawn over the in-app host), don't use M3's default dark `Snackbar`, don't hand-roll message bars. Video-overlay HUDs (fullscreen gestures/errors) keep their dark style.
- **Pages extend behind the minibar.** `AppShell` overlays the minibar on the bottom of the page area (the Scaffold bottom bar holds only the `NavigationBar`), so its rounded corners / floating margins show the page underneath. Its current height is `LocalMiniBarInset` (`app/ui/components/MiniBarInset.kt`): every scrollable screen inside the NavHost must use `miniBarContentPadding()` for its bottom content padding, and anything pinned to the page bottom (input bars, in-page toasts) must be lifted by `LocalMiniBarInset.current` — otherwise the last item hides behind the minibar.
- **WBI signing happens at the call site, not in an interceptor** — `WbiSigner.sign(params, imgKey, subKey)` (keys from `authGate.awaitReady()`) returns a `Map` passed via `@QueryMap`, so the signature covers the final parameter set.

## Reference projects (`ref/`)

Some checkouts have a local, git-ignored `ref/` directory with external reference projects (BiliPai, BBPlayer with a large `node_modules/`, JDK toolchains, an AVD). It is not in the repo, not in `settings.gradle.kts`, and not built. If it exists, **exclude it from code searches**.

## Documentation map

Docs live in `docs/` (Chinese). They describe the code but can lag it — **when a doc and the code disagree, the code wins; fix the doc.**

- Living docs: architecture as-built (`03-架构设计篇`), dev environment (`01-开发环境搭建`), outstanding-work ledger + per-change log (`05-功能优化篇` §10 / §13), UI tokens / shared components / button rules (`09-UI规范与重构`), play-queue rules (`10-播放队列工单`), manual test guide (`BiliSeek-手动测试指南`, 附录 3 = known gaps), release checklist (`发版清单`).
- Records: acceptance records in `docs/验收记录/`; small feature design specs in `docs/superpowers/specs/`.
- `docs/archive/`: frozen historical docs — feature selection (`02`), MVP checklist (`04`), completed work orders (`06` writes, `07` risk-control, `08` UI, `10-AI总结`), MVP wrap-up, reference-project study notes. Useful for *why*, not for *what the code does now*.

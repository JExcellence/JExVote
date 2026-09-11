# JExVote - Public-Plugin Improvement & API Evolution Plan

**Status:** design locked 2026-09-11. JExVote is a **live public plugin** (BuiltByBit/Spigot, other servers
install it), currently v3.2.10, Paper 1.21+/Java 21, Folia-supported. This plan is the API-hardening +
architecture + tech-debt pass, decided with the owner. **Ships bundled with Season 4** (decision R3), so the
vote -> S4-progression hooks land together.

Grounded in the 2026-09-11 codebase map. **JExVote is already feature-rich** - offline-vote queue + consolidated
join summary, vote-party, Duolingo streak-freezes, vote-gifting, vote-points shop, weekend multiplier, offline
reconciliation, Lucky/jackpot, HMAC REST API, Bedrock forms, PlaceholderAPI, 4-language i18n (EN/DE/CS/SK), own
Votifier server (v1 + NuVotifier v2). This pass is **not** new player features - it is **API, architecture,
quality, and web-integration**.

---

## 0. Decision register (2026-09-11)

| # | Decision | Value |
|---|----------|-------|
| V1 | API scope | **Complete reads + controlled write hooks** (getSnapshot, canVoteNow/next-vote, leaderboard rank, list services, + grantVotePoints/forceStreakGrace/triggerVoteParty) |
| V2 | Reward extensibility | **Reward-provider SPI** returning a **neutral, platform-free reward descriptor** |
| V3 | Cross-server | **Proxy-aware vote sync** - network-wide streaks/points/party |
| V4 | Proxy transport | **Shared DB = always-on source of truth** + **optional Redis pub/sub** real-time accelerator (no plugin-messaging) |
| V5 | API stability | **Stable, semver'd public API** - `apiVersion()`, `@ApiStatus`, deprecation policy, published to Maven |
| V6 | Coupling | **Keep the plugin coupled** to the JExSuite platform; add everything on top. **But** keep `jexvote-api` a **zero-dependency contract jar** (pure Bukkit) |
| V7 | Tech-debt | **Fix it all in this pass** - decompose `VoteService`, non-blocking + `Cancellable` event, buffering fix, i18n the hardcoded strings |
| V8 | Edition split | New **heavy capabilities are Premium** (reward-SPI runtime, proxy-sync, network-party). The **api contract jar is free/published**; Free keeps today's basic surface |
| V9 | REST API | **Generalize + document as a public feature** (config-driven CORS/secret/endpoints), not premium-locked |
| R3 | Release | **Bundle with S4 launch** (one coordinated release) |
| V10 | Publishing | **Publish `jexvote-api` to a public Maven repo** + README + example integration |

**Edition reconciliation (V8 x V5 x V9):** the `jexvote-api` **contract jar is public and published** (anyone
can compile against it). At **runtime**, advanced calls (write hooks, SPI composition, proxy state) are
**Premium-gated** and degrade gracefully - `apiVersion()` + per-capability flags let a consumer feature-detect
so calling a premium-only method on a Free server returns a documented "unsupported" result, never an NPE. The
REST API (V9) is the one new-ish surface kept **public** (config-driven), because web integration drives installs.

---

## 1. Part A - Public API evolution (`jexvote-api`)

Keep the module **zero-dependency** (Bukkit + the contract only; no jexplatform/jehibernate leak) so the
published jar is tiny and safe to depend on (V6).

### 1.1 `VoteProvider` - complete the read surface (V1)
Add (all async unless a cached in-memory read, which stays sync + documented like `getVotePartyProgress`):
- `CompletableFuture<VoteSnapshot> getSnapshot(UUID)` - the single-call full DTO (`VoteService.getPlayerStats`
  already produces it; just expose it). Removes the 3-future compose third parties do today.
- `CompletableFuture<Boolean> canVoteNow(UUID, String service)` + `CompletableFuture<Instant> nextVoteAt(UUID,
  String service)` - per-service cooldown, the single most-requested missing read.
- `List<String> listServices()` - the configured vote sites (sync, cached).
- Leaderboard **rank**: add `int rank` to a leaderboard-oriented view, or `CompletableFuture<Integer>
  getRank(UUID)` (all-time + monthly variants).
- `CompletableFuture<Integer> getHighestStreak(UUID)` (already in the snapshot; add the direct getter for parity).

### 1.2 `VoteProvider` - controlled write hooks (V1)
Gated Premium at runtime (V8), but declared in the free contract:
- `CompletableFuture<Boolean> grantVotePoints(UUID, int amount, String reason)` - drive points from automation.
- `CompletableFuture<Boolean> forceStreakGrace(UUID)` - apply a streak-freeze programmatically.
- `CompletableFuture<Boolean> triggerVoteParty()` - fire the party (admin/automation).
Each writes through the same services the internal path uses (single source of truth) and is auditable.

### 1.3 Events (V7 - make them first-class)
- **`VoteReceivedEvent` implements Bukkit `Cancellable`** (it currently rolls its own `isCancelled/setCancelled`
  - switch to the interface so generic listener tooling works). Keep the existing getters.
- **Non-blocking fire (V7):** today `fireVoteReceivedEvent` hops to the main thread and **blocks a votifier
  handler thread on a `CompletableFuture`** - a stalled main thread stalls the bounded votifier pool. Rework so
  the async pipeline schedules the sync event and **continues via callback** instead of blocking the handler.
- **New typed events:** `VotePartyCompletedEvent` (contributors + target), `StreakMilestoneEvent` (uuid, streak,
  milestone) - so integrators react without polling.
- **Pre-grant reward hook:** a mutable pre-reward event where listeners can append/scale rewards (complements
  the SPI for lightweight cases).

### 1.4 Reward-provider SPI (V2) - neutral descriptor
- `interface VoteRewardProvider { List<VoteRewardDescriptor> rewardsFor(VoteContext ctx); }` registered via
  `JExVoteAPI` (`registerRewardProvider` / `unregister`).
- **`VoteRewardDescriptor`** is **platform-free**: a sealed/typed value describing `items` (serialized
  ItemStack or material+amount+meta), `commands` (console templates with `%player%`), `currency` (amount +
  currency id), `points` (vote-points). JExVote executes it through its existing `VoteRewardService` /
  `RewardEconomy`. **Third parties depend only on the clean api jar**, never JExPlatform.
- `VoteContext` = uuid, name, service, snapshot, isOnline, isJackpot. Composed with the config rewards
  (SPI rewards are additive, subject to the same offline-queue + sequential-grant safety).
- **Runtime Premium-gated (V8):** on Free, registration is accepted but a warning logs and providers are
  inert (documented), so a plugin author develops against Free and it "lights up" on Premium.

### 1.5 Stability & publishing (V5, V10)
- `String apiVersion()` on `JExVoteAPI` (semver of the api contract) + `boolean supports(String capability)`
  for feature-detection (`"write-hooks"`, `"reward-spi"`, `"proxy-sync"`, `"rest"`).
- `@ApiStatus.Experimental/@Available` annotations; a written **deprecation policy** (mark `@Deprecated` at
  least one minor before removal, never remove within a major).
- Publish `jexvote-api` to a **public Maven repo**; ship a `README` + a ~40-line **example integration** plugin
  in `docs/`.

---

## 2. Part B - Proxy-aware vote sync (V3, V4)

**Model: the DB is the source of truth; Redis is an optional real-time push.**
- **Shared DB (always on):** all backends point at the same JEHibernate DB (they already do for a network). Vote
  processing, streaks, points, party counters are DB-authoritative. On **player join** and on a light **poll**
  (config interval), a backend reconciles its in-memory view - so streaks/points are **network-wide with zero
  new infra**. This alone makes a network correct.
- **Optional Redis pub/sub (opt-in, Premium):** when `proxy.redis.enabled`, backends publish/subscribe vote
  events (`vote.received`, `party.progress`, `broadcast`) so the **vote-party bar and network broadcasts are
  instant** across servers, not poll-delayed. Absent Redis, everything still works via DB (just poll-latency on
  the live bar).
- **No plugin-messaging** (needs a player online per backend, fiddly state) - dropped by decision.
- **Party & multiplier become network-scoped** under proxy mode (a network vote-party, one weekend multiplier)
  - Premium (V8).
- Guard against **double-processing**: a vote is claimed by exactly one backend (DB unique row on
  `service+player+timestamp` / the existing `UnresolvedVoteStore` + reconciliation dedupe).

---

## 3. Part C - Tech-debt (V7, fix-all)

- **Decompose `VoteService` (943 LOC)** into focused collaborators (Sonar monster-class > 20 deps): keep
  `VoteService` as a thin orchestrator; extract `VotePipeline` (process/validate/dedupe), `VoteRewardCoordinator`
  (config + SPI + offline queue), `VoteStatsService` (reads/snapshots/cooldowns), `VoteResetService`
  (monthly/purge/retention). Each < ~250 LOC, cognitive-complexity <= 15 per method.
- **Non-blocking event fire** (see 1.3) - remove the main-thread block from the votifier handler path.
- **Replace the `Thread.sleep(100)` read hack** in `readV2LengthPrefixed` with proper non-blocking buffering /
  `readFully` against the length prefix (a correctness + latency fix in the NuVotifier v2 path).
- **i18n the hardcoded English:** `VoteCommandHandler.onSites` MiniMessage literals ("Open in browser", "Click
  to vote!", "Click to vote") and the `/vote help` entry descriptions -> translation keys (EN/DE/CS/SK), per the
  suite `feedback_always_i18n` rule. Sweep admin `/jexvote` player-facing strings too (logs may stay English).
- Apply the suite Sonar rules throughout (lazy logging, `Boolean.TRUE.equals`, one break/continue, default
  cases, constants at 3+, imports-at-top) - it's a public plugin, keep the gate green.

---

## 4. Part D - REST API generalization (V9)

The embedded HMAC REST server (`rest/`) currently defaults CORS to `https://mythblock.me` and shares
`JEXONE_API_SECRET`. Generalize into an advertised feature:
- **Config-driven:** `rest.cors-origins` (list), `rest.secret` (own, generated; not the shared JEXONE secret),
  `rest.enabled`, `rest.port`, `rest.rate-limit`.
- **Documented endpoints** (leaderboard, player stats, vote-party) with the HMAC signing scheme in `docs/` so
  any server can feed its own website. Keep the RateLimiter + HmacAuthenticator.
- Stays **public** (not premium-locked) because web integration drives installs; the mythblock.me wiring becomes
  just one configured consumer of a general feature.

---

## 5. Build phases (bundled into the S4 track)

1. **P1 - API contract + tech-debt base:** extend `jexvote-api` (reads, write-hook signatures, Cancellable,
   apiVersion/supports, SPI interfaces + neutral descriptor), decompose `VoteService`, fix the event-fire block
   + sleep hack, i18n sweep. *(The clean base everything else builds on.)*
2. **P2 - Reward SPI runtime:** wire `VoteRewardProvider` registration + descriptor execution into the
   decomposed `VoteRewardCoordinator`; Premium gate + graceful Free degradation; new typed events.
3. **P3 - Proxy sync:** DB-authoritative reconcile (join + poll) + dedupe; then optional Redis pub/sub +
   network-scoped party/multiplier (Premium).
4. **P4 - REST generalization + publish:** config-drive the REST server, document endpoints; publish
   `jexvote-api` to Maven + README + example plugin.
5. **P5 - S4 hooks:** the vote->S4-progression rewards (gear-material keys, small skill/collection XP tokens
   via the SPI/descriptors) so voting feeds the new systems (ties to `SEASON4_EXISTING_SYSTEMS_INTEGRATION.md`
   vote row).

## 6. Open (build-time)
- Redis event schema + channel names; poll interval default.
- The exact `VoteRewardDescriptor` shape for `items` (serialized ItemStack vs material+meta) - pick the most
  version-portable.
- Which write-hooks (if any) are also exposed over REST.
- Semver starting point for the published api (propose `1.0.0` for the api artifact, independent of the plugin's
  3.x/4.x version).
- Vote-shop currency and achievement-set expansion (carried from `VOTE_ACHIEVEMENT_PLAN.md` - fold or supersede).

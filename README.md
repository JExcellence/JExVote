<div align="center">

![JExVote banner](attachment-961876)

# JExVote
### The Votifier Reward System

Built-in Votifier server · Vote streaks · Streak Freezes · Vote Gifting · Jackpot rewards · Beautiful GUIs · Multi-language

*Supports Votifier v1 (RSA) and NuVotifier v2 (HMAC). No external Votifier plugin required.*

</div>

---

## ■ New in 3.1.0

This release is about retention: making players want to vote every day and stick around.

- **Streak Freezes** protect a streak when a day is missed. They auto-equip, everyone starts with one free, and more are bought with vote points.
- **Vote Gifting** lets players keep a friend's streak alive with `/vote gift`.
- **Vote Jackpot** rolls one prize from a weighted pool on every vote, with the odds shown in the menu.
- **Reworked streak rewards** form an escalating crate ladder from day 3 to day 30.
- **Vote Party** rewards every contributor when the server hits a shared vote target (Premium).

Full details further down. Everything is configurable.

> **3.1.1 (patch):** fixes a 3.1.0 upgrade migration error on existing player databases (new `NOT NULL` columns lacked a SQL default). If you ran 3.1.0, update to 3.1.1 and restart; recovery is automatic, no manual database changes needed.

---

## ■ What is JExVote?

JExVote handles everything around server voting: receiving votes from voting sites, rewarding players, tracking streaks, and showing statistics. It ships with its own Votifier-compatible server built in, so you do not need NuVotifier or any other listener plugin. Point your vote sites at the configured port and JExVote does the rest.

Votes trigger configurable rewards, feed a streak system that brings players back, and fill a leaderboard GUI every player can open. Admins get full control through commands and hot-reloadable config files.

---

## ■ Feature Overview

- **Built-in Votifier server**: Supports v1 (RSA-encrypted) and v2 (HMAC-SHA256) out of the box. No NuVotifier dependency.
- **14 reward types**: Commands, items, XP, currency, permissions, sounds, particles, titles, teleports, composite bundles, choice menus, custom hooks, plus **chance** and **lucky** (weighted jackpot) rewards.
- **Streak Freezes**: Auto-equipping streak protection. Free on first vote, buyable with vote points, with a per-rank cap permission.
- **Vote Gifting**: Players gift a streak day to a friend or a random online player, with a configurable daily limit.
- **Vote streaks**: Tracks consecutive daily votes with a configurable timeout. Milestone rewards at any day threshold you define.
- **Vote Jackpot**: A weighted prize pool rolled per vote, with each outcome's drop chance and drop count visible in the menu.
- **Vote Party**: A shared server-wide counter that rewards every contributor on completion (Premium).
- **Weekend multipliers**: Scale points and reward amounts during configurable windows (Premium).
- **Per-site rewards**: Bonus rewards for specific vote sites on top of the default set.
- **Offline vote queue**: Votes received while a player is offline are stored and delivered on next login.
- **Interactive GUIs**: Vote overview, paginated leaderboard, streak progress, and a rewards menu, all from `/vote`.
- **Vote site cooldowns**: Rolling window or fixed daily reset per site. Players see exactly when they can vote again.
- **Vote points**: Each site awards configurable points, tracked separately from vote count and spent on Streak Freezes.
- **Broadcast & private messages**: Server-wide announcements (all / others / none) with anti-spam cooldown, plus optional private thank-you messages.
- **Multi-language**: Ships with English, German, Czech, and Slovak. Add your own or override existing keys.
- **Record retention**: Automatically purges old vote records after a configurable number of days.
- **Spigot / Paper / Folia**: Scheduled tasks run through FoliaLib for full Folia support.
- **Customizable commands**: Aliases, translations, and command trees are fully configurable.

---

## ■ Engagement Systems

### ❄ Streak Freezes
A Streak Freeze keeps a streak alive when a player misses a day. It is fully automatic: if a vote arrives after the streak would have broken, the needed freezes are spent and the streak continues. Every player starts with one free freeze (they are told on their first vote), and more are bought with vote points through `/vote freeze`. Cost, the owned cap, and how long one freeze covers are all configurable, and `jexvote.freeze.max.<n>` raises the cap per rank.

### 🎁 Vote Gifting
Players keep a friend's streak going when that friend cannot log in.

- `/vote gift <player>` gifts a streak day to a specific player.
- `/vote gift random` gifts one to a random online player.

A gift advances only the receiver's streak. The gifter keeps their own vote and rewards. The daily limit defaults to one (raise it with `jexvote.gift.daily.<n>`), gifting can require the gifter to have voted that day, and a player can only be gifted once per day.

### 🎰 Vote Jackpot
Every vote rolls one prize from a weighted pool. The rewards menu lists each outcome with its exact drop chance and how many times it has dropped. The default pool runs from small coin wins up to a top-tier crate key, and it is fully editable in `rewards.yml`.

### 🎉 Vote Party *(Premium)*
A shared, server-wide counter. When votes reach the target, every player who contributed is rewarded (online players right away, offline players on next login), then the counter resets.

---

## ■ GUIs

![Vote Overview](attachment-961877)
**Vote Overview** — the main menu players see when they type `/vote`. Shows personal stats, a progress bar for the current streak, all configured vote sites with clickable links, vote points, and navigation to the leaderboard and streak views.

![Leaderboard](attachment-961878)
**Leaderboard** — a paginated all-time leaderboard with player heads for the top 3. Each entry shows total votes, monthly votes, streak, vote points, and a visual vote bar. Use the arrow buttons to change pages.

![Streak Progress](attachment-961879)
**Streak Progress** — shows the current streak, highest streak, and every configured milestone. Achieved milestones glow green, the next target is highlighted yellow, and locked ones are red. A 20-segment bar shows how close the player is to the next reward, with each tier's full reward list on hover.

*The rewards menu (Lucky Vote odds, weekend multiplier, vote party, Streak Freeze, Vote Gift and vote points) opens from `/vote rewards`.*

---

## ■ Setup Guide

<details>
<summary>Step 1 — Install</summary>

Drop `JExVote.jar` into your `plugins/` folder and restart the server. JExVote generates its config files, an RSA keypair, and a Votifier token on first launch.
</details>

<details>
<summary>Step 2 — Configure the Votifier server</summary>

Open `config.yml` and check the votifier section:

```yaml
votifier:
  host: ''          # Leave empty to bind all interfaces
  port: 8192        # The port vote sites connect to
  token: ''         # Auto-generated on first run. Copy it into your vote site settings.
```

If your host needs a specific bind address, set `host` accordingly. The token is generated automatically. Copy it from the config after the first start and paste it into your vote site's Votifier settings.

**Important:** make sure port 8192 (or whatever you set) is open in your firewall and hosting panel. Vote sites need to reach this port directly.
</details>

<details>
<summary>Step 3 — Add vote sites</summary>

Edit `sites.yml` to register each voting site:

```yaml
sites:
  serverliste:
    display-name: "Serverliste.net"
    service-name: "Serverliste"        # Must match what the site sends
    vote-url: "https://serverliste.net/vote/12345"
    cooldown-minutes: 1440             # 24-hour rolling window
    points-per-vote: 1

  mcserverlist:
    display-name: "MC Server List"
    service-name: "MCServerList"
    vote-url: "https://minecraft-server-list.com/server/12345/vote/"
    daily-reset: "00:00"               # Fixed daily reset
    timezone: "Europe/Berlin"
    points-per-vote: 2
```

Cooldown modes, pick one per site:

- `cooldown-minutes`: rolling window. The player can vote again X minutes after their last vote.
- `daily-reset` + `timezone`: fixed reset. Everyone can vote again at the given time in the given timezone.

The `service-name` must match the service identifier the vote site sends in its Votifier payload. If you are unsure, run `/jexvote fakevote <player> TestService` or check the server log for the incoming service name.
</details>

<details>
<summary>Step 4 — Configure rewards</summary>

Open `rewards.yml`. Each reward is a named entry with a `type`:

```yaml
default-rewards:
  thank-you-title:
    type: title
    title: "<green>Thank you!"
    subtitle: "<gray>Your vote has been counted."
    fade-in: 10
    stay: 40
    fade-out: 10
  level-up:
    type: sound
    sound: ENTITY_PLAYER_LEVELUP
    volume: 1.0
    pitch: 1.2
  starter-coins:
    type: currency
    currency: coins
    amount: 2500
```

Rewards are split into categories:

- `default-rewards`: granted on every vote, regardless of site.
- `streak-rewards`: bonus rewards at specific streak milestones (day 7, 14, 30, and so on).
- `site-rewards`: extra rewards for a specific site, keyed by site ID from `sites.yml`.
- `vote-party-rewards`: granted to every contributor when a vote party completes.

*Command rewards run as console with `asConsole: true`. Placeholders: `{player}`, `{uuid}`, `{world}`, `{x}`, `{y}`, `{z}`.*
</details>

<details>
<summary>Step 5 — Verify</summary>

Run `/jexvote fakevote <yourname>` to trigger a test vote and confirm rewards, broadcasts, and the GUI all work. The console logs every vote it receives with the protocol version and service name.
</details>

---

## ■ Reward Types

<details>
<summary>View all 14 reward types</summary>

| Type | Description | Key Fields |
|------|-------------|------------|
| command | Run a console or player command | command, asConsole |
| item | Give an item stack, optionally named and enchanted | material, amount, name, lore, enchantments, glow |
| xp | Grant vanilla XP points | amount |
| currency | Deposit into a currency (JExEconomy or Vault) | currency, amount |
| permission | Grant a timed permission via LuckPerms | permission, duration, server |
| sound | Play a sound effect | sound, volume, pitch |
| particle | Spawn a particle effect | particle, count, offset |
| title | Display a title and subtitle | title, subtitle, fade-in, stay, fade-out |
| teleport | Teleport the player | world, x, y, z, yaw, pitch |
| composite | Bundle multiple rewards together | rewards (list) |
| choice | Let the player pick from a GUI menu | options (list) |
| custom | Hook into your own plugin logic | handler, data |
| chance | Grant a nested reward with a set probability | chance, show-chance, announce, reward |
| lucky | Grant one reward from a weighted pool (jackpot) | entries (weight, id, reward) |

</details>

---

## ■ Commands & Permissions

**Player Commands**

```
/vote                    Opens the vote GUI
/vote rewards            Opens the rewards menu (jackpot odds, multiplier, freeze, gift, points)
/vote freeze             Buy a Streak Freeze with vote points
/vote gift <player>      Gift a streak day to a player (or 'random')
/vote stats [player]     View vote statistics (opens the GUI for yourself)
/vote top [count]        View the leaderboard (opens the GUI)
/vote sites              List all vote sites with clickable links
/vote help               Show player command help
```

Permission: `jexvote.command.vote` · Alias: `/v`

**Admin Commands**

```
/jexvote                 Shows admin help
/jexvote info            Shows edition, version, site count, Votifier port
/jexvote reload          Hot-reloads config.yml, rewards.yml, sites.yml
/jexvote reset <player>  Resets all vote data for a player
/jexvote resetmonthly    Resets monthly counts for ALL players
/jexvote fakevote <player> [service]   Simulates a vote for testing
```

Alias: `/jv`

<details>
<summary>Permission Nodes</summary>

```
jexvote.command.vote              Access to /vote and all subcommands
jexvote.command.admin             Access to /jexvote base and info
jexvote.command.admin.reload      Reload configuration files
jexvote.command.admin.reset       Reset player data and monthly counts
jexvote.command.admin.fakevote    Simulate test votes
jexvote.freeze.max.<n>            Raise the Streak Freeze owned cap to <n>
jexvote.gift.daily.<n>            Raise the daily gift limit to <n>
```

</details>

---

## ■ Configuration Reference

<details>
<summary>config.yml — Full reference</summary>

```yaml
# ── Votifier Server ──
votifier:
  host: ''                    # Bind address (empty = all interfaces)
  port: 8192                  # Port for vote site connections
  token: ''                   # HMAC token (auto-generated)

# ── Broadcasts ──
broadcast:
  mode: 'all'                 # all | others | none
  cooldown: 0                 # Minimum seconds between broadcasts (0 = every vote)

# ── Private Messages ──
private-message:
  enabled: true

# ── Offline Queue ──
offline-vote-queue: true

# ── Streaks ──
streak:
  timeout-hours: 36           # Hours before a streak resets
  claim-mode: 'auto'          # auto | manual
  freeze:
    enabled: true
    free-amount: 1            # Free freezes granted on first vote
    cost-points: 5            # Vote points per purchased freeze
    default-max: 3            # Owned cap (raise per rank with jexvote.freeze.max.<n>)
    duration-hours: 24        # Grace one freeze covers
  bonus-commands:
    7:
      - "give {player} diamond 3"
    30:
      - "broadcast &6{player} &7hit a &630-day &7vote streak!"

# ── Vote Gifting ──
vote-gift:
  enabled: true
  daily-limit: 1              # Raise per rank with jexvote.gift.daily.<n>
  require-vote-today: true
  timezone: 'UTC'

# ── Vote Party (Premium) ──
vote-party:
  enabled: true
  target: 100

# ── Weekend Multiplier (Premium) ──
multipliers:
  weekend:
    enabled: false
    factor: 2.0
    days: [SATURDAY, SUNDAY]
    timezone: 'UTC'

# ── Housekeeping ──
records:
  retention-days: 90          # Auto-purge vote records older than this
```

</details>

---

## ■ Streak System

Streaks track how many days in a row a player has voted. The timeout is configurable. By default a player has 36 hours between votes before the streak resets, which gives some buffer beyond a strict 24-hour window. Streak Freezes extend that buffer automatically when a day is missed.

Milestones can trigger two things, independently:

- **Bonus commands**: defined in `config.yml` under `streak.bonus-commands`. Quick and simple.
- **Streak rewards**: defined in `rewards.yml` under `streak-rewards`. Uses the full reward system (items, currency, crate keys, choice menus, and so on).

Both fire at the same milestone if you configure both. The default setup is an escalating crate ladder: each day tier grants XP, coins, a crate key, and useful gear, building up to the top-tier crate at day 30.

Streak progress shows in every GUI: the overview has a bar toward the next milestone, and the dedicated streak view breaks down each tier with achieved, upcoming, and locked status.

---

## ■ Votifier Protocol

JExVote includes a fully self-contained Votifier server. There is no need to install NuVotifier or any third-party listener.

- **Votifier v1**: 256-byte RSA-encrypted vote block. Legacy support for older vote sites.
- **NuVotifier v2**: JSON payload with HMAC-SHA256 signature verification and challenge-response. Used by most modern vote sites.

JExVote auto-detects the protocol version per connection. The RSA keypair is generated on first launch and stored in `plugins/JExVote/rsa/`. The HMAC token is written to `config.yml`. If a vote site asks for your public key, it is at `plugins/JExVote/rsa/public.key` (Base64-encoded).

---

## ■ Language & Localization

JExVote ships with English (en_US), German (de_DE), Czech (cs_CZ), and Slovak (sk_SK). The language is auto-detected per player from their client locale, and item names in the rewards menu use the player's own client language. To force one locale for the whole server, set it in the plugin's translation config:

```yaml
# Leave empty for auto-detection.
force-locale: 'de_DE'
```

Add custom translations by placing a `<locale>.yml` file in the `translations/` folder. Missing keys fall back to English.

---

## ■ Dependencies

**Required**

- Paper 1.20.4+ (or Spigot / Folia)
- Java 21+

**Optional (soft dependencies)**

- **JExEconomy**: enables the currency reward type for any configured currency.
- **LuckPerms**: enables the permission reward type (timed, server-specific).
- **PlaceholderAPI**: exposes vote placeholders for scoreboards, tab lists, and more.
- **Vault**: economy fallback if JExEconomy is not installed.

---

<div align="center">

Free edition is limited to 5 vote sites. Premium unlocks unlimited sites, the vote shop, Vote Party, and weekend multipliers.

*JExVote is part of the JExcellence plugin suite. Issues, feature requests, and support: use the Discussion tab or open a ticket.*

</div>

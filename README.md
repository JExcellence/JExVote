# JExVote

All-in-one vote listener, reward and leaderboard system for Minecraft servers.

JExVote **replaces NuVotifier** entirely. It has a built-in Votifier server that speaks both the legacy v1 (RSA) and modern v2 (HMAC-SHA256) protocols, so you do **not** need NuVotifier, Votifier, or any other vote receiver plugin. Just drop in JExVote, point your vote listing sites at it, and you're done.

---

## Migrating from NuVotifier

Already running NuVotifier? Switching is straightforward:

1. **Remove NuVotifier** — delete `NuVotifier.jar` (or `Votifier.jar`) from your `plugins/` folder.
2. **Install JExVote** — drop the JExVote JAR into `plugins/` and start the server once to generate config files.
3. **Port settings** — open `plugins/JExVote/config.yml` and set the same `host` and `port` you had in NuVotifier (default `8192`).
4. **Token / Key** — JExVote auto-generates a new RSA key pair and token on first run. Copy the new **public key** (`plugins/JExVote/rsa/public.key`) and **token** (printed in console) to your vote listing sites. If a site only supports v1, use the public key. If it supports v2, use the token.
5. **Restart** — no other plugins need to change. Any plugin that listens for `VotifierEvent` can switch to JExVote's `VoteReceivedEvent` (or use the API).

> **Note:** JExVote's Votifier server is wire-compatible with all major vote listing sites. If a site works with NuVotifier, it works with JExVote — the only thing that changes is the key/token.

---

## Features

- **Built-in Votifier protocol** — receives votes directly; supports both legacy v1 (RSA) and modern v2 (HMAC-SHA256)
- **Reward system** — 12 reward types (commands, items, XP, currency, permissions, sounds, particles, titles, teleport, composite bundles, player-choice, custom handlers)
- **Vote streaks** — configurable streak tracking with milestone rewards and automatic timeout
- **Leaderboard** — paginated GUI and chat-based top-voter lists (all-time and monthly)
- **Broadcast system** — public vote announcements with anti-spam cooldown and configurable audience (all / others / none)
- **Offline vote queue** — votes received while a player is offline are stored and delivered on next login
- **Per-site configuration** — each vote site can have its own cooldown mode (rolling window or daily reset at a fixed time), timezone, points-per-vote, and bonus rewards
- **Fully translatable** — ships with English, German, Czech and Slovak; add your own locale by dropping a YAML file
- **Editable commands** — command names, aliases, permissions and descriptions are defined in YAML files inside the data folder
- **PlaceholderAPI support** — exposes `%jexvote_total%`, `%jexvote_monthly%`, `%jexvote_streak%`, `%jexvote_points%` and more
- **Developer API** — `jexvote-api` module with `JExVoteAPI`, `VoteProvider`, events (`VoteReceivedEvent`, `VoteRewardClaimedEvent`) and `VoteSnapshot` model
- **Folia compatible** — uses `PlatformScheduler` for thread-safe entity scheduling

---

## Requirements

| Dependency | Version | Required |
|---|---|---|
| Java | 21+ | Yes |
| Paper / Folia | 1.21.4+ | Yes |
| JExPlatform | 3.x | Yes (bundled) |
| JEHibernate | 3.x | Yes (bundled) |
| PlaceholderAPI | 2.11+ | Optional |
| JExEconomy | 3.x | Optional (for `currency` rewards) |

---

## Installation

1. Download the JExVote JAR (`JExVote-Free` or `JExVote-Premium`) and place it in your server's `plugins/` folder.
2. Start the server. JExVote generates all default configuration files on first run.
3. Stop the server and edit the generated files in `plugins/JExVote/` (see [Configuration](#configuration) below).
4. Open the Votifier port in your firewall (default `8192`).
5. Copy the **token** printed in the console (or from `config.yml`) and your server's **public key** (`plugins/JExVote/rsa/public.key`) to each vote listing site.
6. Restart the server.

---

## Configuration

All configuration files live in `plugins/JExVote/`. Files are generated with sensible defaults on first run — you only need to change what matters to you.

### config.yml — Main configuration

```yaml
# Built-in Votifier server
votifier:
  host: ''          # Leave empty to bind all interfaces (0.0.0.0)
  port: 8192        # Must match the port on your vote listing sites
  token: ''         # Auto-generated on first run; copy to vote sites

# Public chat broadcast when someone votes
broadcast:
  mode: 'all'       # all | others | none
  cooldown: 0       # Minimum seconds between broadcasts (anti-spam)

# Private "thank you" message to the voter
private-message:
  enabled: true

# Queue rewards for offline voters and deliver on login
offline-vote-queue: true

# Vote streak settings
streak:
  timeout-hours: 36           # Hours before a streak breaks
  bonus-commands:              # Commands at streak milestones
    7:
      - 'give {player} diamond 3'
    30:
      - 'give {player} diamond_block 1'

# Commands executed on every vote (placeholders: {player}, {service}, {streak})
commands-on-vote: []

# How long to keep individual vote records
records:
  retention-days: 90
```

### sites.yml — Vote listing sites

Each site needs a unique ID, a `service-name` that matches what the site sends in its Votifier payload, and a cooldown mode.

> **Important:** The `service-name` is often an abbreviation, not the full website URL. For example, `minecraft-server-list.com` sends `MCSL`. Check your listing site's documentation.

```yaml
sites:
  minecraft-server-list:
    display-name: 'Minecraft Server List'
    service-name: 'MCSL'
    vote-url: 'https://minecraft-server-list.com/server/123456/vote/'
    daily-reset: '00:00'       # Fixed reset time (alternative to rolling cooldown)
    timezone: 'UTC'
    points-per-vote: 1

  topg:
    display-name: 'TopG'
    service-name: 'TopG.org'
    vote-url: 'https://topg.org/minecraft-servers/server-123456'
    cooldown-minutes: 1440     # Rolling 24h window
    points-per-vote: 1
```

**Cooldown modes** (pick one per site):
| Mode | Key | Description |
|---|---|---|
| Rolling window | `cooldown-minutes: 1440` | Player can vote again 24 h after their last vote |
| Daily reset | `daily-reset: '00:00'` + `timezone: 'UTC'` | Votes reset at a fixed time each day |

### rewards.yml — Reward configuration

Rewards are split into three sections:

| Section | Purpose |
|---|---|
| `default-rewards` | Granted on every vote |
| `streak-rewards` | Bonus rewards at streak milestones (day 7, 14, 30, ...) |
| `site-rewards` | Extra rewards for voting on a specific site |

#### Available reward types

| Type | Description |
|---|---|
| `command` | Executes a server command (`{player}` placeholder) |
| `xp` | Grants vanilla experience points |
| `currency` | Deposits money via JExEconomy |
| `item` | Gives an item stack with optional name, lore, enchantments |
| `permission` | Grants a permission node (optional duration, LuckPerms) |
| `sound` | Plays a sound effect |
| `particle` | Spawns particles at the player |
| `title` | Shows a title + subtitle on screen |
| `teleport` | Teleports the player to coordinates |
| `composite` | Bundles multiple rewards into one |
| `choice` | Opens a GUI for the player to pick one reward |
| `custom` | Delegates to a handler registered by another plugin |

Example:

```yaml
default-rewards:
  vote-xp:
    type: xp
    amount: 50
  vote-diamonds:
    type: command
    command: 'give {player} diamond 1'
    as-console: true

streak-rewards:
  30:
    streak-30-title:
      type: title
      title: '<gold><bold>30 Day Streak!'
      subtitle: '<gray>Dedicated voter!'
      fade-in: 10
      stay: 60
      fade-out: 10
```

The `rewards.yml` file shipped with the plugin contains commented examples for every reward type.

### commands/ — Command definitions

Command names, aliases, permissions and descriptions are defined in editable YAML files:

| File | Command | Default aliases |
|---|---|---|
| `commands/vote.yml` | `/vote` | `/v` |
| `commands/jexvote.yml` | `/jexvote` | `/jv` |

To rename `/vote` to `/votefor`, change the `name:` field in `commands/vote.yml` and restart.

### database/hibernate.properties — Database backend

JExVote uses JEHibernate. By default it creates an H2 file database. To switch to MySQL or MariaDB, edit `database/hibernate.properties`:

```properties
hibernate.connection.url=jdbc:mariadb://localhost:3306/jexvote
hibernate.connection.username=root
hibernate.connection.password=secret
hibernate.connection.driver_class=org.mariadb.jdbc.Driver
hibernate.dialect=org.hibernate.dialect.MariaDBDialect
```

---

## Commands

### Player commands (`/vote`)

| Command | Description | Permission |
|---|---|---|
| `/vote` | Opens the vote GUI | `jexvote.command.vote` |
| `/vote sites` | Lists all vote sites with clickable links | `jexvote.command.vote` |
| `/vote stats [player]` | Shows vote statistics | `jexvote.command.vote` |
| `/vote top [count]` | Shows the leaderboard | `jexvote.command.vote` |

### Admin commands (`/jexvote`)

| Command | Description | Permission |
|---|---|---|
| `/jexvote info` | Shows edition and version | `jexvote.command.admin` |
| `/jexvote reload` | Reloads all configuration | `jexvote.command.admin.reload` |
| `/jexvote reset <player>` | Resets a player's vote stats | `jexvote.command.admin.reset` |
| `/jexvote resetmonthly` | Resets monthly votes for all players | `jexvote.command.admin.reset` |
| `/jexvote fakevote <player> [service]` | Simulates a vote (for testing) | `jexvote.command.admin.fakevote` |

---

## Permissions

| Permission | Description | Default |
|---|---|---|
| `jexvote.command.vote` | Access to `/vote` and subcommands | Everyone |
| `jexvote.command.admin` | Access to `/jexvote` base command | OP |
| `jexvote.command.admin.reload` | Reload configuration | OP |
| `jexvote.command.admin.reset` | Reset player stats | OP |
| `jexvote.command.admin.fakevote` | Simulate votes | OP |

---

## PlaceholderAPI

If PlaceholderAPI is installed, the following placeholders are available:

| Placeholder | Description |
|---|---|
| `%jexvote_total%` | Total votes (all time) |
| `%jexvote_monthly%` | Votes this month |
| `%jexvote_streak%` | Current vote streak |
| `%jexvote_highest_streak%` | Highest streak ever |
| `%jexvote_points%` | Vote points balance |

---

## Translations

JExVote ships with four languages: `en_US`, `de_DE`, `cs_CZ`, `sk_SK`.

Translation files use MiniMessage formatting and are located in `plugins/JExVote/i18n/`. To add a new language, copy an existing file and rename it (e.g. `fr_FR.yml`), then register the locale in the platform builder.

---

## Developer API

Add `jexvote-api` as a dependency:

```kotlin
// build.gradle.kts
compileOnly("de.jexcellence.vote:jexvote-api:3.0.0")
```

### Accessing the API

```java
JExVoteAPI api = JExVoteAPI.get();          // throws if not loaded
JExVoteAPI api = JExVoteAPI.getOrNull();    // returns null if not loaded
VoteProvider provider = api.provider();
```

### Querying vote data

```java
provider.getTotalVotes(playerUuid).thenAccept(total -> {
    System.out.println("Total votes: " + total);
});

provider.getTopVoters(10).thenAccept(list -> {
    for (VoteSnapshot snap : list) {
        System.out.println(snap.playerName() + ": " + snap.totalVotes());
    }
});
```

### Submitting a vote programmatically

```java
provider.submitVote("Notch", "MyVoteSite").thenAccept(success -> {
    if (success) System.out.println("Vote processed!");
});
```

### Listening to events

```java
@EventHandler
public void onVote(VoteReceivedEvent event) {
    String player = event.getPlayerName();
    String service = event.getServiceName();
    // event.setCancelled(true) to prevent processing
}

@EventHandler
public void onRewardClaimed(VoteRewardClaimedEvent event) {
    // Fired after rewards are delivered
}
```

---

## Votifier Setup Guide

### 1. Find your server IP and port

Your Votifier IP is the same IP players use to connect to your server. The default port is `8192`.

### 2. Open the port in your firewall

If you're hosted on a provider like Hetzner, OVH, or a shared host, make sure port `8192/TCP` is open in your firewall rules.

**Hetzner Cloud:**
1. Go to your server's **Firewall** tab
2. Add an inbound rule: Protocol `TCP`, Port `8192`, Source `0.0.0.0/0`

**Linux (ufw):**
```bash
sudo ufw allow 8192/tcp
```

### 3. Get your token and public key

On first start, JExVote generates:
- A **token** (printed in console and stored internally) — used for Votifier v2 (HMAC)
- An **RSA key pair** in `plugins/JExVote/rsa/` — used for Votifier v1

### 4. Configure vote sites

Each listing site asks for:

| Field | Value |
|---|---|
| Votifier IP | Your server IP (e.g. `play.example.com`) |
| Votifier Port | `8192` (or whatever you set in `config.yml`) |
| Public Key | Contents of `plugins/JExVote/rsa/public.key` |
| Token | The token from your console log or config |

> Some sites only support v1 (public key), others support v2 (token), and some support both. Fill in what the site asks for.

### 5. Test with fakevote

```
/jexvote fakevote YourName MCSL
```

This simulates a vote from the service `MCSL` and triggers the full reward pipeline.

---

## Editions

| Feature | Free | Premium |
|---|---|---|
| Votifier v1 + v2 | Yes | Yes |
| Rewards (all 12 types) | Yes | Yes |
| Streaks | Yes | Yes |
| Leaderboard GUI | Yes | Yes |
| PlaceholderAPI | Yes | Yes |
| Developer API | Yes | Yes |
| Vote shop | — | Yes |
| Custom GUI layouts | — | Yes |
| Priority support | — | Yes |

---

## Building from source

```bash
./gradlew buildAll
```

Output JARs are placed in `jexvote-free/build/libs/` and `jexvote-premium/build/libs/`.

---

## License

All rights reserved. See [LICENSE](LICENSE) for details.

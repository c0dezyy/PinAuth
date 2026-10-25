# CrossplayPinAuth

A Paper plugin that makes every player log in with a **6-digit PIN** entered on an **inventory keypad**.
The same chest GUI works for Java players and for Bedrock players joining through **GeyserMC + Floodgate**.
No client mods, no web server. Data is stored in SQLite, keyed by **UUID**.

- GUI title: `Kodeshi SMP | Login` (all titles/materials/slots/messages configurable)
- PIN registration with confirmation, login, **change PIN (old PIN required)**
- 4 wrong attempts (configurable) → disconnect + temporary lockout that survives reconnecting
- Admin commands to reset/clear PINs (online or offline), status, reload
- Unauthenticated players are frozen and cannot interact with the world, items, chat or commands

## Requirements

| Item | Version |
|------|---------|
| Server | Paper 1.21.x (built against `1.21.4-R0.1-SNAPSHOT`, `api-version: 1.21`) |
| Java | 21 |
| Bedrock support (optional) | GeyserMC + Floodgate |
| Database | SQLite JDBC, which Paper already bundles (no extra download) |

## Getting the JAR (GitHub Actions)

1. Create an empty GitHub repository and push this folder:
   ```bash
   git init
   git add .
   git commit -m "Initial commit"
   git branch -M main
   git remote add origin https://github.com/<you>/<repo>.git
   git push -u origin main
   ```
2. Open the **Actions** tab. The *Build plugin* workflow (`.github/workflows/build.yml`) runs on every push,
   pull request and manual run (*Run workflow*). It builds with Maven + JDK 21, checks that the JAR contains
   `plugin.yml`, `config.yml` and the main class, and uploads it as the **CrossplayPinAuth** artifact.
3. Open the finished run and download the artifact (a zip containing `CrossplayPinAuth-1.0.0.jar`).
4. **Releases (optional):** push a tag and the JAR is attached to a GitHub Release automatically:
   ```bash
   git tag v1.0.0 && git push origin v1.0.0
   ```

Build locally instead: `mvn clean package` → `target/CrossplayPinAuth-1.0.0.jar`.
Target another Paper version: `mvn clean package -Dpaper.version=1.21.8-R0.1-SNAPSHOT`.

## Installation

1. Put the JAR into `plugins/` and start the server once.
2. Edit `plugins/CrossplayPinAuth/config.yml` if you like, then run `/pinauth reload`.
3. **Back up `accounts.db` together with `secret.key`.** PINs are mixed with this server-side secret before
   hashing; without it existing PINs cannot be verified (admins would need to `/pinreset` everyone).

### Geyser / Floodgate setup

- Install **Geyser** and **Floodgate** on the Paper server (or Geyser standalone/proxy with Floodgate on both).
  Floodgate is detected automatically (soft dependency). If it is missing you get a startup warning and
  Bedrock players are detected with a UUID heuristic instead.
- Bedrock accounts get Floodgate UUIDs and a username prefix (default `.`), so a Java player `Steve` and a Bedrock
  player `.Steve` are **separate accounts**. Records are keyed by UUID, never by name.
- If Floodgate is not used, set Geyser `auth-type: offline` and note that Bedrock players are then identified by
  an offline-style UUID derived from the name.
- Set `allow-bedrock-players: false` to refuse Bedrock players.
- Offline-mode (cracked) Java servers: anyone can claim any name, so a PIN is the only protection. The plugin refuses
  a second connection with the same UUID (`block-duplicate-login`) so nobody can kick a logged-in player.
  Online-mode servers work too; the PIN is then a second factor.

## Commands

| Command | Permission | Description |
|---------|-----------|-------------|
| `/pinreset <player\|uuid>` | `pinauth.admin` | Clear the PIN, mark `NEEDS_REGISTRATION`. Online: session revoked immediately and the registration GUI opens. Offline: applied at next join. |
| `/clearpin <player\|uuid>` | `pinauth.admin` | Same operation as `/pinreset`. Other player data is untouched. |
| `/pinstatus <player\|uuid>` | `pinauth.admin` | Shows registration status, session state, lockout and failed count (never the hash). |
| `/pinauth reload` | `pinauth.reload` | Re-reads `config.yml` safely (invalid values fall back to defaults). |
| `/pinauth help` | `pinauth.admin` or `pinauth.reload` | Lists commands. |
| `/changepin` | `pinauth.changepin` (default: everyone) | Keypad asks for the **current PIN**, then the new PIN, then confirmation. |
| `/pinlogin` (alias `/pin`) | everyone | Re-opens the keypad if it was closed. Usable before logging in. |

Offline players are looked up by the username stored when they last joined (case-insensitive). If several accounts
share a name, the command lists their UUIDs and you re-run it with the UUID. Tab completion lists online players.
Every reset is logged to the console (admin, target name, UUID), never PINs or hashes.

## Permissions

| Permission | Default | Grants |
|------------|---------|--------|
| `pinauth.admin` | op | `/pinreset`, `/clearpin`, `/pinstatus`, `/pinauth help` (also includes `pinauth.reload`) |
| `pinauth.reload` | op | `/pinauth reload` |
| `pinauth.changepin` | everyone | `/changepin` |

Permissions are checked server-side in the command handlers as well as via `plugin.yml`.

## How it works

- **Default deny.** A player is unauthenticated unless their UUID is in an in-memory authenticated set, which is
  cleared on every disconnect (so reconnecting or changing dimension never bypasses login).
- **Restrictions before login** (config `freeze-unauthenticated-players` controls movement): no movement, teleport,
  block break/place, buckets, interaction, attacking/being damaged, item pickup/drop/swap/consume, hunger, mob
  targeting, vehicles, beds, chat, opening other inventories, and all commands except `allowed-commands`. Commands
  are also hidden from tab completion. Only the plugin's own keypad inventory is exempt.
- **Keypad.** Every click/drag in the keypad (or in the player's inventory while it is open) is cancelled and
  interpreted as a button press, so nothing can be moved or taken. Closing it re-opens it (`reopen-gui-on-close`);
  after `max-gui-closes` closes the player is kicked. The cancel button kicks (or aborts a PIN change).
- **Attempts.** Wrong PINs are counted **in the database**. At `max-login-attempts` the player is disconnected and
  the account is locked for `lockout-duration-seconds`; the lock is checked at pre-login, so reconnecting does not
  help. Submissions are also rate-limited (`attempt-cooldown-millis`) and one verification runs at a time.
  Nobody is permanently banned.
- **Timeout.** `login-timeout-seconds: 0` (default) = no timeout. Any positive value kicks after that many seconds.
- **Threads.** Hashing runs on a 2-thread pool, SQLite on a single dedicated thread. The main thread never waits
  for either (pre-login runs on Paper's async thread).
- **Fails closed.** If the database or `secret.key` cannot be initialised, all connections are refused with a
  clear console error instead of letting players in unauthenticated.

## Storage

`plugins/CrossplayPinAuth/accounts.db` (SQLite, WAL), table `accounts`:
`uuid` (PK), `username`, `pin_hash`, `status` (`REGISTERED` / `NEEDS_REGISTRATION`), `registered_at`, `last_login`,
`failed_count`, `lockout_until`, `edition` (`JAVA` / `BEDROCK`). Index on `username COLLATE NOCASE`.
The schema is versioned with `PRAGMA user_version`; migrations run automatically at startup, each in a transaction.
All SQL uses prepared statements.

Hash format: `pbkdf2-sha256$iterations$salt$hash`. The PIN is first HMAC-SHA256'd with `secret.key`, then PBKDF2 with a
unique random 16-byte salt (210,000 iterations by default). PINs and hashes are never logged.

## Security notes (please read)

A six-digit PIN has only 1,000,000 possibilities. **A PIN-only system cannot match strong passwords or Minecraft's
official account authentication.** The lockouts, cooldown and server-side secret make *online* guessing impractical, but
if an attacker steals both `accounts.db` and `secret.key` they can recover PINs offline quickly, whatever hash is used.
Keep those files private, restrict file access, and prefer online-mode for servers that need real account security.
Email-based recovery is intentionally not implemented (it would need a real verification service).

## Troubleshooting

- *"SQLite JDBC driver not found"*: your server build does not bundle `org.sqlite.JDBC`; use a current Paper build.
- *Everyone is kicked with "Authentication is temporarily unavailable"*: see the SEVERE line in the console at startup.
- *Players cannot be reset after moving the database*: copy `secret.key` along with `accounts.db`.

See `docs/TESTING.md` for the manual test checklist.

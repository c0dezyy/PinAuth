# PinAuth – Crossplay PIN authentication for Paper (Kodeshi SMP)

Six-digit PIN registration and login through a **plain chest-inventory keypad**, for Java Edition and
Bedrock Edition (via GeyserMC + Floodgate). SQLite storage, PBKDF2 hashing with unique salts, persistent
lockouts, admin reset commands, and a `/changepin` command that requires the old PIN.

## Requirements
- Paper 1.21.x, Java 21
- Optional: Geyser + Floodgate for Bedrock players
- Internet access on first start (Paper downloads the SQLite JDBC driver listed under `libraries:` in plugin.yml).
  For an offline host, drop `sqlite-jdbc` into the server's `libraries` folder or shade it (see "Build").

## Build
```
mvn clean package
```
Output: `target/PinAuth-1.0.0.jar`. To target another 1.21.x API: `mvn clean package -Dpaper.version=1.21.8-R0.1-SNAPSHOT`.
Verify the metadata: `unzip -l target/PinAuth-1.0.0.jar | grep -E "plugin.yml|config.yml"`.

### Build with GitHub Actions (no local Maven needed)
1. Push this folder to a GitHub repository (the workflow is `.github/workflows/build.yml`).
2. Open the **Actions** tab → "Build PinAuth" → latest run → download the **PinAuth-jar** artifact.
3. Optional: push a tag such as `v1.0.0` and the JAR is attached to a GitHub Release automatically.
The workflow also verifies that `plugin.yml` and `config.yml` are inside the JAR.

## Install
1. Put the JAR in `plugins/`, start the server once, edit `plugins/PinAuth/config.yml`, then `/pinauth reload`.
2. Back up **both** `plugins/PinAuth/accounts.db` and `plugins/PinAuth/pepper.key` (see Security).

## How it works
- New account → welcome message → "Kodeshi SMP | Register" keypad → enter PIN → "Confirm PIN" keypad → saved and logged in.
- Returning account → "Kodeshi SMP | Login" keypad. Digits are never displayed, only filled/empty slots.
- Buttons: digits 0-9, Backspace, Clear, Confirm, Leave server (kicks safely). All positions/materials/texts are configurable.
- Closing the window reopens it; after `max-gui-closes` closes the player is kicked. `/pin` reopens it manually.
- `/changepin`: asks for the **current PIN first**, then new PIN + confirmation. Closing the window cancels the change.

## Commands
| Command | Permission | Description |
|---|---|---|
| `/pinreset <player>` | `pinauth.admin` | Clear PIN, revoke session, require new registration (online: immediately; offline: next join) |
| `/clearpin <player>` | `pinauth.admin` | Same operation as `/pinreset` |
| `/pinstatus <player>` | `pinauth.admin` | Registered?, online state, last login, lockout |
| `/pinauth reload` | `pinauth.reload` | Reload config safely (invalid YAML keeps old settings) |
| `/pinauth help` | `pinauth.admin` | Command list |
| `/changepin` | `pinauth.change` (default: everyone) | Change own PIN, needs old PIN |
| `/pin` | none | Reopen the PIN window (only allowed command before login) |

`<player>` accepts an online name, a name stored in the database (offline), or a UUID. If a name matches several
accounts you are asked to use the UUID. Tab completion lists online players.

## Permissions
- `pinauth.admin` (default **op**) – admin commands; includes `pinauth.reload`.
- `pinauth.reload` (default **op**) – `/pinauth reload`.
- `pinauth.change` (default **true**) – `/changepin`.

## Trusted-IP auto login (optional)
In `config.yml`: set `ip-exemptions.enabled: true` and add entries `"name-or-uuid=ip"`. A player skips the PIN screen only when
**both** the account and the IP match. Do not list `127.0.0.1` or a proxy/Geyser IP (ignored with a warning). IPs can be shared
(same home network) and names can be claimed on offline-mode servers, so prefer UUID entries. Reload with `/pinauth reload`.

## Failed attempts, lockouts, timeouts
- `max-login-attempts: 4` → 3, 2, 1 attempts remaining, 4th wrong PIN disconnects ("Too many incorrect PIN attempts. You have been disconnected.").
- The counter is stored in SQLite. After the last attempt the account is locked for `lockout-duration-seconds` (default 300);
  reconnecting during the lockout is refused with the remaining time. Nobody is ever banned.
- `attempt-cooldown-millis` throttles PIN checks; hashing runs off the main thread; one check at a time per player.
- `login-timeout-seconds: 0` = **no timeout** (default). Set e.g. `60` to kick idle unauthenticated players.
- Registration mismatches: after `max-registration-failures` the player is kicked and blocked for `registration-block-seconds`.

## Protection before login
Cancelled for unauthenticated players: movement (head rotation allowed), teleport, portals, block break/place,
interaction, entity interaction, damage dealt and received, mob targeting, item drop/pickup/consume/swap, buckets,
beds, vehicles, hunger, chat, all commands except `allowed-commands`, every inventory except the PIN keypad.
A player with no session is treated as unauthenticated (fail-closed). NPC-plugin fake players (`NPC` metadata) are exempt.
If the database cannot be opened, PinAuth refuses **all** logins instead of letting anyone in.

## Compatibility notes
**Paper** – uses Adventure components, `AsyncChatEvent`, `Inventory#getHolder(boolean)`; Paper only.
**Geyser/Floodgate** – Bedrock is detected through `FloodgateApi#isFloodgatePlayer` (reflection, soft-depend). Without Floodgate a
warning is logged and the Floodgate UUID convention is used. Accounts are keyed by UUID, never by name. Floodgate's username prefix
(default `.`) is part of the stored name, so `Steve` (Java) and `.Steve` (Bedrock) are different accounts. Keep a prefix configured in
Floodgate; without one, two accounts can share a name – admin commands then ask for the UUID. Set `allow-bedrock-players: false` to refuse Bedrock.
**Online-mode** servers: Mojang authenticates Java players; the PIN is a second factor.
**Offline-mode** servers: anyone can claim any Java name until the PIN is set. Keep `prevent-duplicate-login: true`, register your
staff accounts first, and use a proxy/Floodgate setup you trust. PinAuth cannot prove who owns a Java username in offline mode.
**Other plugins** – spawn/teleport-on-join plugins are blocked from teleporting unauthenticated players (by design).
The keypad uses ordinary clicks (left/right/shift) so Bedrock tap input works; double-click events are ignored.

## Security notes (please read)
- A six-digit PIN has only 1,000,000 combinations. It **cannot** match a strong password or Mojang/Microsoft authentication.
  Protection comes from the attempt limit, persistent lockout, cooldown, and PBKDF2 (310k iterations default).
- Hashes are PBKDF2-HMAC-SHA256 with a random 128-bit salt per PIN, plus (default) a secret **pepper** in `pepper.key` mixed in by HMAC.
  A leaked `accounts.db` alone then cannot be brute-forced offline. **If `pepper.key` is lost, every PIN becomes invalid** (reset them
  with `/pinreset`). Restrict file permissions; never share the file.
- PINs and hashes are never logged, printed or sent in messages. The record's `toString()` omits the hash.
- All SQL uses prepared statements; all database work runs on one dedicated thread.
- No e-mail recovery is implemented (and none is simulated). Recovery is admin-only via `/pinreset`; a name alone never resets a PIN.
- Not provided: IP-based rate limiting, 2FA. Combine with a proxy-level connection throttle if you expect distributed guessing.

## Database
`plugins/PinAuth/accounts.db`, table `accounts(uuid PK, username, pin_hash, status, registered_at, last_login, failed_count, lockout_until)`,
index on `username COLLATE NOCASE`. Schema version is stored in `PRAGMA user_version`; migrations run automatically at startup
(a database from a newer version is refused).

## Project layout
`src/main/java/com/kodeshi/pinauth/` – `PinAuthPlugin`, `AuthManager` (all logic), `PinGui`, `Database`, `PinHasher`, `Settings`,
listeners (`ConnectionListener`, `GuiListener`, `ProtectionListener`), commands (`AdminCommands`, `PlayerCommands`), `FloodgateHook`.
See `TESTING.md` for the test checklist.

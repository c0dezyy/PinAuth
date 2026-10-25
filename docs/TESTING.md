# Testing checklist

Use a Paper 1.21.x test server with Geyser + Floodgate, a Java client and a Bedrock client (or a second account).
Set `max-login-attempts: 4`, `lockout-duration-seconds: 60`, and `login-timeout-seconds: 20` for the relevant tests.

| # | Scenario | Steps | Expected |
|---|----------|-------|----------|
| 1 | New Java player registers | Join with a new Java account; enter 6 digits, OK; repeat digits, OK | Welcome + keypad titled "Kodeshi SMP \| Register"; after matching confirmation you are logged in and can move |
| 1b | Mismatch / too short | Enter different PINs; press OK with 3 digits | Clear error, restart; "PIN must be 6 digits" |
| 2 | Returning Java player logs in | Rejoin; enter correct PIN | Keypad titled "Kodeshi SMP \| Login"; logged in |
| 3 | New Bedrock player registers | Same as 1 from Bedrock | Same chest keypad works by tapping; `/pinstatus` shows Edition BEDROCK |
| 4 | Returning Bedrock player logs in | Same as 2 from Bedrock | Logged in |
| 5 | Four wrong PINs | Enter wrong PIN 4 times | 3, 2, 1 attempts remaining, then kick "Too many incorrect PIN attempts. You have been disconnected." |
| 6 | Login timeout | Set `login-timeout-seconds: 20`, reload, join, wait | Kicked after ~20 s; with `0` never kicked |
| 7 | Closing the GUI | Press Esc repeatedly | Keypad re-opens; after `max-gui-closes` you are kicked; `/pinlogin` re-opens it when `reopen-gui-on-close: false` |
| 8 | Reconnect after failed attempts | After test 5 rejoin immediately; wait out the lockout; rejoin | Refused with remaining time; allowed after lockout; counter reset on success |
| 8b | Counter persists | Enter 2 wrong PINs, disconnect, rejoin | Next wrong PIN shows 1 attempt remaining |
| 9 | Admin resets online player | `/pinreset <name>` while they are playing | Their session is revoked, registration keypad opens, they cannot play until a new PIN is confirmed; console logs the reset |
| 10 | Admin resets offline player | `/clearpin <name>` while offline, then they join | Registration keypad appears on join |
| 11 | Unauthorized reset | Non-op runs `/pinreset other` | "You do not have permission"; nothing changes |
| 12 | Server restart | Register, stop server, start, rejoin | Login (not registration) works; `accounts.db` and `secret.key` persisted |
| 13 | Duplicate usernames | Java `Steve` and Bedrock `.Steve` | Two separate accounts/PINs; `/pinreset Steve` and `/pinreset .Steve` affect only their own |
| 14a | Database error | Make `accounts.db` unreadable / remove the SQLite driver, restart | SEVERE log; all joins refused (fail closed) |
| 14b | Config reload | Edit titles, messages, attempts to invalid values, `/pinauth reload` | Valid changes apply, invalid values warn and use defaults, no restart needed |
| 15 | Bypass attempts | While at the keypad: move, break/place, hit mobs, drop/pick up, chat, `/tp`, `/gamemode`, shift-click/number-keys/drag in the keypad, press `E` | Everything cancelled; only keypad buttons work |
| 16 | Change PIN | Logged in: `/changepin`; wrong old PIN; then correct old, new, confirm | Wrong old PIN counts toward lockout; success message; old PIN no longer works |
| 17 | Duplicate session | Connect a second client with the same account while online | Second connection refused; first player not kicked |
| 18 | Floodgate missing | Remove Floodgate, restart | Startup warning; plugin still works |

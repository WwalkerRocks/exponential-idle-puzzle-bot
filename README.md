# Puzzle Bot for Exponential Idle

An Android app that plays the **15-Puzzle** minigame in [Exponential Idle](https://play.google.com/store/apps/details?id=com.conicgames.exponentialidle)
for you: it reads the board from the screen, solves it, taps the tiles, claims the stars and starts the next board.

> [!WARNING]
> **Read this before installing.**
> - **Unofficial.** Not made by, endorsed by, or affiliated with Conic Games (the makers of Exponential Idle) or Google.
> - **Use at your own risk.** Automating a game may break its terms of service. Your progress or account could be
>   affected. The authors take no responsibility for any consequences.
> - **Powerful permission.** This is an Accessibility Service: while it runs it reads the puzzle from
>   Exponential Idle's screen, sees which windows are open, can take screenshots, and taps the screen for you. Only install it if you trust the code. Building it yourself
>   from source is the safest option.
> - **Unsigned build.** The APK in Releases is signed with a debug key, not through Google Play. Android and
>   Play Protect will warn you when you install it.
> - **It can make mistakes.** It pauses when something covers the puzzle and stops when it is unsure, but don't
>   leave it running unattended for long.
> - Provided **"as is", without warranty of any kind**.

> [!NOTE]
> **AI-written code.** The code, tests and documentation in this repository were written by an AI
> (Claude, made by Anthropic, working through Claude Code) at the request of the repository owner, who tested
> it on their own phone. It has been tested on one device only. Please review it before relying on it.

## What it does

A small floating bar (the "island") appears over Exponential Idle. Press ▶ and it loops:

1. reads the board (3×3, 4×4 or 5×5, detected automatically) and the button under it,
2. computes a short solution,
3. taps the tiles (one tap slides a whole row or column segment, like in the game),
4. taps **Great! Claim N stars**, which also starts the next board, and goes again.

Exponential Idle exposes its puzzle tiles and buttons to Android's accessibility system, so the bot reads
the numbers directly. That's exact and fast. On Android 11–12, or if a game update changes this, it falls
back to reading screenshots.

**Hard (5×5) gives the most stars per minute.** The game raises its star rewards as you progress. The bot
reads the real reward from the claim button, so its ★/min stats are accurate. On the test phone
(1080×2340, Android 16) a Hard board takes about **0.37 s** once auto-tune settles, about 3,500★/min at 22★
per board. That breaks down as ~35 ms thinking, ~190 ms tapping, ~35 ms until the game shows "Claim", and
~115 ms to read the next board.

| Difficulty | Moves (solver) | Taps with multi-tile slides |
|---|---|---|
| Easy 3×3   | 22 (optimal)        | ~18 |
| Medium 4×4 | 53 (optimal)        | ~38 |
| **Hard 5×5** | ~127 (near-optimal) | **~87** |

## Install

Requires **Android 11 or newer**.

**Option A: download.** Get `PuzzleBot.apk` from the [Releases](../../releases) page and open it on your phone.
Allow your browser or file manager to install unknown apps. If Play Protect warns you, choose
**More details → Install anyway**. See the warning above.

**Option B: build it yourself** (see [Build from source](#build-from-source)), then install it over USB with
`adb install -r app/build/outputs/apk/release/app-release.apk`.

## Turn it on (one time)

1. Open **Puzzle Bot** from the app drawer.
2. Tap **Open Accessibility settings** → *Installed apps* (or *Downloaded apps*) → **Puzzle Bot** → turn it on.
3. **Android 13+:** if the switch is greyed out ("Restricted setting"), go back, tap **Open App info**,
   tap **⋮** (top right) → **Allow restricted settings**, then repeat step 2.
4. The Puzzle Bot screen should now say **✓ Service is ON**.

## Use it

1. Turning on Do Not Disturb is recommended, so notifications don't interrupt it.
2. Open Exponential Idle → Stars → Minigames → **15-Puzzle** → pick a difficulty.
3. Press **▶** on the island to start and **■** to stop. Drag the text to move the island; long-press it to open settings.
4. The first 4×4/5×5 run spends a few seconds building solver tables; after that they are cached.
5. **Stars per board** (settings screen): leave these at 0 to use the reward the game shows. Type a
   number only to override it.

### Safety features
- **Pauses when something covers the puzzle.** Before every group of taps it checks the window list. If a call
  screen, dialog, notification shade, keyboard, popup or another app is under any point it is about to tap, it
  stops tapping. It resumes once the screen has been clear for a moment and re-reads the board first. If it stays
  paused for 3 minutes, it stops.
- **Verifies every board.** After the last tap it waits for the game to show "Claim". If the board ends up
  unsolved, it re-solves from wherever the board ended up instead of guessing.
- **Never taps blind.** It only taps the claim/start button when the board reads as solved. If the board still
  looks solved right after a button tap, it looks again before tapping, so it never hits a new game's
  "I Give Up!" by accident.
- **Stops on anything unexpected**, for example if it can't find the puzzle or the taps have no effect. The island
  shows the reason.

### Speed settings (settings screen)
- **Auto-tune** (on by default): after clean boards the bot shortens the pause between taps, then the tap
  length, down to 1 ms each. If the game drops a tap, it undoes that step and stays there for the session.
- **Slide several tiles per tap** (on by default).
- **Taps per gesture** (default 20): use 1 if taps get lost even at slow speeds.

## Permissions and privacy

- The app requests **no Android permissions**, including **no internet access**. Nothing it sees leaves the phone.
- As an Accessibility Service it can:
  - read Exponential Idle's puzzle: the tile numbers, their positions, and the text of the button under the board;
  - take screenshots (only used when the puzzle can't be read directly);
  - perform taps;
  - see the list of open windows (their position and which app owns them), for the failsafe. It does **not** read
    the text or contents of other apps' windows.
- Nothing is stored except settings and stats. The only exception: if it can't read the board in screenshot mode,
  it saves that one screenshot to `Android/data/io.github.wwalkerrocks.puzzlebot/files/debug` for
  troubleshooting. This can be turned off in settings.

## How it works

- `core/TreeBoard.kt`: turns the tile views the game exposes (number and screen bounds) into a board, and reads
  the reward from the claim button. `BotService.readTree()` fetches them in a single request per board.
- `core/BoardReader.kt` (screenshot fallback): finds the grid from the thin tile-border lines, splits each tile into digit shapes,
  and matches them to templates. Every number appears exactly once, so it picks the best one-to-one
  assignment (Hungarian algorithm) instead of guessing digit by digit. Templates refine themselves after every
  verified solve.
- `core/Solver3.kt`: optimal 3×3 (IDA*).
- `core/Solver4.kt`: optimal 4×4 (IDA* with 5-5-5 additive pattern databases and a mirrored lookup).
- `core/Solver5.kt`: 5×5 solved as top row → left column, then the remaining 4×4 optimally. Among many short
  row/column solutions it keeps the one with the fewest taps, under a small time budget. It also tries
  column-first. Tapping starts while the 4×4 is still being solved.
- `core/Taps.kt`: turns moves into taps (straight runs merge into one multi-tile slide).
- `BotService.kt`: the accessibility service: screenshots, taps, island, failsafe, game loop, speed tuning.
- `MainActivity.kt`: setup screen, settings, stats and an on-phone benchmark.

## Build from source

Requires JDK 17 and the Android SDK (platform 35). Android Studio works, or from the command line:

```bash
./gradlew :app:assembleRelease :app:testReleaseUnitTest
```

On Windows, `build.bat` and `install-to-phone.bat` wrap the same steps (set `JAVA_HOME` to a JDK 17 first).

Unit tests check the solvers on random boards. They also check the reader on real screenshots of the minigame
(cropped to the puzzle) and on 150 shuffled versions of them. The screenshots are in `tools/screens`. To rebuild
the fixtures: `py tools/make_test_screens.py`. To re-measure the digit templates:
`./gradlew :app:testReleaseUnitTest --tests "*TemplateGenerator*" -DregenTemplates=true`.

## License

No license has been chosen yet, so default copyright applies. Ask the repository owner before reusing the code.

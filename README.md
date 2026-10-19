# Puzzle Bot for Exponential Idle

An Android app that plays the **Arrow Puzzle**, **Torus Puzzle** and **15-Puzzle** minigames in [Exponential Idle](https://play.google.com/store/apps/details?id=com.conicgames.exponentialidle)
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

A small floating bar (the "island") appears over Exponential Idle. Open any of the three minigames, press ▶, and it
loops: read the board, compute the taps, tap, claim the stars (which starts the next board), repeat. It
recognises which minigame and difficulty is open, including when you switch.

**Which one to run:** the game raises its star rewards as you progress, and the bot reads the real reward
from the claim button, so the stats screen shows true ★/min. On the test phone (1080×2340, Android 16):

| Minigame | Reward seen | Taps/swipes per board | Time per board | ★/min |
|---|---|---|---|---|
| **Arrow Puzzle Expert** | **93–97★** | ~58 taps | ~0.45 s | **~12,600** |
| Torus Puzzle Hard 6×6 | 56★ | ~115 swipes | ~0.40 s | ~8,400 |
| 15-Puzzle Hard 5×5 | 22★ | ~87 taps | ~0.37 s | ~3,500 |
| Arrow Puzzle Hard | 52★ | ~13 taps | | |
| Arrow Puzzle Easy / Torus Easy | 3★ | ~14 taps / ~6 swipes | | |

### Arrow Puzzle
Tapping a tile turns it and every touching tile one step clockwise: 90° on Easy (3×3) and Medium (4×4),
60° on Expert (hex board), and a flip on Hard (hex board, up/down only). So a board is a set of linear
equations (mod 4, 6 or 2), solved exactly for the fewest taps. The game exposes where the tiles are but not
which way the arrows point, so the bot takes one screenshot of the board area per board to read them.

### Torus Puzzle
A swipe shifts a whole row or column by any number of cells, wrapping around (also known as Loopover). The
game exposes the numbers, so the board is read directly. 3×3 is solved optimally (fewest swipes) from a
table of all 181,440 positions. Bigger boards are solved row by row: each tile is put in place with a
4-swipe commutator (row move, column move, then both undone) that swaps exactly three tiles and leaves
finished rows untouched. The last two rows are filled column by column the same way. 6×6 takes ~115
swipes, and the game accepts them as fast as 1 ms each.

### 15-Puzzle
The game exposes every tile's number to Android's accessibility system, so the bot reads the board directly
(screenshots are a fallback). One tap slides a whole row or column segment, like in the game. Solutions:
Easy 3×3 ~18 taps (optimal), Medium 4×4 ~38 (optimal moves), Hard 5×5 ~87 (near-optimal).

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
2. Open Exponential Idle → Stars → Minigames → **Arrow Puzzle**, **Torus Puzzle** or **15-Puzzle** → pick a difficulty.
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
  - read Exponential Idle's puzzle: the tile numbers and positions, the minigame title, and the text of the
    button under the board;
  - take screenshots (one of the board area per Arrow Puzzle board, to see the arrows; and for the 15-Puzzle
    only when it can't be read directly). They're processed in memory and discarded;
  - perform taps;
  - see the list of open windows (their position and which app owns them), for the failsafe. It does **not** read
    the text or contents of other apps' windows.
- For troubleshooting, a computer connected over USB with debugging enabled can ask the bot to write the game's
  view tree to the phone's log (`adb shell am broadcast -a io.github.wwalkerrocks.puzzlebot.DUMP_TREE`).
  Other apps can't trigger this (it requires a permission only the adb shell has).
- Nothing is stored except settings and stats. The only exception: if it can't read the board in screenshot mode,
  it saves that one screenshot to `Android/data/io.github.wwalkerrocks.puzzlebot/files/debug` for
  troubleshooting. This can be turned off in settings.

## How it works

- `core/ArrowSolver.kt`: Arrow Puzzle as linear equations over Z₄/Z₆/Z₂ (row reduction with Euclid, since
  4 and 6 aren't prime). The Expert board has 3,888 solutions for any position, and the bot picks the one with
  the fewest taps. Per board shape the reduction and the list of "do-nothing" tap combinations are computed
  once, so each board solves in well under a millisecond.
- `core/ArrowReader.kt`: which way each chevron points, from the screenshot (its tip is narrow, its ends are
  wide).
- `core/TorusSolver.kt`: Torus Puzzle. Optimal table for 3×3, and commutator-based placement for larger
  boards (with a parity fix on even sizes so the last two tiles come out right).
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

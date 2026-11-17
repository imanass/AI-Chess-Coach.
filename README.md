# AI Chess Coach — Android project

A chess trainer that plays and teaches: an on-device Stockfish 19 engine, a
chess.com-style game review with move classification, a persistent play-style
profile that targets your recurring mistakes, local game history, chess.com
game import, and an optional AI coach (OpenAI-compatible, Google Gemini, or
Anthropic Claude). All engine analysis runs on-device and is free; the AI coach
is optional and only receives summary text.

## Implemented in this revision (v5)

### Play
- Interactive legal chessboard: legal-move highlighting, last-move and check
  highlights, tap-to-move, board flip, undo, promotion picker.
- **Computer opponent**: on-device Stockfish 19 (UCI subprocess, `libstockfish.so`,
  downloaded at build time by CI) for Medium/Hard, a built-in
  iterative-deepening negamax engine for Easy and as fallback. GPL-3.0 applies
  via Stockfish.
- Play as White or Black, or pass-and-play two-player mode.

### Game review (chess.com-style, free)
- Every move is classified as **Brilliant / Great / Best / Excellent / Good /
  Book / Inaccuracy / Mistake / Blunder / Miss** by comparing it against the
  engine's best *and second-best* move (MultiPV 2) on a win-probability scale.
- Per-side accuracy percentages, a tag-count summary, a per-move evaluation
  graph coloured by move quality, and the best move shown for each mistake.
- "Book" moves are detected against a compact offline openings book
  (~90 named lines).
- Brilliant requires an engine-best move that sacrifices material while staying
  good; Great requires a clear gap to the second-best line; Miss marks thrown-away
  wins and missed mates.

### Play-style profile & targeted teaching
- Every reviewed game is folded into a persistent Room-backed profile:
  accuracy across reviewed games, tag counts, phase-by-phase (opening /
  middlegame / endgame) win-point loss, capture tendencies.
- Recurring mistake themes are detected on-device, e.g. *leaving pieces
  undefended* (moves refuted by a simple capture), *missing winning tactics*,
  *one-move blunders*, *risky captures*, *shaky openings*, *endgame accuracy*.
- The Progress tab shows your style label, accuracy, top tags and the drills to
  fix first — computed locally, always free.
- Optional AI: "Ask the coach for a plan" sends only the aggregated summary text
  to your configured provider and returns a one-week practice plan.

### chess.com import (free)
- Fetch your recent games via chess.com's public Published-Data API (no key) by
  username, import them into local history, and review them with the same
  on-device analysis.

### Coach
- Chat-style coach screen: explain last move, ideas in this position, review
  this game — providers: OpenAI (+ compatible endpoints), Google Gemini,
  Anthropic Claude. Keys stored in EncryptedSharedPreferences; requests go
  directly to the provider.

### Training
- 29 interactive lessons (Learn tab) with unlockable chapters and drills.
- Puzzle bank with rating, streaks and hints (Puzzles tab).

### Storage
- Room database v4: games, lesson progress, puzzle stats and the play-style
  profile, with migrations 1→2, 2→3 and 3→4 so existing installs upgrade
  without losing data.

### Build
- Launcher icon (adaptive, vector pawn), dark-first Material 3 theme.
- GitHub Actions workflow: unit tests + debug APK; it downloads Stockfish 19
  (arm64) and validates the ELF header before packing it into the APK.

## Important limitations
- The Stockfish binary is **not committed** to the repo; the CI workflow fetches
  it at build time. Locally cloned builds fall back to the built-in engine.
- Reviews run at fixed shallow depth (Stockfish depth 12 / built-in depth 4) so
  a full game finishes in seconds; classifications are estimates, not ground
  truth.
- API keys on a mobile device can be extracted from a compromised device; for a
  public release, proxy AI requests through a backend you control.

## Build using GitHub Actions
1. Push this project to a GitHub repository.
2. Open **Actions → Android APK**.
3. Select **Run workflow** (or push to `main`/`master`).
4. Download the `AI-Chess-Coach-debug-apk` artifact from the completed run.

The workflow uses JDK 17, Android SDK 35, and Gradle 8.9.

## Local build
Use Android Studio with JDK 17, Android SDK 35, and Gradle 8.9. Run:
`gradle :app:assembleDebug`

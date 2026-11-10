# AI Chess Coach — Android project

A chess trainer that plays and teaches: a built-in engine opponent, a full rules
implementation, local game history, and an optional AI coach (OpenAI-compatible,
Google Gemini, or Anthropic Claude) that explains your moves and reviews your games.

## Implemented in this revision

### Play
- Interactive legal chessboard: legal-move highlighting (dots for moves, rings for
  captures), last-move highlight, check highlight on the king, and tap-to-move.
- **Computer opponent** with three difficulty levels (Easy / Medium / Hard), powered by
  a built-in engine written for this app: iterative-deepening negamax with alpha-beta
  pruning, quiescence search, MVV-LVA move ordering, and material + piece-square
  evaluation. Original code — **no Stockfish binary is bundled**, so there are no GPL
  licensing obligations.
- Play as White or Black, or switch to two-player (pass-and-play) mode.
- Board flip, move undo (takes back the computer's reply too), and a live material
  evaluation badge (toggleable in Settings).
- Full promotion handling with a piece picker (queen / rook / bishop / knight).
- Proper SAN move recording with disambiguation, castling (`O-O`, `O-O-O`), promotion
  (`e8=Q`), and check (`+`) / checkmate (`#`) suffixes, displayed as a numbered move list.

### Coach
- Chat-style coach screen with quick actions: *Explain last move* (with the position
  before and after), *Ideas in this position*, and *Review this game* (structured
  what-went-well / key-moments / next-lesson review of the full move list).
- The coach prompt is explicit that it must never claim to have run Stockfish; the only
  automated evaluation it receives is the built-in analyzer's material/PST score.
- Providers: OpenAI (plus any OpenAI-compatible endpoint), Google Gemini, Anthropic
  Claude. Robust error parsing for each provider's error shapes, token limits on every
  request, and a cheap one-word connection test.

### Progress
- Real statistics from the local Room database: games played, moves recorded,
  vs-computer record (W-L-D), win rate, and an adaptive "current focus" tip.
- Saved-game browser: tap any finished game to read its move list, delete single games,
  or clear all history.

### Settings
- Provider / endpoint / model / API key, with a key-visibility toggle. Keys are stored
  in AndroidX EncryptedSharedPreferences on-device; requests go directly to the provider.
- Engine evaluation toggle. No analytics and no third-party tracking anywhere.

### Storage
- Room database v2 stores `mode` and `playerSide` per game, with a proper
  `Migration(1, 2)` so existing installs upgrade without losing data.
- Non-secret preferences (mode, difficulty, side, flip, evaluation) persist across
  restarts.

### Build
- Launcher icon (adaptive, vector pawn), dark-first Material 3 theme.
- A GitHub Actions workflow builds a debug APK and uploads it as an artifact.

## Important limitations
- This environment has no Android SDK, so the APK was **not built here**; use the
  GitHub Actions workflow (Actions → Android APK → Run workflow) or Android Studio.
- The built-in engine is deliberately modest (~1-second search). It plays legal,
  sensible chess but is far below Stockfish strength, and its evaluation badge is a
  heuristic, not an engine-grade assessment.
- Games are saved automatically when they end.
- API keys on a mobile device can be extracted from a compromised device; for a public
  app, proxy requests through a backend you control.

## Build using GitHub Actions
1. Push this project to a GitHub repository.
2. Open **Actions → Android APK**.
3. Select **Run workflow** (or push to `main`/`master`).
4. Download the `AI-Chess-Coach-debug-apk` artifact from the completed run.

The workflow uses JDK 17, Android SDK 35, and Gradle 8.9.

## Local build
Use Android Studio with JDK 17, Android SDK 35, and Gradle 8.9. Run:
`gradle :app:assembleDebug`

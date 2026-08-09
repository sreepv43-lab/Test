# Ludo Circuit

A single-file, offline ludo variant played on a 5×5 circuit. Open `index.html`
in any modern browser — no server, no build step, no network access required.

![board](https://img.shields.io/badge/offline-single%20file-2F6FED) ![deps](https://img.shields.io/badge/dependencies-none-2FB673)

## The board

```
             BLUE
   ┌────┬────┬────┬────┬────┐
   │    │    │ B  │    │    │   outer ring · 16 squares · clockwise
   ├────┼────┼────┼────┼────┤   inner ring ·  8 squares · anti-clockwise
   │    │    │ b  │    │    │   centre     ·  home
Y  │ Y  │ y  │ ✦  │ r  │ R  │  R
E  ├────┼────┼────┼────┼────┤  E
L  │    │    │ g  │    │    │  D
   ├────┼────┼────┼────┼────┤
   │    │    │ G  │    │    │
   └────┴────┴────┴────┴────┘
             GREEN
```

Every one of the 25 squares is on the track. Capitals are the four starting
blocks; lower case are the inner-ring squares each colour steps into the centre
from, each facing its own side of the board and its own triangle of the centre.

A colour starts on its block, laps the outer ring, turns inwards from the square
just before its own block, travels the inner ring the whole way round, and steps
into the centre.

## Rules

- **Getting out** — roll a 6 (or a 1, if enabled) to move a token from its yard
  onto its coloured block. A 6 earns a bonus roll; three sixes in a row forfeits
  the turn.
- **Safe squares** — the four coloured blocks. Nobody can be cut while standing
  on one, and any number of tokens may share one.
- **Cutting** — land on a lone opponent anywhere else and it goes back to its
  yard. The cutter gets a bonus roll.
- **Blockades** — two or more of your own tokens on a square cannot be landed
  on by opponents, though they may pass.
- **Into the inner ring** — a token may only turn inwards once it has
  **completed a full lap** *and* **cut an opponent at least once**. A token that
  has earned its cut carries a white star; one that has not simply laps again.
- **Home** — follow the inner ring round to the square facing the centre, then
  step in with an exact roll. Cutting works in the inner ring too.
- **Winning** — first player to bring the required number of tokens home.

## Options

Set on the *New game* screen and remembered in `localStorage`:

| Option | Values | Default |
| --- | --- | --- |
| Seats | human / computer / empty, per colour (min. 2 playing) | Blue human, rest computer |
| Tokens to win | 1 · Sprint, 2 · Standard, 4 · Full | 2 |
| Cut credit | per token (strict) / per player (relaxed) | per token |
| Release from yard | 1 or 6 / 6 only | 1 or 6 |

## Controls

`Space` roll · `1`–`4` pick a token · `R` rules · `N` new game. Hovering a
movable token previews where it lands.

## Layout

```
index.html          the whole game: board, rules engine, computer player, UI
test/simulate.mjs   head-less soak test for the rules engine
```

`index.html` holds two scripts. `<script id="ludo-rules">` is a pure,
DOM-free rules engine (geometry, legal moves, capture, win detection and the
computer player); `<script id="ludo-ui">` is everything visual. The test
extracts the first block and runs it under Node, so game logic can be verified
without a browser:

```bash
node test/simulate.mjs 300
```

It plays computer-vs-computer games across four configurations and asserts the
invariants after every move — token counts, that the inner ring is never
entered without a lap and a cut, and that opponents never share an unsafe
square — then reports pace, cut counts and the win spread per seat.

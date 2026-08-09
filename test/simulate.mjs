/**
 * Head-less soak test for the Ludo Circuit rules engine.
 *
 * Extracts the <script id="ludo-rules"> block from index.html, evaluates it
 * without a DOM, then plays full computer-vs-computer games while asserting
 * the invariants the board is supposed to guarantee.
 *
 *   node test/simulate.mjs [games]
 */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const html = readFileSync(join(here, '..', 'index.html'), 'utf8');
const src = html.match(/<script id="ludo-rules">([\s\S]*?)<\/script>/);
if (!src) throw new Error('rules script not found in index.html');
new Function(src[1])();
const R = globalThis.LudoRules;

/* deterministic PRNG so failures are reproducible */
function rng(seed) {
  let s = seed >>> 0;
  return () => ((s = (s * 1664525 + 1013904223) >>> 0) / 4294967296);
}

let failures = 0;
function check(cond, msg, ctx) {
  if (cond) return;
  failures++;
  if (failures < 6) console.error('  FAIL: ' + msg + (ctx ? '  ' + JSON.stringify(ctx) : ''));
}

function invariants(g) {
  // token bookkeeping
  for (const c of g.colors) {
    const mine = g.tokens.filter(t => t.color === c);
    check(mine.length === 4, 'each colour keeps 4 tokens', { c, n: mine.length });
    for (const t of mine) {
      check(['yard', 'board', 'home'].includes(t.state), 'valid state', t);
      check(t.idx >= 0 && t.idx <= R.HOME, 'index in range', t);
      check(t.state !== 'home' || t.idx === R.HOME, 'home means centre', t);
      // the inner ring is gated on the cut credit
      if (t.idx >= R.INNER_START && t.state !== 'yard') {
        const credit = g.cutMode === 'player' ? g.playerCut[t.color] : t.cut;
        check(credit, 'inner ring requires a cut', { id: t.id, idx: t.idx });
        check(t.laps >= 1, 'inner ring requires a completed lap', { id: t.id, laps: t.laps });
      }
    }
  }
  // no two opponents share an unsafe square
  const byCell = new Map();
  for (const t of g.tokens) {
    if (t.state !== 'board') continue;
    const cell = R.cellAt(t.color, t.idx);
    const k = cell.join();
    if (!byCell.has(k)) byCell.set(k, []);
    byCell.get(k).push(t);
  }
  for (const [k, arr] of byCell) {
    const colors = new Set(arr.map(t => t.color));
    if (colors.size > 1) {
      check(R.isSafe(k.split(',').map(Number)), 'opponents only share safe squares', { k, colors: [...colors] });
    }
  }
}

function playGame(seed, opts) {
  const rand = rng(seed);
  const g = R.createGame(opts);
  let turns = 0, captures = 0, homes = 0;
  const MAX = 8000;

  while (g.phase !== 'over' && turns < MAX) {
    turns++;
    let again = true, sixes = 0;
    while (again && g.phase !== 'over') {
      again = false;
      const v = 1 + Math.floor(rand() * 6);
      g.dice = v;
      sixes = v === 6 ? sixes + 1 : 0;
      if (sixes >= 3) break;
      const moves = R.legalMoves(g, v);
      if (!moves.length) break;
      const m = R.pickCpuMove(g, moves, rand);
      check(!!m, 'a move is chosen when moves exist');
      // the plan must agree with the walked path
      check(m.path[m.path.length - 1] === m.dest, 'path ends at dest', m);
      check(m.path.length === v || m.release, 'path length equals the roll', { n: m.path.length, v });
      const res = R.applyMove(g, m);
      captures += res.captures.length;
      if (res.home) homes++;
      invariants(g);
      again = res.extra;
    }
    if (g.phase !== 'over') R.endTurn(g);
  }
  return { finished: g.phase === 'over', winner: g.winner, turns, captures, homes };
}

const games = Number(process.argv[2] || 300);
const configs = [
  { name: '4p · 2 home · per-token cut', seats: { blue: 'cpu', red: 'cpu', green: 'cpu', yellow: 'cpu' }, tokensToWin: 2, cutMode: 'token', releaseOnOne: true },
  { name: '4p · 4 home · per-token cut', seats: { blue: 'cpu', red: 'cpu', green: 'cpu', yellow: 'cpu' }, tokensToWin: 4, cutMode: 'token', releaseOnOne: true },
  { name: '4p · 2 home · per-player cut', seats: { blue: 'cpu', red: 'cpu', green: 'cpu', yellow: 'cpu' }, tokensToWin: 2, cutMode: 'player', releaseOnOne: true },
  { name: '2p · 2 home · 6 only', seats: { blue: 'cpu', red: 'off', green: 'cpu', yellow: 'off' }, tokensToWin: 2, cutMode: 'token', releaseOnOne: false },
];

console.log(`Ludo Circuit — ${games} games per configuration\n`);
for (const cfg of configs) {
  const { name, ...opts } = cfg;
  let turns = 0, caps = 0, unfinished = 0;
  const wins = {};
  for (let i = 0; i < games; i++) {
    const r = playGame(i * 7919 + 13, opts);
    turns += r.turns; caps += r.captures;
    if (!r.finished) unfinished++;
    else wins[r.winner] = (wins[r.winner] || 0) + 1;
  }
  const spread = Object.entries(wins).map(([c, n]) => `${c} ${(100 * n / games).toFixed(0)}%`).join('  ');
  console.log(name.padEnd(30) +
    ` turns ${(turns / games).toFixed(0).padStart(4)}` +
    ` | cuts ${(caps / games).toFixed(1).padStart(5)}` +
    ` | stalled ${unfinished}` +
    ` | ${spread}`);
  if (unfinished) failures++;
}

console.log(failures ? `\n${failures} invariant failure(s)` : '\nAll invariants held.');
process.exit(failures ? 1 : 0);

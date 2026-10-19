package sonar;

import java.util.*;

/** Independent straight-ship search for the current international rules:
 * distinct boats do not touch, including diagonally.
 * A timed-out search is UNKNOWN, never a proof of an empty solution set. */
final class Strategy {
    static final class Placement {
        final int length;
        final long lo, hi, haloLo, haloHi;

        Placement(int n, int length, long lo, long hi) {
            this.length = length;
            this.lo = lo;
            this.hi = hi;
            long a = 0, b = 0;
            for (int i = 0; i < n * n; i++) {
                if (contains(i)) {
                    for (int r = Math.max(0, i / n - 1); r <= Math.min(n - 1, i / n + 1); r++) {
                        for (int c = Math.max(0, i % n - 1); c <= Math.min(n - 1, i % n + 1); c++) {
                            int cell = r * n + c;
                            if (cell < 64) {
                                a |= 1L << cell;
                            } else {
                                b |= 1L << (cell - 64);
                            }
                        }
                    }
                }
            }
            haloLo = a;
            haloHi = b;
        }

        boolean contains(int cell) {
            return cell < 64 ? (lo & (1L << cell)) != 0 : (hi & (1L << (cell - 64))) != 0;
        }
    }

    final int n;
    final List<Placement> placements = new ArrayList<>();
    final int[] counts = new int[6];
    final long requiredLo, requiredHi, lockedLo, lockedHi;
    long answerLo, answerHi, deadline;
    boolean timedOut;

    // completed is the current online sidebar. The caller has already attacked
    // every known ship cell; offline discoveries alone do not imply completion.
    Strategy(int n, int[] cells, int[] fleet, List<int[]> sunk, int[] completed) {
        this.n = n;
        long waterLo = 0, waterHi = 0, hitLo = 0, hitHi = 0;
        for (int i = 0; i < cells.length; i++) {
            if (cells[i] == Vision.WATER) {
                if (i < 64) {
                    waterLo |= 1L << i;
                } else {
                    waterHi |= 1L << (i - 64);
                }
            }
            if (cells[i] == Vision.SHIP) {
                if (i < 64) {
                    hitLo |= 1L << i;
                } else {
                    hitHi |= 1L << (i - 64);
                }
            }
        }
        requiredLo = hitLo;
        requiredHi = hitHi;
        for (int length : fleet) {
            counts[length]++;
        }
        long fixedLo = 0, fixedHi = 0;
        int[] fixedCount = new int[6];
        for (int[] ship : sunk) {
            if (--counts[ship.length] < 0) {
                throw new Board.ReviewNeeded("画面完整潜艇数量超过舰队数量");
            }
            long lo = 0, hi = 0;
            fixedCount[ship.length]++;
            for (int cell : ship) {
                if (cell < 64) {
                    lo |= 1L << cell;
                } else {
                    hi |= 1L << (cell - 64);
                }
            }
            Placement p = new Placement(n, ship.length, lo, hi);
            if ((p.haloLo & fixedLo) != 0 || (p.haloHi & fixedHi) != 0) {
                throw new Board.ReviewNeeded("画面完整潜艇违反互不接触规则");
            }
            fixedLo |= lo;
            fixedHi |= hi;
        }
        lockedLo = fixedLo;
        lockedHi = fixedHi;
        if ((lockedLo & waterLo) != 0 || (lockedHi & waterHi) != 0) {
            throw new Board.ReviewNeeded("画面完整潜艇与水格观察矛盾");
        }
        for (int len = 2; len <= 5; len++) {
            if (counts[len] > 0) {
                for (int direction = 0; direction < 2; direction++) {
                    for (int row = 0; row < n; row++) {
                        for (int col = 0; col < n; col++) {
                            if (row + (direction == 0 ? len : 1) > n || col + (direction == 1 ? len : 1) > n) {
                                continue;
                            }
                            long lo = 0, hi = 0;
                            for (int k = 0; k < len; k++) {
                                int i = (row + (direction == 0 ? k : 0)) * n + col + (direction == 1 ? k : 0);
                                if (i < 64) {
                                    lo |= 1L << i;
                                } else {
                                    hi |= 1L << (i - 64);
                                }
                            }
                            Placement p = new Placement(n, len, lo, hi);
                            // A boat made only of confirmed online hits would be sunk. If the
                            // sidebar has no other completed boat of this length, it cannot fit
                            // here, even though the local hit/water geometry alone allows it.
                            if (completed != null && completed[len] == fixedCount[len]
                                    && (lo & ~hitLo) == 0 && (hi & ~hitHi) == 0) {
                                continue;
                            }
                            // Any observed hit beside this boat must belong to this same boat.
                            if ((lo & waterLo) == 0 && (hi & waterHi) == 0
                                    && (p.haloLo & (lockedLo | (requiredLo & ~lo))) == 0
                                    && (p.haloHi & (lockedHi | (requiredHi & ~hi))) == 0) {
                                placements.add(p);
                            }
                        }
                    }
                }
            }
        }
    }

    // 1 = a solution; 0 = exhaustively impossible; -1 = time budget exhausted.
    int solve(int excluded, long milliseconds) {
        timedOut = false;
        deadline = System.nanoTime() + milliseconds * 1000000;
        if (excluded >= 0 && (excluded < 64
                ? (lockedLo & (1L << excluded)) != 0
                : (lockedHi & (1L << (excluded - 64))) != 0)) {
            return 0;
        }
        boolean found = search(counts.clone(), lockedLo, lockedHi, excluded);
        return found ? 1 : timedOut ? -1 : 0;
    }

    boolean search(int[] left, long lo, long hi, int excluded) {
        if (System.nanoTime() > deadline) {
            timedOut = true;
            return false;
        }
        int remaining = 0;
        for (int l = 2; l <= 5; l++) {
            remaining += left[l] * l;
        }
        long needLo = requiredLo & ~lo, needHi = requiredHi & ~hi;
        if (remaining == 0) {
            if (needLo == 0 && needHi == 0) {
                answerLo = lo;
                answerHi = hi;
                return true;
            }
            return false;
        }
        if (Long.bitCount(needLo) + Long.bitCount(needHi) > remaining) {
            return false;
        }
        List<Placement> choices = null;
        int best = Integer.MAX_VALUE;
        for (int cell = 0; cell < n * n; cell++) {
            if (cell < 64 ? (needLo & (1L << cell)) != 0 : (needHi & (1L << (cell - 64))) != 0) {
                List<Placement> cover = new ArrayList<>();
                for (Placement p : placements) {
                    if (left[p.length] > 0 && (p.haloLo & lo) == 0 && (p.haloHi & hi) == 0
                            && (excluded < 0 || !p.contains(excluded)) && p.contains(cell)) {
                        cover.add(p);
                    }
                }
                if (cover.size() < best) {
                    choices = cover;
                    best = cover.size();
                    if (best == 0) {
                        return false;
                    }
                }
            }
        }
        if (choices == null) {
            for (int len = 2; len <= 5; len++) {
                if (left[len] > 0) {
                    List<Placement> cover = new ArrayList<>();
                    for (Placement p : placements) {
                        if (p.length == len && (p.haloLo & lo) == 0 && (p.haloHi & hi) == 0
                                && (excluded < 0 || !p.contains(excluded))) {
                            cover.add(p);
                        }
                    }
                    if (cover.size() < best) {
                        choices = cover;
                        best = cover.size();
                    }
                }
            }
        }
        if (choices == null) {
            return false;
        }
        for (Placement p : choices) {
            left[p.length]--;
            boolean found = search(left, lo | p.lo, hi | p.hi, excluded);
            left[p.length]++;
            if (found) {
                return true;
            }
            if (timedOut) {
                return false;
            }
        }
        return false;
    }

    int certain(int[] known) throws Exception {
        int valid = solve(-1, 60);
        if (valid == 0) {
            throw new Board.ReviewNeeded("棋盘观察与舰队数量矛盾；没有继续投弹");
        }
        if (valid < 0) {
            return -1;
        }
        long lo = answerLo, hi = answerHi;
        // A cell is certain only when excluding it is exhaustively impossible.
        // A timed-out exclusion search does not establish certainty.
        for (int i = 0; i < known.length; i++) {
            if (known[i] == Vision.UNKNOWN && (i < 64 ? (lo & (1L << i)) != 0 : (hi & (1L << (i - 64))) != 0)) {
                if (solve(i, 20) == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    int scout(int[] known) {
        double[] scores = new double[known.length], continuation = new double[known.length];
        List<Placement> possible = new ArrayList<>();
        // A locally fitting boat may prevent the rest of the observed fleet from
        // fitting. Only an exhaustive failure rules it out; timeout is unknown.
        for (Placement p : placements) {
            int[] left = counts.clone();
            left[p.length]--;
            timedOut = false;
            deadline = System.nanoTime() + 20000000;
            if (search(left, lockedLo | p.lo, lockedHi | p.hi, -1) || timedOut) {
                possible.add(p);
            }
        }
        for (Placement p : possible) {
            int hits = Long.bitCount(p.lo & requiredLo) + Long.bitCount(p.hi & requiredHi);
            for (int i = 0; i < known.length; i++) {
                if (known[i] == Vision.UNKNOWN && p.contains(i)) {
                    double score = (1 + hits * hits) / (double)p.length;
                    scores[i] += score;
                    if (hits > 0) {
                        continuation[i] += score;
                    }
                }
            }
        }
        int best = -1;
        // Finish resolving an observed boat before hunting in unrelated space.
        // Unanchored placements otherwise overwhelm a single useful hit merely
        // because there are many more ways to place completely unseen boats.
        for (int i = 0; i < known.length; i++) {
            if (known[i] == Vision.UNKNOWN && scores[i] > 0
                    && (best < 0 || continuation[i] > continuation[best]
                    || continuation[i] == continuation[best] && scores[i] > scores[best])) {
                best = i;
            }
        }
        return finishScout(best, known, possible, scores);
    }

    // Evaluate the chosen boat's remaining red queries. Candidate placements
    // have equal prior weight; blue hits and completion feedback cost no red.
    int finishScout(int chosen, int[] known, List<Placement> possible, double[] scores) {
        if (chosen < 0) {
            return chosen;
        }
        int anchor = -1;
        for (int cell = 0; cell < known.length; cell++) {
            if (known[cell] == Vision.SHIP && Math.abs(cell / n - chosen / n) + Math.abs(cell % n - chosen % n) == 1) {
                anchor = cell;
                break;
            }
        }
        if (anchor < 0) {
            return chosen;
        }
        List<Placement> positions = new ArrayList<>();
        for (Placement p : possible) {
            if (p.contains(anchor)) {
                positions.add(p);
            }
        }
        if (positions.size() < 2) {
            return chosen;
        }
        ProbePlan plan = new ProbePlan(positions, known.length);
        long all = (1L << positions.size()) - 1;
        int best = chosen, bestCost = Integer.MAX_VALUE;
        for (int cell = 0; cell < known.length; cell++) {
            if (known[cell] == Vision.UNKNOWN) {
                long hit = all & plan.hit[cell], water = all & ~plan.hit[cell];
                if (hit == 0 || water == 0 || (all & plan.halo[cell]) != all) {
                    continue;
                }
                int cost = positions.size() + plan.cost(hit) + plan.cost(water);
                if (cost < bestCost || cost == bestCost && (cell == chosen || best != chosen && scores[cell] > scores[best])) {
                    best = cell;
                    bestCost = cost;
                }
            }
        }
        return best;
    }

    static final class ProbePlan {
        final List<Placement> positions;
        final long[] hit, halo;
        final Map<Long, Integer> costs = new HashMap<>();

        ProbePlan(List<Placement> positions, int cells) {
            this.positions = positions;
            hit = new long[cells];
            halo = new long[cells];
            for (int k = 0; k < positions.size(); k++) {
                Placement p = positions.get(k);
                long bit = 1L << k;
                for (int cell = 0; cell < cells; cell++) {
                    if (p.contains(cell)) {
                        hit[cell] |= bit;
                    }
                    if (cell < 64 ? (p.haloLo & (1L << cell)) != 0 : (p.haloHi & (1L << (cell - 64))) != 0) {
                        halo[cell] |= bit;
                    }
                }
            }
        }

        // Sum of remaining red-query counts over equally weighted candidates,
        // not a worst-case count; each query adds one for every active candidate.
        int cost(long candidates) {
            long active = candidates;
            // Common boat cells can be attacked with certainty. If their extent
            // matches one hypothesis, sinking finishes it; not sinking removes it.
            while (active != 0) {
                long lo = -1L, hi = -1L;
                for (int k = 0; k < positions.size(); k++) {
                    if ((active & (1L << k)) != 0) {
                        lo &= positions.get(k).lo;
                        hi &= positions.get(k).hi;
                    }
                }
                long rest = active;
                for (int k = 0; k < positions.size(); k++) {
                    if ((active & (1L << k)) != 0) {
                        Placement p = positions.get(k);
                        if (p.lo == lo && p.hi == hi) {
                            rest &= ~(1L << k);
                        }
                    }
                }
                if (rest == active) {
                    break;
                }
                active = rest;
            }
            if (active == 0) {
                return 0;
            }
            Integer cached = costs.get(active);
            if (cached != null) {
                return cached;
            }
            int best = Integer.MAX_VALUE;
            for (int cell = 0; cell < hit.length; cell++) {
                long yes = active & hit[cell], no = active & ~hit[cell];
                // Outside this boat's guaranteed halo, a hit could be a different
                // boat. Such a cell is not a binary test of these hypotheses.
                if (yes == 0 || no == 0 || (active & halo[cell]) != active) {
                    continue;
                }
                int value = Long.bitCount(active) + cost(yes) + cost(no);
                if (value < best) {
                    best = value;
                }
            }
            if (best == Integer.MAX_VALUE) {
                throw new IllegalStateException("Boat candidates cannot be separated");
            }
            costs.put(active, best);
            return best;
        }
    }
}

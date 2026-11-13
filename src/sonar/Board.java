package sonar;

import java.util.*;

/** Facts from one account/level only: information and saved attacks are separate. */
final class Board {
    Vision.Grid grid;
    // known merges visual/scouting/deduction knowledge, not necessarily saved online.
    // fleet contains one length per observed boat.
    final int[] known, fleet;
    // Online sidebar completion counts, indexed by boat length.
    int[] completed;
    // Marks online-visible cells, including the initial view, not shots this run.
    final boolean[] saved;
    // A sent action survives changes to its visual classification.
    final boolean[] attacked, suspect;
    int blue, red, level;
    // Last reviewed visual result of a resolved shot, separate from deductions.
    // Results can be corrected by reread(); auxiliary water reads cannot revise them.
    final int[] confirmed;
    // Whole visible hulls can also be observed offline; not server acknowledgments.
    final List<int[]> sunk = new ArrayList<>();
    final List<int[]> doubtfulSunk = new ArrayList<>();

    Board(Vision.Observation initial) {
        grid = initial.grid;
        fleet = initial.fleet.clone();
        known = initial.cells.clone();
        saved = new boolean[known.length];
        attacked = new boolean[known.length];
        suspect = new boolean[known.length];
        confirmed = new int[known.length];
        for (int i = 0; i < known.length; i++) {
            saved[i] = known[i] != Vision.UNKNOWN;
            suspect[i] = weak(initial, i);
        }
        blue = initial.blue;
        red = initial.red;
        level = initial.level;
        completed = initial.completed.clone();
        rememberSunk(initial);
    }

    void scout(Vision.Observation observation) {
        grid = observation.grid;
        // Previously opened cells can be occluded by a raised completed submarine.
        // New scouting information comes only from newly revealed covered cells.
        for (int i = 0; i < known.length; i++) {
            if (!attacked[i] && known[i] == Vision.UNKNOWN && observation.cells[i] != Vision.UNKNOWN) {
                known[i] = observation.cells[i];
                suspect[i] = weak(observation, i);
            }
        }
        rememberSunk(observation);
    }

    void online(Vision.Observation observation) {
        grid = observation.grid;
        for (int i = 0; i < known.length; i++) {
            if (observation.cells[i] != Vision.UNKNOWN) {
                if (attacked[i] && (confirmed[i] == Vision.UNKNOWN
                    || confirmed[i] != observation.cells[i])) {
                    known[i] = confirmed[i] = Vision.UNKNOWN;
                    suspect[i] = true;
                    continue;
                }
                // Reconcile visible cells without promoting a suspended shot
                // label. Hidden, unexecuted scouting knowledge remains separate.
                if (saved[i] || known[i] == Vision.UNKNOWN) {
                    known[i] = observation.cells[i];
                    suspect[i] = weak(observation, i) || attacked[i] && suspect[i];
                }
                if (known[i] == observation.cells[i]) {
                    saved[i] = true;
                }
            }
        }
        blue = observation.blue;
        red = observation.red;
        completed = observation.completed.clone();
        rememberSunk(observation);
    }

    static boolean weak(Vision.Observation observation, int cell) {
        return observation.suspect != null && observation.suspect[cell];
    }

    void recordAttack(int cell) {
        attacked[cell] = true;
    }

    void acceptShot(int cell, int label, boolean reliable) {
        attacked[cell] = saved[cell] = true;
        known[cell] = confirmed[cell] = label;
        suspect[cell] = label == Vision.UNKNOWN || !reliable;
    }

    boolean[] unavailable() {
        boolean[] result = new boolean[known.length];
        for (int i = 0; i < result.length; i++) result[i] = saved[i] || attacked[i];
        return result;
    }

    boolean reconcileShots(Vision.Observation fresh) {
        if (!fresh.page.equals("board") || fresh.grid.n != grid.n || !Arrays.equals(fresh.fleet, fleet)) return false;
        boolean changed = false;
        for (int i = 0; i < known.length; i++) {
            if ((attacked[i] || confirmed[i] != Vision.UNKNOWN) && confirmed[i] != Vision.UNKNOWN
                && !fresh.covered[i] && fresh.cells[i] != Vision.UNKNOWN && !weak(fresh, i)
                && fresh.cells[i] != confirmed[i]) {
                attacked[i] = saved[i] = true;
                known[i] = confirmed[i] = Vision.UNKNOWN;
                suspect[i] = true;
                changed = true;
            }
        }
        if (changed) rememberSunk(fresh);
        return changed;
    }

    void rememberSunk(Vision.Observation observation) {
        // A visible whole submarine identifies its placement even offline.
        // This is layout knowledge only; it does not mark its attacks as saved.
        List<int[]> candidates = new ArrayList<>();
        for (int[] ship : sunk) if (agreesWithShots(ship) && !unresolvedHull(ship)) candidates.add(ship);
        for (int[] ship : doubtfulSunk) {
            boolean exists = false;
            for (int[] old : candidates) if (Arrays.equals(old, ship)) exists = true;
            if (!exists && agreesWithShots(ship) && !unresolvedHull(ship)) candidates.add(ship);
        }
        for (int[] ship : observation.sunk) {
            if (!agreesWithShots(ship) || unresolvedHull(ship)) {
                continue;
            }
            boolean exists = false;
            for (int[] old : candidates) {
                if (Arrays.equals(old, ship)) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                candidates.add(ship.clone());
            }
        }
        boolean[] strong = new boolean[candidates.size()], rejected = new boolean[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) strong[i] = strongHull(candidates.get(i));
        for (int i = 0; i < candidates.size(); i++) for (int j = i + 1; j < candidates.size(); j++) {
            if (!touches(candidates.get(i), candidates.get(j))) continue;
            if (strong[i] && strong[j])
                throw new ReviewNeeded("已确认完整潜艇违反互不接触规则");
            if (!strong[i]) rejected[i] = true;
            if (!strong[j]) rejected[j] = true;
        }
        int[] limits = new int[6], counts = new int[6];
        for (int length : fleet) limits[length]++;
        for (int i = 0; i < candidates.size(); i++) if (!rejected[i]) counts[candidates.get(i).length]++;
        for (int i = 0; i < candidates.size(); i++) {
            if (counts[candidates.get(i).length] > limits[candidates.get(i).length] && !strong[i]) rejected[i] = true;
        }
        sunk.clear();
        doubtfulSunk.clear();
        for (int i = 0; i < candidates.size(); i++) {
            int[] ship = candidates.get(i);
            if (!rejected[i]) sunk.add(ship);
            else {
                doubtfulSunk.add(ship);
                for (int cell : ship) if (confirmed[cell] != Vision.SHIP || suspect[cell]) suspect[cell] = true;
            }
        }
    }

    boolean strongHull(int[] ship) {
        if (completed[ship.length] <= 0) return false;
        for (int cell : ship) if (confirmed[cell] != Vision.SHIP || suspect[cell]) return false;
        return true;
    }

    boolean unresolvedHull(int[] ship) {
        for (int cell : ship) if (attacked[cell] && confirmed[cell] == Vision.UNKNOWN) return true;
        return false;
    }

    boolean touches(int[] first, int[] second) {
        for (int a : first) for (int b : second)
            if (Math.abs(a / grid.n - b / grid.n) <= 1 && Math.abs(a % grid.n - b % grid.n) <= 1) return true;
        return false;
    }

    boolean agreesWithShots(int[] ship) {
        // A visual hull fit is weaker evidence than an acknowledged shot. A
        // missed bow must not turn one confirmed five-cell boat into a second
        // four-cell boat. Use the same non-touching rule as the solver.
        for (int cell = 0; cell < confirmed.length; cell++) {
            if (confirmed[cell] != Vision.UNKNOWN && !suspect[cell]
                || known[cell] == Vision.WATER && !suspect[cell]) {
                boolean inside = false, adjacent = false;
                for (int part : ship) {
                    inside |= cell == part;
                    adjacent |= Math.abs(cell / grid.n - part / grid.n) <= 1
                        && Math.abs(cell % grid.n - part % grid.n) <= 1;
                }
                if ((confirmed[cell] == Vision.WATER || known[cell] == Vision.WATER) && inside
                    || confirmed[cell] == Vision.SHIP && !inside && adjacent) {
                    return false;
                }
            }
        }
        return true;
    }

    void completedWater(Vision.Observation observation) {
        // Read the opened water around a visibly completed, already saved boat.
        // Other impact-frame cells remain untouched; no spacing rule is assumed.
        int n = grid.n;
        for (int[] ship : observation.sunk) {
            boolean accepted = false;
            for (int[] locked : sunk) if (Arrays.equals(ship, locked)) accepted = true;
            if (!accepted || !agreesWithShots(ship)) {
                continue;
            }
            boolean committed = true;
            for (int cell : ship) {
                committed &= saved[cell] && known[cell] == Vision.SHIP && !suspect[cell];
            }
            if (!committed) {
                continue;
            }
            for (int cell : ship) {
                for (int r = Math.max(0, cell / n - 1); r <= Math.min(n - 1, cell / n + 1); r++) {
                    for (int c = Math.max(0, cell % n - 1); c <= Math.min(n - 1, cell % n + 1); c++) {
                        // As in online(), clear water corrects an earlier saved read
                        // obscured by a raised hull, while retaining unexecuted scouts.
                        int i = r * n + c;
                        if (!attacked[i] && confirmed[i] == Vision.UNKNOWN && (known[i] == Vision.UNKNOWN || saved[i])
                            && observation.cells[i] == Vision.WATER) {
                            known[i] = Vision.WATER;
                            saved[i] = true;
                            suspect[i] = weak(observation, i);
                        }
                    }
                }
            }
        }
    }

    boolean refreshCompleted(Vision.Observation observation) {
        // The hit can be acknowledged before the raised hull becomes readable.
        // Reuse the same guarded update on the existing pre-scout frame.
        int[] previous = known.clone();
        List<int[]> previousSunk = new ArrayList<>(sunk);
        rememberSunk(observation);
        completedWater(observation);
        return !Arrays.equals(previous, known) || !previousSunk.equals(sunk);
    }

    static final class ReviewNeeded extends RuntimeException {
        ReviewNeeded(String message) {
            super(message);
        }
    }

    Board reread(Vision.Observation observation) {
        if (!observation.page.equals("board") || observation.grid.n != grid.n
            || !Arrays.equals(observation.fleet, fleet)) {
            throw new ReviewNeeded("复核画面尺寸或舰队与本关不一致");
        }
        Board candidate = reviewCandidate(observation, -1);
        int valid = consistent(candidate);
        if (valid == 0) throw new ReviewNeeded("复核后仍未确认棋盘约束一致");
        if (valid < 0) throw new ReviewNeeded("复核求解尚未完成，不据此更改旧投弹结论");
        // Conflicting shot labels stay unknown unless all remaining constraints
        // admit exactly one label. Repeated model output is not a new action.
        long end = System.nanoTime() + 60000000;
        for (int i = 0; i < known.length; i++) {
            if (!candidate.attacked[i] || candidate.confirmed[i] != Vision.UNKNOWN && !candidate.suspect[i]) continue;
            int water = consistentLabel(candidate, i, Vision.WATER, end);
            int ship = consistentLabel(candidate, i, Vision.SHIP, end);
            if (water == 1 && ship == 0 || water == 0 && ship == 1) {
                candidate.known[i] = candidate.confirmed[i] = ship == 1 ? Vision.SHIP : Vision.WATER;
                candidate.suspect[i] = false;
            }
        }
        candidate.rememberSunk(observation);
        return candidate;
    }

    Board reviewCandidate(Vision.Observation observation, int revised) {
        // Build a candidate, leaving the live state untouched until validation.
        // Hull constraints come from this fresh image, not accumulated old fits.
        Board candidate = new Board(observation);
        candidate.blue = blue;
        candidate.red = red;
        candidate.level = level;
        for (int i = 0; i < known.length; i++) {
            candidate.attacked[i] = attacked[i] || confirmed[i] != Vision.UNKNOWN;
            candidate.confirmed[i] = confirmed[i];
            if (candidate.attacked[i]) {
                if (observation.covered[i]) {
                    throw new ReviewNeeded("已确认投弹格在复核画面中仍有盖板");
                }
                candidate.saved[i] = true;
                if (confirmed[i] == Vision.UNKNOWN || observation.cells[i] != Vision.UNKNOWN
                    && observation.cells[i] != confirmed[i]) {
                    candidate.known[i] = candidate.confirmed[i] = Vision.UNKNOWN;
                    candidate.suspect[i] = true;
                } else {
                    candidate.known[i] = confirmed[i];
                    candidate.suspect[i] = suspect[i] || weak(observation, i);
                }
            }
        }
        // This is a fault review, not normal online reconciliation. A covered
        // cell cannot confirm old offline knowledge: copying it here resurrects
        // the very misread that caused the contradiction. Keep current visible
        // evidence and acknowledged shots; the normal strategy can rediscover
        // what is hidden. Ordinary online() still retains scouting knowledge.
        candidate.sunk.clear();
        candidate.doubtfulSunk.clear();
        for (int[] ship : sunk) if (strongHull(ship)) candidate.sunk.add(ship.clone());
        for (int[] ship : doubtfulSunk) candidate.doubtfulSunk.add(ship.clone());
        candidate.rememberSunk(observation);
        return candidate;
    }

    static int consistentLabel(Board candidate, int cell, int label, long end) {
        long remaining = (end - System.nanoTime()) / 1000000;
        if (remaining <= 0) return -1;
        int[] labels = candidate.known.clone();
        boolean[] removed = new boolean[labels.length];
        for (int i = 0; i < labels.length; i++) if (i != cell && candidate.suspect[i]) {
            labels[i] = Vision.UNKNOWN;removed[i] = true;
        }
        labels[cell] = label;
        try {
            return new Strategy(candidate.grid.n, labels, candidate.fleet,
                Strategy.independentHulls(candidate.grid.n, candidate.sunk, removed), null).solve(-1, remaining);
        } catch (ReviewNeeded contradiction) {
            return 0;
        }
    }

    static int consistent(Board candidate) {
        try {
            return candidate.solveConsistent(60);
        } catch (ReviewNeeded contradiction) {
            return 0;
        }
    }

    Strategy strategy() {
        try {
            return new Strategy(grid.n, known, fleet, sunk, allKnownShipsSaved() ? completed : null);
        } catch (ReviewNeeded contradiction) {
            int[] relaxed = known.clone();boolean[] removed = new boolean[known.length];boolean any = false;
            for (int i = 0; i < known.length; i++) if (suspect[i] && known[i] != Vision.UNKNOWN) {
                relaxed[i] = Vision.UNKNOWN;removed[i] = true;any = true;
            }
            if (!any) throw contradiction;
            Strategy fallback = new Strategy(grid.n, relaxed, fleet,
                Strategy.independentHulls(grid.n, sunk, removed), null);
            fallback.relaxedInput = true;
            return fallback;
        }
    }

    int solveConsistent(long milliseconds) {
        long end = System.nanoTime() + milliseconds * 1000000;
        Strategy current = strategy();
        int valid = current.solve(-1, milliseconds);
        if (valid == 0) {
            Strategy relaxed = current.withoutWeak(known, suspect);
            long remaining = (end - System.nanoTime()) / 1000000;
            if (relaxed != null) return remaining > 0 ? relaxed.solve(-1, remaining) : -1;
        }
        return valid;
    }

    int attack() {
        for (int i = 0; i < known.length; i++) {
            if (known[i] == Vision.SHIP && !saved[i] && !attacked[i] && !suspect[i]) {
                return i;
            }
        }
        return -1;
    }

    boolean allKnownShipsSaved() {
        for (int i = 0; i < known.length; i++) if (known[i] == Vision.SHIP && !saved[i]) return false;
        return true;
    }

    int remainingShipCells() {
        int remaining = 0;
        for (int length : fleet) remaining += length;
        for (int i = 0; i < known.length; i++) {
            if (known[i] == Vision.SHIP && saved[i] && !suspect[i]) remaining--;
        }
        return remaining;
    }

    boolean lastShipCellScout(int cell) {
        return cell >= 0 && cell < known.length && (known[cell] == Vision.UNKNOWN || suspect[cell])
            && !saved[cell] && !attacked[cell] && attack() < 0 && remainingShipCells() == 1;
    }

    String text() {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < known.length; i++) {
            s.append(known[i] == Vision.SHIP ? (saved[i] ? '#' : '+')
                : known[i] == Vision.WATER ? '~' : '?');
            if ((i + 1) % grid.n == 0) {
                s.append('\n');
            }
        }
        return s.toString();
    }
}

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
    int blue, red, level;
    // Last reviewed visual result of a resolved shot, separate from deductions.
    // Results can be corrected by reread(); auxiliary water reads cannot revise them.
    final int[] confirmed;
    // Whole visible hulls can also be observed offline; not server acknowledgments.
    final List<int[]> sunk = new ArrayList<>();

    Board(Vision.Observation initial) {
        grid = initial.grid;
        fleet = initial.fleet.clone();
        known = initial.cells.clone();
        saved = new boolean[known.length];
        confirmed = new int[known.length];
        for (int i = 0; i < known.length; i++) {
            saved[i] = known[i] != Vision.UNKNOWN;
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
            if (known[i] == Vision.UNKNOWN && observation.cells[i] != Vision.UNKNOWN) {
                known[i] = observation.cells[i];
            }
        }
        rememberSunk(observation);
    }

    void online(Vision.Observation observation) {
        grid = observation.grid;
        for (int i = 0; i < known.length; i++) {
            if (observation.cells[i] != Vision.UNKNOWN) {
                // Re-observe executed cells after reconnecting. A later clear water
                // result must replace an earlier impact-frame ship classification.
                // Unexecuted scouting knowledge remains separate from online marks.
                if (saved[i] || known[i] == Vision.UNKNOWN) {
                    known[i] = observation.cells[i];
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

    void rememberSunk(Vision.Observation observation) {
        // A visible whole submarine identifies its placement even offline.
        // This is layout knowledge only; it does not mark its attacks as saved.
        sunk.removeIf(ship -> !agreesWithShots(ship));
        for (int[] ship : observation.sunk) {
            if (!agreesWithShots(ship)) {
                continue;
            }
            boolean exists = false;
            for (int[] old : sunk) {
                if (Arrays.equals(old, ship)) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                sunk.add(ship.clone());
            }
        }
    }

    boolean agreesWithShots(int[] ship) {
        // A visual hull fit is weaker evidence than an acknowledged shot. A
        // missed bow must not turn one confirmed five-cell boat into a second
        // four-cell boat. Use the same non-touching rule as the solver.
        for (int cell = 0; cell < confirmed.length; cell++) {
            if (confirmed[cell] != Vision.UNKNOWN) {
                boolean inside = false, adjacent = false;
                for (int part : ship) {
                    inside |= cell == part;
                    adjacent |= Math.abs(cell / grid.n - part / grid.n) <= 1
                        && Math.abs(cell % grid.n - part % grid.n) <= 1;
                }
                if (confirmed[cell] == Vision.WATER && inside
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
            if (!agreesWithShots(ship)) {
                continue;
            }
            boolean committed = true;
            for (int cell : ship) {
                committed &= saved[cell] && known[cell] == Vision.SHIP;
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
                        if (confirmed[i] == Vision.UNKNOWN && (known[i] == Vision.UNKNOWN || saved[i])
                            && observation.cells[i] == Vision.WATER) {
                            known[i] = Vision.WATER;
                            saved[i] = true;
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
        Board original = reviewCandidate(observation, -1);
        int valid = consistent(original);
        if (valid == 1) {
            return original;
        }
        if (valid < 0) {
            throw new ReviewNeeded("复核求解尚未完成，不据此更改旧投弹结论");
        }
        // A visual shot result is revisable, but neither recency nor a repeated
        // model prediction alone proves it wrong. Test the currently visible
        // conflicting labels against all other shots and the unchanged fleet.
        // Accept a single uniquely supported revision; never guess a new label.
        Board revision = null;
        for (int i = 0; i < known.length; i++) {
            if (confirmed[i] != Vision.UNKNOWN && observation.cells[i] != Vision.UNKNOWN
                && observation.cells[i] != confirmed[i]) {
                Board candidate = reviewCandidate(observation, i);
                int result = consistent(candidate);
                if (result < 0) {
                    throw new ReviewNeeded("旧投弹结论复核求解尚未完成");
                }
                if (result == 1) {
                    if (revision != null) {
                        throw new ReviewNeeded("旧投弹结论存在多种解释，尚不能确认修正");
                    }
                    revision = candidate;
                }
            }
        }
        if (revision != null) {
            return revision;
        }
        throw new ReviewNeeded("复核后仍未确认棋盘约束一致");
    }

    Board reviewCandidate(Vision.Observation observation, int revised) {
        // Build a candidate, leaving the live state untouched until validation.
        // Hull constraints come from this fresh image, not accumulated old fits.
        Board candidate = new Board(observation);
        candidate.blue = blue;
        candidate.red = red;
        candidate.level = level;
        for (int i = 0; i < known.length; i++) {
            candidate.confirmed[i] = i == revised ? observation.cells[i] : confirmed[i];
            if (confirmed[i] != Vision.UNKNOWN) {
                if (observation.covered[i]) {
                    throw new ReviewNeeded("已确认投弹格在复核画面中仍有盖板");
                }
                candidate.known[i] = candidate.confirmed[i];
                candidate.saved[i] = true;
            }
        }
        // This is a fault review, not normal online reconciliation. A covered
        // cell cannot confirm old offline knowledge: copying it here resurrects
        // the very misread that caused the contradiction. Keep current visible
        // evidence and acknowledged shots; the normal strategy can rediscover
        // what is hidden. Ordinary online() still retains scouting knowledge.
        candidate.sunk.clear();
        candidate.rememberSunk(observation);
        return candidate;
    }

    static int consistent(Board candidate) {
        try {
            return new Strategy(candidate.grid.n, candidate.known, candidate.fleet, candidate.sunk,
                candidate.attack() < 0 ? candidate.completed : null).solve(-1, 60);
        } catch (ReviewNeeded contradiction) {
            return 0;
        }
    }

    int attack() {
        for (int i = 0; i < known.length; i++) {
            if (known[i] == Vision.SHIP && !saved[i]) {
                return i;
            }
        }
        return -1;
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

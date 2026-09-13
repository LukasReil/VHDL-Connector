package vhdlconnector.gui;

import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.TreeMap;

/**
 * Rectilinear (orthogonal) one-to-many router.
 *
 * <p>Computes a route from a single {@code source} to every destination such that
 * <ul>
 *   <li>every segment is strictly horizontal or vertical,</li>
 *   <li>the first segment leaving the source and the last segment entering a destination are
 *       always <em>horizontal</em>,</li>
 *   <li>no segment crosses the interior of an obstacle (touching an obstacle border is allowed),</li>
 *   <li>every vertex lies inside {@code bounds}, and</li>
 *   <li>routes to different destinations share as much of their path as possible.</li>
 * </ul>
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li><b>Escape / Hanan grid.</b> The search space is reduced to the cross product of the
 *       "interesting" x-coordinates (source, destinations, obstacle borders, bounds) and the
 *       corresponding y-coordinates. Every rectilinear shortest path in a rectangle-obstacle
 *       scene can be drawn on this grid, so nothing is lost by discretising this way. Grid size
 *       is O(f&sup2;) in the number of features, independent of the coordinate magnitudes.</li>
 *   <li><b>Bend-aware Dijkstra.</b> Search states are {@code (vertex, arrival axis)} pairs. Each
 *       change of axis adds {@link #BEND_PENALTY_RATIO} &times; (width + height) to the cost.
 *       Without this, the enormous number of equal-length staircase paths would be broken
 *       arbitrarily and the result would look like noise.</li>
 *   <li><b>Sharing by edge discounting.</b> Destinations are processed nearest-first. Edges already
 *       occupied by a previous route cost only {@link #REUSE_DISCOUNT} of their length, which pulls
 *       later searches onto the existing trunk. The discount is deliberately non-zero: with free
 *       edges the search happily detours along the entire tree, producing longer, uglier routes at
 *       no cost benefit.</li>
 *   <li><b>Tree canonicalisation.</b> Discounting alone does not <i>guarantee</i> a tree. Each new
 *       path is therefore spliced into an explicit parent-pointer tree at the last vertex that is
 *       already part of the tree; the returned route is the tree path down to that junction plus the
 *       new branch. This makes shared parts literally identical vertex sequences and rules out
 *       cycles or paths that leave and re-enter the trunk.</li>
 *   <li><b>Horizontal stubs.</b> The search may only leave the source horizontally and may only
 *       enter the goal horizontally, which fixes the orientation of the first and last segment of
 *       every branch. Because a branch is spliced onto the tree, the first segment of a route is
 *       the tree's first edge out of the source &mdash; also horizontal, since every branch leaves
 *       the source that way. To keep the <i>last</i> segment horizontal as well, destinations are
 *       treated as <b>terminals</b>: no route may pass through a destination vertex, only end
 *       there. Otherwise a destination lying on an earlier route's trunk would be an interior tree
 *       node whose incoming edge was chosen by that other route, and could be vertical.</li>
 * </ol>
 *
 * <h2>Quality</h2>
 * The optimum ("share as much as possible") is an obstacle-avoiding rectilinear Steiner minimal
 * tree, which is NP-hard. This is a Prim-like greedy heuristic: fast, deterministic, and typically
 * within a few percent of a hand-drawn result for schematic/diagram-sized inputs.
 *
 * <h2>Complexity</h2>
 * With {@code m} obstacles and {@code d} destinations the grid has {@code V = O((m + d)^2)}
 * vertices. Grid construction costs {@code O(V * m)} (naive obstacle test per edge), each route
 * costs {@code O(V log V)}. For a few hundred obstacles this is milliseconds; beyond that, replace
 * the per-edge obstacle scan with a sweep line or an interval tree.
 *
 * <h2>Memory across calls</h2>
 * An instance remembers the segments it has already drawn and charges later calls extra for
 * running along them, so routes from <em>different</em> sources tend not to be drawn on top of one
 * another. Only collinear overlap is penalised; a perpendicular crossing is left alone, because a
 * crossing is readable and an overlap is not. The penalty is soft: where a corridor is the only way
 * through, the route still takes it rather than failing. Call {@link #reset()} for a clean layout,
 * or use {@link #routeOnce} when no memory is wanted at all.
 *
 * <p>Because the memory is geometric (axis, line coordinate, span) rather than grid-based, it
 * survives the grid being rebuilt from different destinations, obstacles or bounds on every call.
 *
 * <p><b>An instance is stateful and therefore NOT thread-safe.</b> Use one instance per layout pass
 * and confine it to one thread, or use the stateless {@link #routeOnce} entry point.
 */
public final class OrthogonalRouter {

    /**
     * Cost multiplier for grid edges that are already part of the route tree. Must be
     * {@code > 0} (see class docs) and {@code < 1} for sharing to be attractive at all.
     */
    private static final double REUSE_DISCOUNT = 0.05;

    /**
     * Cost of a single 90&deg; bend, as a fraction of {@code bounds.width + bounds.height}.
     * Larger values yield fewer, longer straight runs; smaller values yield shorter but
     * more fidgety routes.
     */
    private static final double BEND_PENALTY_RATIO = 0.01;

    /**
     * Distance kept between a route and an obstacle border. {@code 0} lets routes hug obstacles
     * exactly; a positive value inserts grid lines that many units outside each obstacle instead.
     */
    private static final double CLEARANCE = 50.0;

    /**
     * Length of the horizontal stub at a terminal, as a fraction of the smaller side of
     * {@code bounds}. Because the first and last segment must be horizontal, the grid needs a
     * column immediately left and right of every terminal; without it the nearest turning point
     * could be the far side of the drawing and routes would take absurd detours to get out of the
     * terminal's own column. Only x-stubs are needed &mdash; the stub itself is horizontal, and its
     * endpoint already lies on the terminal's row.
     *
     * <p>If terminals are ports on nodes of a known size, prefer an absolute stub length (half the
     * port spacing, say) over this bounds-relative guess.
     */
    private static final double STUB_RATIO = 0.02;

    /**
     * Extra cost per earlier {@code route} call that already drew along a segment: an edge used by
     * {@code n} previous calls costs {@code (1 + CONGESTION_PENALTY * n)} times its length. Raise it
     * to spread routes out more aggressively, lower it to keep them short. It must stay finite, so
     * that a corridor which is the only connection is still used rather than reported unroutable.
     */
    private static final double CONGESTION_PENALTY = 5.0;

    /** Segments drawn by earlier calls on this instance. */
    private final RoutingMemory memory = new RoutingMemory();

    /** Creates a router with empty memory. */
    public OrthogonalRouter() {
        // nothing to initialise
    }

    /**
     * Forgets every previously routed segment, so the next call routes as if it were the first.
     * Use this when the whole diagram is re-routed from scratch; without it, stale segments from a
     * layout that no longer exists would keep pushing new routes around.
     */
    public void reset() {
        memory.clear();
    }

    /**
     * Routes without any memory of, or effect on, previous routes. Equivalent to
     * {@code new OrthogonalRouter().route(...)} and safe to call from multiple threads.
     *
     * @see #route(Point2D, List, List, Rectangle2D)
     */
    public static List<List<Point2D>> routeOnce(Point2D source,
                                                List<Point2D> destinations,
                                                List<Rectangle2D> obstacles,
                                                Rectangle2D bounds) {
        return new OrthogonalRouter().route(source, destinations, obstacles, bounds);
    }

    /**
     * Routes {@code source} to every entry of {@code destinations}.
     *
     * @param source       start point of all routes; must lie within {@code bounds}
     * @param destinations target points; may contain duplicates and {@code null} entries
     * @param obstacles    axis-aligned rectangles whose <em>interior</em> must not be crossed;
     *                     may be {@code null} or empty. Empty rectangles are ignored.
     * @param bounds       axis-aligned region that every route vertex must stay inside
     * @return one polyline per destination, in the same order and of the same size as
     *         {@code destinations}. Each polyline starts at {@code source} and ends at the
     *         destination, contains only axis-parallel segments, and has collinear intermediate
     *         vertices removed. Its first and last segment are horizontal; a destination lying
     *         directly above or below the source therefore gets a detour instead of a straight
     *         vertical drop. Routes sharing a trunk share an identical vertex prefix, so a
     *         renderer can deduplicate by comparing points. An <b>empty</b> list is returned for a
     *         destination that is {@code null}, outside {@code bounds}, inside an obstacle, or
     *         otherwise unreachable &mdash; which now also covers terminals that cannot be left or
     *         entered horizontally (for example a destination wedged between two obstacles that
     *         only leave a vertical gap). A destination equal to the source yields a single-point
     *         list, for which the constraint is vacuous.
     * @throws NullPointerException if {@code source}, {@code destinations} or {@code bounds} is null
     */
    public List<List<Point2D>> route(Point2D source,
                                     List<Point2D> destinations,
                                     List<Rectangle2D> obstacles,
                                     Rectangle2D bounds) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(destinations, "destinations");
        Objects.requireNonNull(bounds, "bounds");
        final List<Rectangle2D> obs = (obstacles != null) ? obstacles : Collections.emptyList();

        // Result slots are pre-filled with "unreachable" so every early exit below stays correct.
        final List<List<Point2D>> routes = new ArrayList<>(destinations.size());
        for (int i = 0; i < destinations.size(); i++) {
            routes.add(Collections.emptyList());
        }
        if (destinations.isEmpty() || bounds.isEmpty()) {
            return routes;
        }

        final Grid grid = Grid.build(source, destinations, obs, bounds, memory);
        final int start = grid.vertexAt(source);
        if (start < 0) {
            return routes; // source outside bounds: nothing is reachable
        }

        final double bendPenalty = BEND_PENALTY_RATIO * (bounds.getWidth() + bounds.getHeight());

        // Every destination must be a leaf of the route tree, otherwise its incoming edge would be
        // dictated by whichever route passes through it and could be vertical. This must happen for
        // all destinations before the first route is computed.
        for (final Point2D destination : destinations) {
            if (destination != null) {
                final int vertex = grid.vertexAt(destination);
                if (vertex >= 0 && vertex != start) {
                    grid.markTerminal(vertex);
                }
            }
        }

        // Explicit route tree over grid vertices. parent[root] == root marks the root.
        final int[] parent = new int[grid.vertexCount()];
        final boolean[] inTree = new boolean[grid.vertexCount()];
        Arrays.fill(parent, -1);
        parent[start] = start;
        inTree[start] = true;

        // Edges added by this call. Collected first and committed to the memory only at the end,
        // so that routes within this call still share freely: they leave the same source and are
        // meant to overlap. Only *later* calls pay the congestion penalty for them.
        final List<int[]> drawnEdges = new ArrayList<>();

        // Nearest first: the tree grows outward, so far destinations can latch onto an
        // already-established trunk instead of the other way round.
        for (final int k : orderByDistance(source, destinations)) {
            final Point2D destination = destinations.get(k);
            if (destination == null) {
                continue;
            }
            final int goal = grid.vertexAt(destination);
            if (goal < 0) {
                continue; // outside bounds
            }
            if (goal == start) {
                routes.set(k, Collections.singletonList(grid.pointOf(start)));
                continue;
            }

            final int[] path = grid.shortestPath(start, goal, bendPenalty);
            if (path == null) {
                continue; // enclosed by obstacles
            }

            // Splice at the LAST vertex already in the tree; anything before it is redundant
            // because the tree already provides a path from the source to that vertex.
            int junction = 0;
            for (int i = path.length - 1; i >= 0; i--) {
                if (inTree[path[i]]) {
                    junction = i;
                    break;
                }
            }
            for (int i = junction; i + 1 < path.length; i++) {
                if (inTree[path[i + 1]]) {
                    // Would re-parent a tree vertex and thus create a cycle. Unreachable given
                    // loop removal in the search; kept as a fail-fast, because the symptom of a
                    // cycle further down is an OutOfMemoryError in treePath, not a stack trace
                    // pointing here.
                    throw new IllegalStateException("route branch re-enters the route tree");
                }
                parent[path[i + 1]] = path[i];
                inTree[path[i + 1]] = true;
                grid.markUsed(path[i], path[i + 1]);
                drawnEdges.add(new int[] {path[i], path[i + 1]});
            }

            routes.set(k, grid.toPolyline(treePath(parent, start, goal)));
        }

        grid.commitToMemory(memory, drawnEdges);
        return routes;
    }

    /**
     * Returns destination indices ordered by ascending Manhattan distance from the source.
     * {@code null} destinations are sorted to the end.
     */
    private static int[] orderByDistance(Point2D source, List<Point2D> destinations) {
        final Integer[] order = new Integer[destinations.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        Arrays.sort(order, Comparator.comparingDouble(i -> {
            final Point2D p = destinations.get(i);
            return (p == null) ? Double.MAX_VALUE
                    : Math.abs(p.getX() - source.getX()) + Math.abs(p.getY() - source.getY());
        }));
        final int[] result = new int[order.length];
        for (int i = 0; i < order.length; i++) {
            result[i] = order[i];
        }
        return result;
    }

    /**
     * Walks the parent pointers from {@code node} up to {@code root} and returns the vertex
     * sequence in root-to-node order.
     */
    private static int[] treePath(int[] parent, int root, int node) {
        final List<Integer> reversed = new ArrayList<>();
        int current = node;
        // A tree path can never be longer than the number of vertices; the bound turns a corrupt
        // parent array into an immediate error instead of an unbounded, heap-eating walk.
        for (int guard = parent.length; current != root; guard--) {
            reversed.add(current);
            current = parent[current];
            if (current < 0 || guard <= 0) {
                throw new IllegalStateException("route tree is corrupt: cycle or missing parent");
            }
        }
        reversed.add(root);
        final int[] result = new int[reversed.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = reversed.get(result.length - 1 - i);
        }
        return result;
    }


    /**
     * Segments drawn by earlier calls, stored geometrically so they survive the grid being rebuilt.
     *
     * <p>Each axis keeps a map from the line's fixed coordinate to the spans occupied on that line.
     * Within one call the spans are merged, so a stored span contributes exactly one to the
     * congestion count and the count equals the number of distinct earlier calls drawing there.
     */
    private static final class RoutingMemory {

        /** y coordinate to occupied x spans. */
        private final NavigableMap<Double, List<double[]>> horizontal = new TreeMap<>();
        /** x coordinate to occupied y spans. */
        private final NavigableMap<Double, List<double[]>> vertical = new TreeMap<>();

        boolean isEmpty() {
            return horizontal.isEmpty() && vertical.isEmpty();
        }

        void clear() {
            horizontal.clear();
            vertical.clear();
        }

        /**
         * Number of earlier calls that drew along {@code [from, to]} on the given line. Only a
         * positive-length collinear overlap counts; touching at a point (a crossing, or two spans
         * meeting end to end) does not.
         */
        int countOverlaps(boolean horizontalAxis, double fixed, double from, double to, double tolerance) {
            final NavigableMap<Double, List<double[]>> lines = horizontalAxis ? horizontal : vertical;
            if (lines.isEmpty()) {
                return 0;
            }
            int count = 0;
            for (final List<double[]> spans : lines.subMap(fixed - tolerance, true,
                                                           fixed + tolerance, true).values()) {
                for (final double[] span : spans) {
                    if (Math.min(to, span[1]) - Math.max(from, span[0]) > tolerance) {
                        count++;
                    }
                }
            }
            return count;
        }

        /** Adds already-merged, disjoint spans for one line. */
        void addLine(boolean horizontalAxis, double fixed, List<double[]> spans, double tolerance) {
            final NavigableMap<Double, List<double[]>> lines = horizontalAxis ? horizontal : vertical;
            // Reuse a near-identical key so that lines from different calls, which may differ by a
            // rounding step, do not accumulate as separate entries.
            final NavigableMap<Double, List<double[]>> near =
                    lines.subMap(fixed - tolerance, true, fixed + tolerance, true);
            final double key = near.isEmpty() ? fixed : near.firstKey();
            lines.computeIfAbsent(key, k -> new ArrayList<>()).addAll(spans);
        }
    }

    /** Priority queue entry for {@link Grid#shortestPath}; distance is a snapshot (lazy deletion). */
    private record QueueEntry(int state, double distance) { }

    /**
     * The escape grid: sorted coordinate axes plus, for every potential edge, whether it is
     * passable and whether it is already used by the route tree.
     *
     * <p>Vertex {@code (ix, iy)} has index {@code iy * nx + ix}. The horizontal edge between
     * {@code (ix, iy)} and {@code (ix + 1, iy)} has index {@code iy * (nx - 1) + ix}; the vertical
     * edge between {@code (ix, iy)} and {@code (ix, iy + 1)} has index {@code iy * nx + ix}, which
     * conveniently equals the index of its lower vertex.
     */
    private static final class Grid {

        private final double[] xs;
        private final double[] ys;
        private final int nx;
        private final int ny;
        private final boolean[] horizontalFree;
        private final boolean[] verticalFree;
        private final boolean[] horizontalUsed;
        private final boolean[] verticalUsed;
        /** How many earlier calls drew along each edge; all zero when the memory is empty. */
        private final int[] horizontalCongestion;
        private final int[] verticalCongestion;
        /** Vertices that may only be entered as a search's own goal, never traversed. */
        private final boolean[] terminal;
        private final double tolerance;
        /**
         * Search scratch space, allocated once and reused for every destination. A Grid never
         * outlives the {@code route} call that created it, so this stays confined to one thread.
         */
        private double[] scratchDistance;
        private int[] scratchPrevious;
        private boolean[] scratchSettled;
        private PriorityQueue<QueueEntry> scratchQueue;

        private Grid(double[] xs, double[] ys, boolean[] horizontalFree, boolean[] verticalFree,
                     int[] horizontalCongestion, int[] verticalCongestion, double tolerance) {
            this.xs = xs;
            this.ys = ys;
            this.nx = xs.length;
            this.ny = ys.length;
            this.horizontalFree = horizontalFree;
            this.verticalFree = verticalFree;
            this.horizontalUsed = new boolean[horizontalFree.length];
            this.verticalUsed = new boolean[verticalFree.length];
            this.horizontalCongestion = horizontalCongestion;
            this.verticalCongestion = verticalCongestion;
            this.terminal = new boolean[xs.length * ys.length];
            this.tolerance = tolerance;
        }

        /** Builds the grid and pre-computes obstacle passability for every edge. */
        static Grid build(Point2D source, List<Point2D> destinations,
                          List<Rectangle2D> obstacles, Rectangle2D bounds,
                          RoutingMemory memory) {
            final double stub = STUB_RATIO * Math.min(bounds.getWidth(), bounds.getHeight());
            final List<Double> rawX = new ArrayList<>();
            final List<Double> rawY = new ArrayList<>();
            rawX.add(source.getX());
            rawX.add(source.getX() - stub);
            rawX.add(source.getX() + stub);
            rawY.add(source.getY());
            for (final Point2D p : destinations) {
                if (p != null) {
                    // The terminal's own column plus a turning column on either side, so a route
                    // can leave or enter horizontally without crossing the whole drawing.
                    rawX.add(p.getX());
                    rawX.add(p.getX() - stub);
                    rawX.add(p.getX() + stub);
                    rawY.add(p.getY());
                }
            }
            for (final Rectangle2D r : obstacles) {
                if (r == null || r.isEmpty()) {
                    continue;
                }
                // Grid lines just outside each obstacle border: these are the only x/y values a
                // shortest path ever needs in order to go around it.
                rawX.add(r.getMinX() - CLEARANCE);
                rawX.add(r.getMaxX() + CLEARANCE);
                rawY.add(r.getMinY() - CLEARANCE);
                rawY.add(r.getMaxY() + CLEARANCE);
            }

            final double[] xs = axis(rawX, bounds.getMinX(), bounds.getMaxX());
            final double[] ys = axis(rawY, bounds.getMinY(), bounds.getMaxY());
            final double tolerance = Math.max(toleranceOf(bounds.getMinX(), bounds.getMaxX()),
                                              toleranceOf(bounds.getMinY(), bounds.getMaxY()));
            final int nx = xs.length;
            final int ny = ys.length;

            final boolean remember = !memory.isEmpty();
            final boolean[] horizontalFree = new boolean[Math.max(0, nx - 1) * ny];
            final int[] horizontalCongestion = new int[horizontalFree.length];
            for (int iy = 0; iy < ny; iy++) {
                for (int ix = 0; ix + 1 < nx; ix++) {
                    final int edge = iy * (nx - 1) + ix;
                    horizontalFree[edge] =
                            !crossesObstacle(obstacles, ys[iy], xs[ix], xs[ix + 1], true, tolerance);
                    if (remember && horizontalFree[edge]) {
                        horizontalCongestion[edge] = memory.countOverlaps(
                                true, ys[iy], xs[ix], xs[ix + 1], tolerance);
                    }
                }
            }
            final boolean[] verticalFree = new boolean[nx * Math.max(0, ny - 1)];
            final int[] verticalCongestion = new int[verticalFree.length];
            for (int iy = 0; iy + 1 < ny; iy++) {
                for (int ix = 0; ix < nx; ix++) {
                    final int edge = iy * nx + ix;
                    verticalFree[edge] =
                            !crossesObstacle(obstacles, xs[ix], ys[iy], ys[iy + 1], false, tolerance);
                    if (remember && verticalFree[edge]) {
                        verticalCongestion[edge] = memory.countOverlaps(
                                false, xs[ix], ys[iy], ys[iy + 1], tolerance);
                    }
                }
            }
            return new Grid(xs, ys, horizontalFree, verticalFree,
                            horizontalCongestion, verticalCongestion, tolerance);
        }

        /**
         * Clamps the raw coordinates to {@code [lo, hi]}, adds the two bounds themselves, sorts and
         * removes near-duplicates. Coordinates outside the bounds are dropped, which is what makes
         * out-of-bounds points unroutable rather than silently relocated.
         */
        private static double[] axis(List<Double> raw, double lo, double hi) {
            final double tol = toleranceOf(lo, hi);
            final List<Double> values = new ArrayList<>(raw.size() + 2);
            values.add(lo);
            values.add(hi);
            for (final double v : raw) {
                if (v > lo && v < hi) {
                    values.add(v);
                }
            }
            Collections.sort(values);
            final double[] unique = new double[values.size()];
            int n = 0;
            for (final double v : values) {
                if (n == 0 || v - unique[n - 1] > tol) {
                    unique[n++] = v;
                }
            }
            return Arrays.copyOf(unique, n);
        }

        /** Scale-aware equality tolerance for one axis. */
        private static double toleranceOf(double lo, double hi) {
            return Math.max(1e-9, (hi - lo) * 1e-9);
        }

        /**
         * Tests whether an axis-parallel segment overlaps the <em>open interior</em> of any
         * obstacle. Segments running exactly along an obstacle border are therefore allowed.
         *
         * @param fixed      the constant coordinate (y for a horizontal segment, x otherwise)
         * @param from       lower value of the varying coordinate
         * @param to         upper value of the varying coordinate
         * @param horizontal {@code true} for a horizontal segment
         */
        private static boolean crossesObstacle(List<Rectangle2D> obstacles, double fixed,
                                               double from, double to, boolean horizontal,
                                               double tolerance) {
            for (final Rectangle2D r : obstacles) {
                if (r == null || r.isEmpty()) {
                    continue;
                }
                final double fixedMin = horizontal ? r.getMinY() : r.getMinX();
                final double fixedMax = horizontal ? r.getMaxY() : r.getMaxX();
                if (fixed <= fixedMin + tolerance || fixed >= fixedMax - tolerance) {
                    continue; // segment runs outside or exactly along the border
                }
                final double spanMin = horizontal ? r.getMinX() : r.getMinY();
                final double spanMax = horizontal ? r.getMaxX() : r.getMaxY();
                if (Math.min(to, spanMax) - Math.max(from, spanMin) > tolerance) {
                    return true; // positive-length overlap with the interior
                }
            }
            return false;
        }

        int vertexCount() {
            return nx * ny;
        }

        /** Maps a point to its grid vertex, or {@code -1} if it is not on the grid (out of bounds). */
        int vertexAt(Point2D p) {
            final int ix = indexOf(xs, p.getX(), tolerance);
            final int iy = indexOf(ys, p.getY(), tolerance);
            return (ix < 0 || iy < 0) ? -1 : iy * nx + ix;
        }

        private static int indexOf(double[] sorted, double value, double tolerance) {
            final int hit = Arrays.binarySearch(sorted, value);
            if (hit >= 0) {
                return hit;
            }
            final int insert = -hit - 1;
            if (insert < sorted.length && Math.abs(sorted[insert] - value) <= tolerance) {
                return insert;
            }
            if (insert > 0 && Math.abs(sorted[insert - 1] - value) <= tolerance) {
                return insert - 1;
            }
            return -1;
        }

        Point2D pointOf(int vertex) {
            return new Point2D.Double(xs[vertex % nx], ys[vertex / nx]);
        }

        /**
         * Declares a vertex to be a terminal: searches may end there but never route through it.
         * This is what keeps destinations leaves of the route tree.
         */
        void markTerminal(int vertex) {
            terminal[vertex] = true;
        }

        /** Marks the edge between two adjacent vertices as part of the route tree. */
        void markUsed(int a, int b) {
            final int lo = Math.min(a, b);
            final int hi = Math.max(a, b);
            if (hi - lo == 1 && nx > 1) { // nx == 1 means the only possible step is vertical
                horizontalUsed[(lo / nx) * (nx - 1) + (lo % nx)] = true;
            } else {
                verticalUsed[lo] = true;
            }
        }

        /**
         * Bend-penalised Dijkstra over states {@code (vertex, arrival axis)}, where axis 0 is
         * horizontal and 1 is vertical.
         *
         * <p>Three constraints are enforced structurally rather than by penalty, so they cannot be
         * traded away for a shorter path: only the horizontal start state is seeded and vertical
         * moves out of {@code start} are suppressed; only the horizontal goal state is accepted;
         * and terminal vertices other than {@code goal} may not be entered at all.
         *
         * @return the vertex sequence from {@code start} to {@code goal}, or {@code null} if the
         *         goal is unreachable under those constraints
         */
        int[] shortestPath(int start, int goal, double bendPenalty) {
            final int stateCount = 2 * vertexCount();
            if (scratchDistance == null) {
                scratchDistance = new double[stateCount];
                scratchPrevious = new int[stateCount];
                scratchSettled = new boolean[stateCount];
                scratchQueue = new PriorityQueue<>(Comparator.comparingDouble(QueueEntry::distance));
            }
            final double[] dist = scratchDistance;
            final int[] previous = scratchPrevious;
            final boolean[] settled = scratchSettled;
            final PriorityQueue<QueueEntry> queue = scratchQueue;
            Arrays.fill(dist, Double.POSITIVE_INFINITY);
            Arrays.fill(previous, -1);
            Arrays.fill(settled, false);
            queue.clear();

            final int goalState = 2 * goal; // horizontal arrival
            // Horizontal start state only: the first step is horizontal and thus never a bend.
            dist[2 * start] = 0.0;
            queue.add(new QueueEntry(2 * start, 0.0));

            while (!queue.isEmpty()) {
                final QueueEntry entry = queue.poll();
                final int state = entry.state();
                if (settled[state]) {
                    continue; // stale entry
                }
                settled[state] = true;

                final int vertex = state >> 1;
                final int axis = state & 1;
                if (state == goalState) {
                    break; // goal settled with a horizontal arrival: that is the answer
                }
                final int ix = vertex % nx;
                final int iy = vertex / nx;
                if (vertex == goal) {
                    // The goal is absorbing. Reached vertically it must NOT be expanded: stepping
                    // off and back in would satisfy the horizontal-arrival rule with a zero-width
                    // spur, and that spur is a cycle once the branch is spliced into the tree.
                    continue;
                }
                // Leaving the source vertically is forbidden, not merely expensive.
                final boolean verticalAllowed = (vertex != start);

                if (ix > 0) {
                    final int edge = iy * (nx - 1) + (ix - 1);
                    if (horizontalFree[edge] && mayEnter(vertex - 1, start, goal)) {
                        relax(dist, previous, queue, state, 2 * (vertex - 1),
                                stepCost(xs[ix] - xs[ix - 1], horizontalUsed[edge], horizontalCongestion[edge], axis, 0, bendPenalty));
                    }
                }
                if (ix + 1 < nx) {
                    final int edge = iy * (nx - 1) + ix;
                    if (horizontalFree[edge] && mayEnter(vertex + 1, start, goal)) {
                        relax(dist, previous, queue, state, 2 * (vertex + 1),
                                stepCost(xs[ix + 1] - xs[ix], horizontalUsed[edge], horizontalCongestion[edge], axis, 0, bendPenalty));
                    }
                }
                if (verticalAllowed && iy > 0) {
                    final int edge = (iy - 1) * nx + ix;
                    if (verticalFree[edge] && mayEnter(vertex - nx, start, goal)) {
                        relax(dist, previous, queue, state, 2 * (vertex - nx) + 1,
                                stepCost(ys[iy] - ys[iy - 1], verticalUsed[edge], verticalCongestion[edge], axis, 1, bendPenalty));
                    }
                }
                if (verticalAllowed && iy + 1 < ny) {
                    final int edge = iy * nx + ix;
                    if (verticalFree[edge] && mayEnter(vertex + nx, start, goal)) {
                        relax(dist, previous, queue, state, 2 * (vertex + nx) + 1,
                                stepCost(ys[iy + 1] - ys[iy], verticalUsed[edge], verticalCongestion[edge], axis, 1, bendPenalty));
                    }
                }
            }

            // Only a horizontal arrival counts, even if the goal could be reached vertically.
            if (Double.isInfinite(dist[goalState])) {
                return null;
            }

            final List<Integer> reversed = new ArrayList<>();
            for (int state = goalState; state != -1; state = previous[state]) {
                reversed.add(state >> 1);
            }
            final int[] path = new int[reversed.size()];
            for (int i = 0; i < path.length; i++) {
                path[i] = reversed.get(path.length - 1 - i);
            }
            return removeLoops(path);
        }

        /**
         * Removes vertex loops from a state path.
         *
         * <p>Dijkstra returns a simple path in the <em>state</em> graph, not in the vertex graph:
         * two states share a vertex, so a path may legitimately leave a vertex on one axis and
         * return to it on the other. Splicing such a path into the route tree would create a parent
         * cycle, so any repetition is cut out here. Shortcutting keeps the edge that followed the
         * <i>last</i> occurrence of the repeated vertex, so the result is still a connected sequence
         * of grid edges, and the first and last edge are untouched (start and goal cannot repeat).
         */
        private static int[] removeLoops(int[] path) {
            final Map<Integer, Integer> firstIndex = new HashMap<>();
            final int[] stack = new int[path.length];
            int top = 0;
            for (final int vertex : path) {
                final Integer seen = firstIndex.get(vertex);
                if (seen != null) {
                    for (int i = seen + 1; i < top; i++) {
                        firstIndex.remove(stack[i]);
                    }
                    top = seen + 1; // fall back to the earlier visit and continue from there
                } else {
                    firstIndex.put(vertex, top);
                    stack[top++] = vertex;
                }
            }
            return (top == path.length) ? path : Arrays.copyOf(stack, top);
        }

        /**
         * Whether a search may step onto a vertex. The start is never re-entered (that would put a
         * loop in front of the first segment) and a terminal may be entered only by the search that
         * is looking for it.
         */
        private boolean mayEnter(int vertex, int start, int goal) {
            if (vertex == start) {
                return false;
            }
            return vertex == goal || !terminal[vertex];
        }

        /**
         * Length after the reuse discount and the congestion surcharge, plus a bend penalty if the
         * axis changes. Discount and surcharge multiply: an edge on this call's own tree stays cheap
         * even where an earlier call drew, because the overlap has already been paid for once.
         */
        private static double stepCost(double length, boolean used, int congestion,
                                       int fromAxis, int toAxis, double bendPenalty) {
            final double weight = (used ? REUSE_DISCOUNT : 1.0) * (1.0 + CONGESTION_PENALTY * congestion);
            return length * weight + (fromAxis == toAxis ? 0.0 : bendPenalty);
        }

        private static void relax(double[] dist, int[] previous, PriorityQueue<QueueEntry> queue,
                                  int from, int to, double cost) {
            final double candidate = dist[from] + cost;
            if (candidate < dist[to]) {
                dist[to] = candidate;
                previous[to] = from;
                queue.add(new QueueEntry(to, candidate));
            }
        }


        /**
         * Records the edges drawn by this call in the memory. Collinear edges on the same line are
         * merged first: the tree uses each edge at most once, so one call contributes at most one
         * span to any point and {@link RoutingMemory#countOverlaps} counts distinct calls.
         */
        void commitToMemory(RoutingMemory memory, List<int[]> edges) {
            final Map<Integer, List<double[]>> rows = new HashMap<>();
            final Map<Integer, List<double[]>> columns = new HashMap<>();
            for (final int[] edge : edges) {
                final int lo = Math.min(edge[0], edge[1]);
                final int ix = lo % nx;
                final int iy = lo / nx;
                if (Math.abs(edge[0] - edge[1]) == 1 && nx > 1) {
                    rows.computeIfAbsent(iy, k -> new ArrayList<>())
                            .add(new double[] {xs[ix], xs[ix + 1]});
                } else {
                    columns.computeIfAbsent(ix, k -> new ArrayList<>())
                            .add(new double[] {ys[iy], ys[iy + 1]});
                }
            }
            for (final Map.Entry<Integer, List<double[]>> row : rows.entrySet()) {
                memory.addLine(true, ys[row.getKey()], mergeSpans(row.getValue(), tolerance), tolerance);
            }
            for (final Map.Entry<Integer, List<double[]>> column : columns.entrySet()) {
                memory.addLine(false, xs[column.getKey()], mergeSpans(column.getValue(), tolerance),
                        tolerance);
            }
        }

        /** Sorts spans and fuses overlapping or touching ones into maximal disjoint spans. */
        private static List<double[]> mergeSpans(List<double[]> spans, double tolerance) {
            spans.sort(Comparator.comparingDouble(span -> span[0]));
            final List<double[]> merged = new ArrayList<>();
            for (final double[] span : spans) {
                final double[] last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
                if (last != null && span[0] <= last[1] + tolerance) {
                    last[1] = Math.max(last[1], span[1]);
                } else {
                    merged.add(new double[] {span[0], span[1]});
                }
            }
            return merged;
        }

        /** Converts a vertex sequence into a polyline with collinear intermediate points removed. */
        List<Point2D> toPolyline(int[] vertices) {
            final List<Point2D> points = new ArrayList<>(vertices.length);
            for (final int v : vertices) {
                points.add(pointOf(v));
            }
            if (points.size() < 3) {
                return points;
            }
            final List<Point2D> simplified = new ArrayList<>(points.size());
            simplified.add(points.get(0));
            for (int i = 1; i + 1 < points.size(); i++) {
                final Point2D a = simplified.get(simplified.size() - 1);
                final Point2D b = points.get(i);
                final Point2D c = points.get(i + 1);
                // Exact comparison is safe: all coordinates come from the same axis arrays.
                final boolean collinear = (a.getX() == b.getX() && b.getX() == c.getX())
                        || (a.getY() == b.getY() && b.getY() == c.getY());
                if (!collinear) {
                    simplified.add(b);
                }
            }
            simplified.add(points.get(points.size() - 1));
            return simplified;
        }
    }
}
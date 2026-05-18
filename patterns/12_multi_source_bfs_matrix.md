# Pattern 12 — Multi-Source BFS + Matrix Traversal

---

## 1. Pattern Recognition Guide

**Keywords to look for:**
- "distance to nearest 0/gate/target"
- "rotting oranges", "spread from multiple sources"
- "walls and gates"
- "01 matrix" (distance to nearest 0)
- "Pacific Atlantic water flow" (reverse BFS from two edges)

**Multi-Source BFS triggers:**
- Multiple starting points simultaneously
- "All X spread at once" or "minimum distance from ANY of the X's"
- Expansion from boundaries inward

**Signal phrase:** _"Minimum distance from each cell to the nearest gate"_ → Multi-Source BFS
**Signal phrase:** _"Rotting oranges spread each minute"_ → Multi-Source BFS, track time (levels)

---

## 2. Core Intuition

**Single-source BFS**: one starting node, expand outward.
**Multi-source BFS**: add ALL starting nodes to the queue at once before the first iteration. The BFS then expands from all of them simultaneously — as if one "super source" connects to all of them with zero-cost edges.

```
Grid with gates (G) and rooms (R):
INF  INF  INF  INF
INF   G   INF  INF
INF  INF  INF   G
INF  INF  INF  INF

Single-source: run BFS from each G separately → O(k × m × n)
Multi-source: add ALL G's to queue first → O(m × n) total

Queue starts: [(1,1), (2,3)]  ← both gates at once
BFS expands: distance 1 cells for both simultaneously
```

---

## 3. Generic Java Templates

### Template A — Multi-Source BFS (Walls and Gates / 01 Matrix)
```java
public void wallsAndGates(int[][] rooms) {
    int rows = rooms.length, cols = rooms[0].length;
    Queue<int[]> queue = new LinkedList<>();

    // Step 1: Add ALL sources (gates/zeros) to queue first
    for (int r = 0; r < rows; r++) {
        for (int c = 0; c < cols; c++) {
            if (rooms[r][c] == 0) { // 0 = gate/source
                queue.offer(new int[]{r, c});
            }
        }
    }

    // Step 2: BFS from all sources simultaneously
    int[][] dirs = {{0,1},{0,-1},{1,0},{-1,0}};
    while (!queue.isEmpty()) {
        int[] cell = queue.poll();
        int r = cell[0], c = cell[1];

        for (int[] d : dirs) {
            int nr = r + d[0], nc = c + d[1];

            // Only update INF cells (unvisited rooms)
            if (nr >= 0 && nr < rows && nc >= 0 && nc < cols
                    && rooms[nr][nc] == Integer.MAX_VALUE) {
                rooms[nr][nc] = rooms[r][c] + 1; // distance propagates
                queue.offer(new int[]{nr, nc});
            }
        }
    }
}
```

### Template B — Rotting Oranges (Track Time as BFS Levels)
```java
public int orangesRotting(int[][] grid) {
    int rows = grid.length, cols = grid[0].length;
    Queue<int[]> queue = new LinkedList<>();
    int fresh = 0;

    // Step 1: Add all rotten oranges, count fresh
    for (int r = 0; r < rows; r++) {
        for (int c = 0; c < cols; c++) {
            if (grid[r][c] == 2) queue.offer(new int[]{r, c});
            else if (grid[r][c] == 1) fresh++;
        }
    }

    if (fresh == 0) return 0; // no fresh oranges

    int minutes = 0;
    int[][] dirs = {{0,1},{0,-1},{1,0},{-1,0}};

    while (!queue.isEmpty() && fresh > 0) {
        int levelSize = queue.size();
        minutes++;

        for (int i = 0; i < levelSize; i++) {
            int[] cell = queue.poll();
            for (int[] d : dirs) {
                int nr = cell[0] + d[0], nc = cell[1] + d[1];
                if (nr >= 0 && nr < rows && nc >= 0 && nc < cols
                        && grid[nr][nc] == 1) {
                    grid[nr][nc] = 2; // rot it
                    fresh--;
                    queue.offer(new int[]{nr, nc});
                }
            }
        }
    }

    return fresh == 0 ? minutes : -1; // -1 if unreachable fresh remain
}
```

### Template C — 01 Matrix (Distance to Nearest 0)
```java
public int[][] updateMatrix(int[][] mat) {
    int rows = mat.length, cols = mat[0].length;
    int[][] dist = new int[rows][cols];
    Queue<int[]> queue = new LinkedList<>();

    // Add all 0s to queue, mark 1s as unvisited (MAX_VALUE)
    for (int r = 0; r < rows; r++) {
        for (int c = 0; c < cols; c++) {
            if (mat[r][c] == 0) {
                dist[r][c] = 0;
                queue.offer(new int[]{r, c});
            } else {
                dist[r][c] = Integer.MAX_VALUE;
            }
        }
    }

    int[][] dirs = {{0,1},{0,-1},{1,0},{-1,0}};
    while (!queue.isEmpty()) {
        int[] cell = queue.poll();
        int r = cell[0], c = cell[1];
        for (int[] d : dirs) {
            int nr = r + d[0], nc = c + d[1];
            if (nr >= 0 && nr < rows && nc >= 0 && nc < cols
                    && dist[nr][nc] == Integer.MAX_VALUE) {
                dist[nr][nc] = dist[r][c] + 1;
                queue.offer(new int[]{nr, nc});
            }
        }
    }
    return dist;
}
```

### Template D — Pacific Atlantic Water Flow (Reverse BFS from Borders)
```java
public List<List<Integer>> pacificAtlantic(int[][] heights) {
    int rows = heights.length, cols = heights[0].length;
    boolean[][] pacific = new boolean[rows][cols];
    boolean[][] atlantic = new boolean[rows][cols];
    Queue<int[]> pQueue = new LinkedList<>();
    Queue<int[]> aQueue = new LinkedList<>();

    // Add border cells to each ocean's queue
    for (int r = 0; r < rows; r++) {
        pQueue.offer(new int[]{r, 0}); pacific[r][0] = true;
        aQueue.offer(new int[]{r, cols-1}); atlantic[r][cols-1] = true;
    }
    for (int c = 0; c < cols; c++) {
        pQueue.offer(new int[]{0, c}); pacific[0][c] = true;
        aQueue.offer(new int[]{rows-1, c}); atlantic[rows-1][c] = true;
    }

    bfsOcean(heights, pQueue, pacific);
    bfsOcean(heights, aQueue, atlantic);

    List<List<Integer>> result = new ArrayList<>();
    for (int r = 0; r < rows; r++) {
        for (int c = 0; c < cols; c++) {
            if (pacific[r][c] && atlantic[r][c]) {
                result.add(Arrays.asList(r, c));
            }
        }
    }
    return result;
}

private void bfsOcean(int[][] heights, Queue<int[]> queue, boolean[][] visited) {
    int[][] dirs = {{0,1},{0,-1},{1,0},{-1,0}};
    while (!queue.isEmpty()) {
        int[] cell = queue.poll();
        int r = cell[0], c = cell[1];
        for (int[] d : dirs) {
            int nr = r + d[0], nc = c + d[1];
            if (nr >= 0 && nr < heights.length && nc >= 0 && nc < heights[0].length
                    && !visited[nr][nc]
                    && heights[nr][nc] >= heights[r][c]) { // water flows uphill in reverse
                visited[nr][nc] = true;
                queue.offer(new int[]{nr, nc});
            }
        }
    }
}
```

---

## 5. Canonical Code Problems

### Core (Must Solve)

| LC # | Problem | Difficulty | Why Important |
|---|---|---|---|
| 994 | Rotting Oranges | Medium | Time-based multi-source BFS |
| 542 | 01 Matrix | Medium | Distance multi-source BFS |
| 286 | Walls and Gates | Medium | Propagate distance from sources |
| 417 | Pacific Atlantic Water Flow | Medium | Reverse BFS from two borders |
| 1765 | Map of Highest Peak | Medium | Multi-source height BFS |

### Advanced Variations

| LC # | Problem | Difficulty | Why Important |
|---|---|---|---|
| 1162 | As Far from Land as Possible | Medium | Max distance to nearest 0 |
| 1091 | Shortest Path in Binary Matrix | Medium | BFS with diagonal moves |
| 934 | Shortest Bridge | Medium | DFS find + BFS expand |
| 1197 | Minimum Knight Moves | Medium | BFS from target |
| 2146 | K Highest Ranked Items | Medium | BFS + sorting |

---

## 6. Solve Step-by-Step — LC 994: Rotting Oranges

Already covered in Template B above. Key insight recap:

1. All rotten oranges go in queue simultaneously — that's the "multi-source" start
2. Track `fresh` count — if it hits 0, we're done early
3. Track `minutes` by BFS level
4. If any fresh remain after BFS exhausts → return -1

### Edge Cases
- No fresh oranges: return 0 immediately
- Disconnected fresh: return -1 (fresh can never rot)
- All rotten: return 0
- 1×1 grid with fresh: return -1 (no rotten to spread)

---

## 7. Pattern Variations

| Variation | Key Trick |
|---|---|
| Distance from nearest source | Multi-source BFS, propagate distance |
| Time until all infected | BFS levels = time units |
| Can all cells be reached | Check remaining unvisited after BFS |
| Flow from border | Reverse BFS: from ocean/border inward |
| Shortest path with obstacles | BFS, skip walls |
| Expand from one set toward another | BFS from set A until reaching set B |

---

## 8. Common Interview Mistakes

1. **Single-source BFS for multi-source problem** → O(k × n × m) instead of O(n × m)
2. **Not adding visited check before queueing** → TLE from revisiting cells
3. **Rotting oranges: counting minutes wrong** → use BFS levels, not cell count
4. **Pacific Atlantic: going forward** (water flows down) vs reverse (reachability flows up)
5. **Not handling isolated cells** (fresh orange with no rotten neighbor reachable)

---

## 9. Interview Cheat Sheet

```
MULTI-SOURCE BFS — MENTAL CHECKLIST
=====================================
□ "From any/all X, find distance to Y" → Multi-source BFS
□ "Spread simultaneously" → Multi-source BFS (all sources in queue at once)
□ Time = BFS level count (use levelSize snapshot)
□ Distance = propagate from source cells, not destination
□ Reverse BFS: when checking "can X reach Y" — start from Y, go backward

TEMPLATE SKELETON
=================
// 1. Find all sources → add to queue, mark visited
// 2. BFS expand:
while (!queue.isEmpty()):
    [optional: int size = queue.size(); minutes++]
    for each cell in level:
        for each 4-direction neighbor:
            if valid and unvisited:
                mark visited, update dist/state, enqueue

TRICKS
======
- Use the grid itself as visited marker (set to distance value)
- Separate dist[][] matrix if grid must be preserved
- "Reverse BFS" for flow/reachability problems (Pacific Atlantic)
- Check remaining unvisited count to detect unreachable cells
```

---

## 10. Practice Roadmap

**Beginner:**
- LC 1765 — Map of Highest Peak
- LC 542 — 01 Matrix

**Intermediate:**
- LC 994 — Rotting Oranges
- LC 286 — Walls and Gates
- LC 1162 — As Far from Land as Possible

**Taking Hard:**
- LC 417 — Pacific Atlantic Water Flow
- LC 934 — Shortest Bridge
- LC 1091 — Shortest Path in Binary Matrix

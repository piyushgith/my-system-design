# Pattern 25 — Tree Maze & Grid Backtracking (Path Finding)

---

## 1. Pattern Recognition Guide

**Keywords to look for:**
- "find all paths from root to leaf"
- "path exists in maze / grid"
- "all paths that sum to target"
- "explore all routes", "find path avoiding obstacles"
- "robot can move in 4 directions", "reach destination"
- "unique paths with obstacles"

**Tree Maze vs Word Search (Pattern 19):**
- **Word Search** = match a specific sequence of characters — must follow the word
- **Tree/Grid Maze** = find any valid path satisfying a structural condition (reach destination, hit target sum, collect all items)

**Signal phrase:** _"Find all root-to-leaf paths with sum = target"_ → Tree DFS + backtrack
**Signal phrase:** _"Can the robot reach the exit?"_ → Grid DFS/BFS + visited marking

---

## 2. Core Intuition

**Tree Maze = DFS with explicit path tracking + backtracking.**

The difference from plain DFS is that you need to:
1. **Record the path** as you go (not just the answer)
2. **Undo the recording** when you backtrack
3. **Collect/check** the full path at leaf/destination nodes

**Grid Maze adds:**
- 4-directional movement
- Bounds checking
- Visited marking (to avoid cycles)
- Restore visited mark after backtracking (if you need ALL paths)

```
Tree path sum = 22:
        5
       / \
      4   8
     /   / \
    11  13   4
   /  \       \
  7    2        1

Path: 5→4→11→2 = 22 ✓
Path: 5→8→4→1  = 18 ✗

Backtracking trace:
visit 5 (path=[5], rem=17)
  visit 4 (path=[5,4], rem=13)
    visit 11 (path=[5,4,11], rem=2)
      visit 7 (path=[5,4,11,7], rem=-5) → leaf, not 0 → backtrack
      visit 2 (path=[5,4,11,2], rem=0)  → leaf, rem==0 → ADD ✓ → backtrack
    backtrack from 11 (path=[5,4])
  backtrack from 4 (path=[5])
  visit 8 ...
```

---

## 3. Generic Java Templates

### Template A — All Root-to-Leaf Paths with Target Sum (Tree)
```java
public List<List<Integer>> pathSum(TreeNode root, int targetSum) {
    List<List<Integer>> result = new ArrayList<>();
    backtrack(root, targetSum, new ArrayList<>(), result);
    return result;
}

private void backtrack(TreeNode node, int remaining,
                        List<Integer> path, List<List<Integer>> result) {
    if (node == null) return;

    path.add(node.val);            // choose
    remaining -= node.val;

    // Check at leaf node
    if (node.left == null && node.right == null && remaining == 0) {
        result.add(new ArrayList<>(path)); // copy — important!
    } else {
        backtrack(node.left, remaining, path, result);
        backtrack(node.right, remaining, path, result);
    }

    path.remove(path.size() - 1); // unchoose (backtrack)
}
```

### Template B — All Paths from Root to Leaf (No Condition)
```java
public List<String> binaryTreePaths(TreeNode root) {
    List<String> result = new ArrayList<>();
    if (root == null) return result;

    dfs(root, String.valueOf(root.val), result);
    return result;
}

private void dfs(TreeNode node, String path, List<String> result) {
    if (node.left == null && node.right == null) {
        result.add(path);
        return;
    }
    if (node.left != null)  dfs(node.left,  path + "->" + node.left.val,  result);
    if (node.right != null) dfs(node.right, path + "->" + node.right.val, result);
}
```

### Template C — Grid Maze: Find If Path Exists (DFS)
```java
public boolean hasPath(int[][] grid, int[] start, int[] end) {
    int rows = grid.length, cols = grid[0].length;
    boolean[][] visited = new boolean[rows][cols];
    return dfs(grid, start[0], start[1], end[0], end[1], visited);
}

private boolean dfs(int[][] grid, int r, int c, int er, int ec, boolean[][] visited) {
    if (r < 0 || r >= grid.length || c < 0 || c >= grid[0].length) return false;
    if (grid[r][c] == 1) return false; // obstacle
    if (visited[r][c]) return false;   // already visited
    if (r == er && c == ec) return true; // reached destination

    visited[r][c] = true; // mark visited (no need to unmark for path existence)

    return dfs(grid, r+1, c, er, ec, visited) ||
           dfs(grid, r-1, c, er, ec, visited) ||
           dfs(grid, r, c+1, er, ec, visited) ||
           dfs(grid, r, c-1, er, ec, visited);
}
```

### Template D — Grid Maze: ALL Paths (Backtracking with Restore)
```java
public List<List<int[]>> allPaths(int[][] grid, int[] start, int[] end) {
    List<List<int[]>> result = new ArrayList<>();
    boolean[][] visited = new boolean[grid.length][grid[0].length];
    List<int[]> path = new ArrayList<>();

    path.add(start);
    visited[start[0]][start[1]] = true;

    dfsAllPaths(grid, start[0], start[1], end[0], end[1], visited, path, result);
    return result;
}

private void dfsAllPaths(int[][] grid, int r, int c, int er, int ec,
                          boolean[][] visited, List<int[]> path,
                          List<List<int[]>> result) {
    if (r == er && c == ec) {
        result.add(new ArrayList<>(path));
        return;
    }

    int[][] dirs = {{0,1},{0,-1},{1,0},{-1,0}};
    for (int[] d : dirs) {
        int nr = r + d[0], nc = c + d[1];
        if (nr < 0 || nr >= grid.length || nc < 0 || nc >= grid[0].length) continue;
        if (grid[nr][nc] == 1 || visited[nr][nc]) continue;

        visited[nr][nc] = true;           // choose
        path.add(new int[]{nr, nc});
        dfsAllPaths(grid, nr, nc, er, ec, visited, path, result);
        path.remove(path.size() - 1);     // unchoose
        visited[nr][nc] = false;          // restore — CRITICAL for all-paths
    }
}
```

### Template E — Unique Paths with Obstacles (DP, not backtracking)
```java
// Count distinct paths from top-left to bottom-right (only right/down moves)
public int uniquePathsWithObstacles(int[][] grid) {
    int rows = grid.length, cols = grid[0].length;
    if (grid[0][0] == 1 || grid[rows-1][cols-1] == 1) return 0;

    int[] dp = new int[cols];
    dp[0] = 1;

    for (int r = 0; r < rows; r++) {
        for (int c = 0; c < cols; c++) {
            if (grid[r][c] == 1) {
                dp[c] = 0; // obstacle blocks path
            } else if (c > 0) {
                dp[c] += dp[c - 1]; // paths from left + paths from above
            }
        }
    }

    return dp[cols - 1];
}
```

### Template F — Maze with Sliding (Ball Rolls Until Wall) — BFS
```java
// LC 490: Ball rolls in a direction until hitting a wall; find path to hole
public boolean hasPath(int[][] maze, int[] start, int[] destination) {
    int rows = maze.length, cols = maze[0].length;
    boolean[][] visited = new boolean[rows][cols];
    Queue<int[]> queue = new LinkedList<>();

    queue.offer(start);
    visited[start[0]][start[1]] = true;

    int[][] dirs = {{0,1},{0,-1},{1,0},{-1,0}};

    while (!queue.isEmpty()) {
        int[] curr = queue.poll();

        if (curr[0] == destination[0] && curr[1] == destination[1]) return true;

        for (int[] d : dirs) {
            int r = curr[0], c = curr[1];

            // Roll until hitting a wall
            while (r + d[0] >= 0 && r + d[0] < rows &&
                   c + d[1] >= 0 && c + d[1] < cols &&
                   maze[r + d[0]][c + d[1]] == 0) {
                r += d[0];
                c += d[1];
            }

            if (!visited[r][c]) {
                visited[r][c] = true;
                queue.offer(new int[]{r, c});
            }
        }
    }

    return false;
}
```

### Template G — Path Sum III (Count Paths in Tree, Any Node to Any Node)
```java
// Count paths with sum = targetSum, starting from any node going downward
public int pathSum(TreeNode root, int targetSum) {
    Map<Long, Integer> prefixCount = new HashMap<>();
    prefixCount.put(0L, 1);
    return dfs(root, 0L, targetSum, prefixCount);
}

private int dfs(TreeNode node, long currSum, int target, Map<Long, Integer> prefixCount) {
    if (node == null) return 0;

    currSum += node.val;
    int count = prefixCount.getOrDefault(currSum - target, 0);

    prefixCount.merge(currSum, 1, Integer::sum);

    count += dfs(node.left, currSum, target, prefixCount);
    count += dfs(node.right, currSum, target, prefixCount);

    // Backtrack: remove current node's prefix sum
    prefixCount.merge(currSum, -1, Integer::sum);

    return count;
}
```

---

## 4. Complexity Cheatsheet

| Problem | Time | Space | Notes |
|---|---|---|---|
| All root-to-leaf paths | O(n × h) | O(h) | h = height, copy path at leaf |
| Grid maze existence | O(m × n) | O(m × n) | Each cell visited once |
| Grid maze all paths | O(4^(m×n)) worst | O(m × n) | Exponential — only small grids |
| Unique paths (DP) | O(m × n) | O(n) | Rolling 1D DP |
| Path Sum III | O(n) | O(n) | Prefix sum + HashMap |

**Key:** If problem asks for EXISTENCE or OPTIMAL path → use BFS/DP. If it asks for ALL paths → backtracking. Never use backtracking for "count distinct paths" — use DP.

---

## 5. Canonical LeetCode Problems

### Core (Must Solve)

| LC # | Problem | Difficulty | Template |
|---|---|---|---|
| 113 | Path Sum II (all paths) | Medium | Template A |
| 257 | Binary Tree Paths | Easy | Template B |
| 63 | Unique Paths II (obstacles) | Medium | Template E (DP) |
| 437 | Path Sum III (any node) | Medium | Template G |
| 79 | Word Search | Medium | Grid backtracking |

### Advanced Variations

| LC # | Problem | Difficulty | Template |
|---|---|---|---|
| 980 | Unique Paths III (visit all) | Hard | Grid backtracking + count |
| 490 | The Maze | Medium | Template F (BFS rolling) |
| 505 | The Maze II (shortest) | Medium | Dijkstra rolling |
| 1219 | Path with Maximum Gold | Medium | Grid DFS + backtrack |
| 329 | Longest Increasing Path in Matrix | Hard | DFS + memoization |

---

## 6. Solve Step-by-Step — LC 980: Unique Paths III

**Problem:** Count paths from start to end that visit every non-obstacle cell exactly once.

### Why Backtracking (not DP)
We must visit ALL empty cells — this is a Hamiltonian path problem. No overlapping subproblems in the classical sense; backtracking with pruning is the way.

```java
public int uniquePathsIII(int[][] grid) {
    int rows = grid.length, cols = grid[0].length;
    int startR = 0, startC = 0, totalEmpty = 0;

    // Find start position and count empty cells
    for (int r = 0; r < rows; r++) {
        for (int c = 0; c < cols; c++) {
            if (grid[r][c] != -1) totalEmpty++; // count non-obstacles
            if (grid[r][c] == 1) { startR = r; startC = c; }
        }
    }

    return dfs(grid, startR, startC, totalEmpty);
}

private int dfs(int[][] grid, int r, int c, int remaining) {
    if (r < 0 || r >= grid.length || c < 0 || c >= grid[0].length) return 0;
    if (grid[r][c] == -1) return 0; // obstacle

    if (grid[r][c] == 2) { // reached end
        return remaining == 1 ? 1 : 0; // valid only if all cells visited
    }

    int temp = grid[r][c];
    grid[r][c] = -1; // mark as visited (temporarily obstacle)

    int paths = dfs(grid, r+1, c, remaining-1) +
                dfs(grid, r-1, c, remaining-1) +
                dfs(grid, r, c+1, remaining-1) +
                dfs(grid, r, c-1, remaining-1);

    grid[r][c] = temp; // restore
    return paths;
}
```

### Dry Run (2×2 grid)
```
Grid: 1  0
      0  2

Start=(0,0), empty=4

dfs(0,0,4): mark (0,0) as -1
  dfs(1,0,3): mark (1,0) as -1
    dfs(1,1,2): this is 2(end), remaining=2 ≠ 1 → 0 (didn't visit all)
    dfs(0,0,2): already -1 → 0
    ... → 0
  dfs(0,1,3): mark (0,1) as -1
    dfs(1,1,2): end, remaining=2≠1 → 0
    dfs(0,0,2): -1 → 0
    ... → 0

Total with 2x2 having only one valid path: [expected answer = 2 for 3x3 case]
```

---

## 7. Tree vs Grid vs Implicit — Comparison

| Aspect | Tree Maze | Grid Maze | Implicit Graph |
|---|---|---|---|
| Structure | Binary/N-ary tree | 2D grid | Generated on the fly |
| Movement | left/right child | 4 directions | Problem-defined transitions |
| Visited tracking | Not needed (tree, no cycles) | `visited[][]` | `Set<State>` |
| Backtrack restore | Path list `remove()` | `visited = false` | `visited.remove(state)` |
| When to stop | At leaf or null | At destination or bounds | At target state |

---

## 8. Common Interview Mistakes

1. **Not copying path when adding to result**: `result.add(path)` adds a reference — use `new ArrayList<>(path)`
2. **Not restoring `visited[r][c] = false`** when finding ALL paths (for existence only, no need)
3. **Counting paths with DP when backtracking is needed**: unique paths (no constraint) → DP; must-visit-all → backtracking
4. **Path Sum III: not backtracking the prefix map** — `prefixCount.merge(currSum, -1, ...)` after recursion
5. **Grid bounds check order**: check bounds BEFORE accessing `grid[r][c]` — short-circuit prevents index out of bounds
6. **Confusing `remaining` semantics**: in Unique Paths III, decrement before passing to child (not at child entry)

---

## 9. Interview Cheat Sheet

```
TREE / GRID MAZE BACKTRACKING — MENTAL CHECKLIST
==================================================
□ ALL paths? → Backtracking with path list + copy at destination
□ PATH EXISTS? → DFS/BFS, no restore needed
□ COUNT paths (no must-visit-all)? → DP (not backtracking!)
□ MUST visit all cells? → Backtracking with remaining counter
□ Tree? → No visited needed (trees are acyclic)
□ Grid? → visited[][] needed, restore if finding ALL paths

TREE PATH TEMPLATE (MEMORIZE)
==============================
void dfs(node, remaining, path, result):
    if null: return
    path.add(node.val)
    remaining -= node.val
    if leaf and remaining == 0:
        result.add(new ArrayList<>(path))
    else:
        dfs(left, ...) ; dfs(right, ...)
    path.remove(last)  ← BACKTRACK

GRID PATH TEMPLATE (MEMORIZE)
==============================
void dfs(grid, r, c, ...):
    if out of bounds or obstacle or visited: return
    if destination: record answer
    visited[r][c] = true  ← mark
    for each direction: dfs(next)
    visited[r][c] = false ← restore (if finding ALL paths)

PATH SUM III TRICK
==================
Use prefix sum + HashMap (like subarray sum = k)
Backtrack: remove current prefix from map after recursion
Use Long for sum to avoid integer overflow
```

---

## 10. Practice Roadmap

**Beginner:**
- LC 257 — Binary Tree Paths
- LC 112 — Path Sum
- LC 113 — Path Sum II

**Intermediate:**
- LC 437 — Path Sum III
- LC 63 — Unique Paths II
- LC 1219 — Path with Maximum Gold
- LC 490 — The Maze

**FAANG Hard:**
- LC 980 — Unique Paths III (visit all cells)
- LC 329 — Longest Increasing Path in Matrix
- LC 505 — The Maze II (shortest path with rolling)
- LC 126 — Word Ladder II (all shortest transformation paths)

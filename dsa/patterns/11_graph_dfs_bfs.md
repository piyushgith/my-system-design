# Pattern 11 — Graph DFS & BFS (Adjacency List)

---

## 1. Pattern Recognition Guide

**Keywords to look for:**
- "connected components", "number of islands"
- "can you reach from A to B", "path exists"
- "clone graph", "all paths from source"
- "flood fill", "surrounded regions"
- "friend circles", "provinces"

**DFS vs BFS for graphs:**
- **DFS**: explore as deep as possible, backtrack. Good for: connectivity, cycle detection, path existence, flood fill
- **BFS**: explore level by level. Good for: shortest path (unweighted), minimum steps, multi-source spread

**Signal phrase:** _"Number of connected components"_ → DFS or Union Find
**Signal phrase:** _"Shortest path / minimum steps"_ → BFS

---

## 2. Core Intuition

**The critical difference from trees: graphs have cycles.**
You MUST track visited nodes, or you'll loop forever.

**DFS mental model:** Go deep down one path, backtrack when stuck, try another path.
**BFS mental model:** Ripple outward from source — like dropping a stone in water.

```
Graph: 0—1—3
       |   |
       2   4

DFS from 0: visit 0 → 1 → 3 → 4 (backtrack) → (backtrack) → 2
BFS from 0: visit 0 → [1,2] → [3] → [4]
```

---

## 3. Generic Java Templates

### Graph Representation
```java
// Build adjacency list from edge list
Map<Integer, List<Integer>> buildGraph(int n, int[][] edges) {
    Map<Integer, List<Integer>> adj = new HashMap<>();
    for (int i = 0; i < n; i++) adj.put(i, new ArrayList<>());
    for (int[] edge : edges) {
        adj.get(edge[0]).add(edge[1]);
        adj.get(edge[1]).add(edge[0]); // undirected
    }
    return adj;
}
```

### Template A — Graph DFS (Recursive)
```java
public void dfs(int node, Map<Integer, List<Integer>> adj, boolean[] visited) {
    visited[node] = true;
    // Process node

    for (int neighbor : adj.get(node)) {
        if (!visited[neighbor]) {
            dfs(neighbor, adj, visited);
        }
    }
}

// Count connected components
public int countComponents(int n, int[][] edges) {
    Map<Integer, List<Integer>> adj = buildGraph(n, edges);
    boolean[] visited = new boolean[n];
    int components = 0;

    for (int i = 0; i < n; i++) {
        if (!visited[i]) {
            dfs(i, adj, visited);
            components++;
        }
    }
    return components;
}
```

### Template B — Graph DFS (Iterative)
```java
public void dfsIterative(int start, Map<Integer, List<Integer>> adj, boolean[] visited) {
    Deque<Integer> stack = new ArrayDeque<>();
    stack.push(start);

    while (!stack.isEmpty()) {
        int node = stack.pop();
        if (visited[node]) continue; // guard for multiple pushes
        visited[node] = true;

        // Process node

        for (int neighbor : adj.get(node)) {
            if (!visited[neighbor]) {
                stack.push(neighbor);
            }
        }
    }
}
```

### Template C — Graph BFS (Shortest Path)
```java
public int bfsShortestPath(int src, int dst, Map<Integer, List<Integer>> adj) {
    Queue<Integer> queue = new LinkedList<>();
    Set<Integer> visited = new HashSet<>();

    queue.offer(src);
    visited.add(src);
    int distance = 0;

    while (!queue.isEmpty()) {
        int levelSize = queue.size();

        for (int i = 0; i < levelSize; i++) {
            int node = queue.poll();

            if (node == dst) return distance;

            for (int neighbor : adj.get(node)) {
                if (!visited.contains(neighbor)) {
                    visited.add(neighbor);
                    queue.offer(neighbor);
                }
            }
        }
        distance++;
    }

    return -1; // unreachable
}
```

### Template D — Valid Path (src → dst)
```java
public boolean validPath(int n, int[][] edges, int source, int destination) {
    if (source == destination) return true;

    Map<Integer, List<Integer>> adj = buildGraph(n, edges);
    boolean[] visited = new boolean[n];

    return dfsPath(source, destination, adj, visited);
}

private boolean dfsPath(int node, int dst, Map<Integer, List<Integer>> adj, boolean[] visited) {
    if (node == dst) return true;
    visited[node] = true;

    for (int neighbor : adj.get(node)) {
        if (!visited[neighbor]) {
            if (dfsPath(neighbor, dst, adj, visited)) return true;
        }
    }
    return false;
}
```

### Template E — All Paths from Source to Target (DAG)
```java
public List<List<Integer>> allPathsSourceTarget(int[][] graph) {
    List<List<Integer>> result = new ArrayList<>();
    List<Integer> path = new ArrayList<>();
    path.add(0);
    dfsAllPaths(graph, 0, graph.length - 1, path, result);
    return result;
}

private void dfsAllPaths(int[][] graph, int node, int target,
                          List<Integer> path, List<List<Integer>> result) {
    if (node == target) {
        result.add(new ArrayList<>(path));
        return;
    }
    for (int neighbor : graph[node]) {
        path.add(neighbor);
        dfsAllPaths(graph, neighbor, target, path, result);
        path.remove(path.size() - 1); // backtrack
    }
}
```

---

## 4. Complexity Cheatsheet

| Operation | Time | Space |
|---|---|---|
| DFS / BFS traversal | O(V + E) | O(V) |
| Adjacency list build | O(V + E) | O(V + E) |
| Adjacency matrix lookup | O(1) | O(V²) |

V = vertices, E = edges. Always O(V + E) for graph traversal.

---

## 5. Canonical Code Problems

### Core (Must Solve)

| LC # | Problem | Difficulty | Why Important |
|---|---|---|---|
| 200 | Number of Islands | Medium | Grid DFS / BFS |
| 133 | Clone Graph | Medium | DFS with HashMap |
| 547 | Number of Provinces | Medium | Adjacency matrix DFS |
| 1971 | Find if Path Exists in Graph | Easy | Basic DFS/BFS |
| 797 | All Paths From Source to Target | Medium | DFS + backtrack |

### Advanced Variations

| LC # | Problem | Difficulty | Why Important |
|---|---|---|---|
| 695 | Max Area of Island | Medium | DFS returning size |
| 417 | Pacific Atlantic Water Flow | Medium | Reverse BFS from edges |
| 130 | Surrounded Regions | Medium | BFS from border |
| 127 | Word Ladder | Hard | BFS on implicit graph |
| 207 | Course Schedule | Medium | Cycle detection (topological) |

---

## 6. Solve Step-by-Step — LC 200: Number of Islands

**Problem:** Count number of islands (connected groups of '1's) in a grid.

### Approach: DFS Flood Fill

Whenever we encounter a '1', we DFS to mark the entire island as visited ('0'), then increment count.

```java
public int numIslands(char[][] grid) {
    if (grid == null || grid.length == 0) return 0;

    int rows = grid.length, cols = grid[0].length;
    int islands = 0;

    for (int r = 0; r < rows; r++) {
        for (int c = 0; c < cols; c++) {
            if (grid[r][c] == '1') {
                islands++;
                dfs(grid, r, c); // sink the island
            }
        }
    }
    return islands;
}

private void dfs(char[][] grid, int r, int c) {
    // Bounds check + water check
    if (r < 0 || r >= grid.length || c < 0 || c >= grid[0].length || grid[r][c] != '1') {
        return;
    }

    grid[r][c] = '0'; // mark visited (sink it)

    dfs(grid, r + 1, c);
    dfs(grid, r - 1, c);
    dfs(grid, r, c + 1);
    dfs(grid, r, c - 1);
}
```

### Dry Run
```
Grid:
1 1 0 0 0
1 1 0 0 0
0 0 1 0 0
0 0 0 1 1

r=0,c=0: '1' found → islands=1, DFS sinks (0,0),(0,1),(1,0),(1,1)
r=2,c=2: '1' found → islands=2, DFS sinks (2,2)
r=3,c=3: '1' found → islands=3, DFS sinks (3,3),(3,4)

Answer: 3  ✓
```

### Edge Cases
- All water: `[['0']]` → 0
- All land: one island
- Single cell: 1

---

## 7. Pattern Variations

| Problem Type | Approach |
|---|---|
| Count components | Outer loop + DFS/BFS from each unvisited |
| Path exists | DFS with early return true |
| All paths | DFS + backtracking |
| Shortest path | BFS with distance counter |
| Flood fill | DFS/BFS modifying grid in-place |
| Multi-source BFS | Add all sources to queue at start |
| Cycle detection | DFS with color marking (white/gray/black) |

---

## 8. Common Interview Mistakes

1. **Not marking visited before recursing** — infinite loop in cyclic graphs
2. **Grid bounds check missing** — always check `r >= 0 && r < rows && c >= 0 && c < cols` first
3. **Modifying input grid** — ask interviewer if this is allowed; if not, use separate `visited[][]`
4. **BFS: adding to visited when polling** instead of when offering — can add duplicates to queue
5. **Forgetting 4-directional vs 8-directional** — clarify with interviewer
6. **Using `visited` set for grid** — using grid modification is cleaner and O(1) space

---

## 9. Interview Cheat Sheet

```
GRAPH DFS/BFS — MENTAL CHECKLIST
==================================
□ Cycles possible? → MUST track visited
□ Shortest path? → BFS (not DFS)
□ Connected components? → outer loop + DFS/BFS
□ Grid problem? → 4 directions, bounds check, mark visited
□ Mark visited WHEN? → BFS: when adding to queue (not polling)

DFS STRUCTURE
=============
visited[node] = true
for neighbor in adj[node]:
    if not visited[neighbor]:
        dfs(neighbor)

BFS STRUCTURE
=============
queue.offer(src); visited.add(src)
while queue not empty:
    node = queue.poll()
    for neighbor: if not visited → visited.add + queue.offer

GRID DIRECTIONS
===============
int[][] dirs = {{0,1},{0,-1},{1,0},{-1,0}};
for (int[] d : dirs) dfs(grid, r+d[0], c+d[1]);

COMPLEXITY: Always O(V+E)
```

---

## 10. Practice Roadmap

**Beginner:**
- LC 1971 — Find if Path Exists
- LC 200 — Number of Islands
- LC 547 — Number of Provinces

**Intermediate:**
- LC 133 — Clone Graph
- LC 695 — Max Area of Island
- LC 797 — All Paths Source to Target
- LC 417 — Pacific Atlantic Water Flow

**Taking Hard:**
- LC 127 — Word Ladder
- LC 130 — Surrounded Regions
- LC 207/210 — Course Schedule I/II (Topological)

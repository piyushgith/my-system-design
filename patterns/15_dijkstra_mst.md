# Pattern 15 — Dijkstra's Algorithm + Kruskal / Prim's MST

---

## 1. Pattern Recognition Guide

**Dijkstra keywords:**
- "shortest path in weighted graph"
- "minimum cost to reach", "cheapest flight"
- "network delay time", "minimum time to reach"

**MST keywords:**
- "minimum spanning tree", "minimum cost to connect all nodes"
- "minimum cable length to connect all cities"
- "Kruskal", "Prim"

**Key constraints:**
- Dijkstra: **non-negative edge weights only**. Negative weights → Bellman-Ford
- BFS for shortest path: **unweighted graphs only**
- Dijkstra: **single source** → all nodes. For all-pairs → Floyd-Warshall

---

## 2. Core Intuition

### Dijkstra's Algorithm

**Greedy insight:** Always extend the shortest known path next.

Use a **min-heap** (priority queue): process nodes in order of their current shortest distance from source.
When you pop a node, its distance is finalized (no shorter path can exist — all weights are non-negative).

```
Graph: 0→1 (4), 0→2 (1), 2→1 (2), 1→3 (1), 2→3 (5)
Source: 0

Initial: dist = [0, INF, INF, INF], heap = [(0,0)]
Pop (0,0): relax → 1: min(INF, 4)=4, 2: min(INF, 1)=1
           heap = [(1,2), (4,1)]
Pop (1,2): relax → 1: min(4, 1+2)=3, 3: min(INF, 1+5)=6
           heap = [(3,1), (4,1-stale), (6,3)]
Pop (3,1): relax → 3: min(6, 3+1)=4
           heap = [(4,1-stale), (4,3), (6,3-stale)]
Pop (4,1 - stale): dist[1]=3 ≠ 4 → SKIP
Pop (4,3): done, all processed

Result: [0, 3, 1, 4]
```

**Why skip stale entries?** Heap can have outdated entries. When we pop a node with a distance > its current best, skip it.

---

## 3. Generic Java Templates

### Template A — Dijkstra's Shortest Path
```java
public int[] dijkstra(int n, int[][] edges, int source) {
    // Build adjacency list: node → [(neighbor, weight)]
    List<int[]>[] adj = new List[n];
    for (int i = 0; i < n; i++) adj[i] = new ArrayList<>();
    for (int[] edge : edges) {
        adj[edge[0]].add(new int[]{edge[1], edge[2]});
        adj[edge[1]].add(new int[]{edge[0], edge[2]}); // undirected
    }

    int[] dist = new int[n];
    Arrays.fill(dist, Integer.MAX_VALUE);
    dist[source] = 0;

    // Min-heap: [distance, node]
    PriorityQueue<int[]> pq = new PriorityQueue<>((a, b) -> a[0] - b[0]);
    pq.offer(new int[]{0, source});

    while (!pq.isEmpty()) {
        int[] curr = pq.poll();
        int d = curr[0], node = curr[1];

        // Skip stale entries
        if (d > dist[node]) continue;

        for (int[] neighbor : adj[node]) {
            int nextNode = neighbor[0], weight = neighbor[1];
            int newDist = dist[node] + weight;

            if (newDist < dist[nextNode]) {
                dist[nextNode] = newDist;
                pq.offer(new int[]{newDist, nextNode});
            }
        }
    }

    return dist;
}
```

### Template B — Dijkstra to Specific Target (Early Exit)
```java
public int dijkstraToTarget(int n, int[][] edges, int source, int target) {
    // ... (same setup as above)

    while (!pq.isEmpty()) {
        int[] curr = pq.poll();
        int d = curr[0], node = curr[1];

        if (node == target) return d; // found shortest path to target

        if (d > dist[node]) continue;

        // relax neighbors...
    }

    return -1; // unreachable
}
```

### Template C — Kruskal's MST (Minimum Spanning Tree)
```java
public int kruskalMST(int n, int[][] edges) {
    // Sort edges by weight
    Arrays.sort(edges, (a, b) -> a[2] - b[2]);

    UnionFind uf = new UnionFind(n);
    int totalWeight = 0;
    int edgesUsed = 0;

    for (int[] edge : edges) {
        int u = edge[0], v = edge[1], w = edge[2];

        if (uf.union(u, v)) { // connect if not already connected
            totalWeight += w;
            edgesUsed++;
            if (edgesUsed == n - 1) break; // MST complete: n-1 edges
        }
    }

    return edgesUsed == n - 1 ? totalWeight : -1; // -1 if not connected
}
```

### Template D — Prim's MST (Greedy, Heap-based)
```java
public int primMST(int n, int[][] edges) {
    List<int[]>[] adj = new List[n];
    for (int i = 0; i < n; i++) adj[i] = new ArrayList<>();
    for (int[] e : edges) {
        adj[e[0]].add(new int[]{e[1], e[2]});
        adj[e[1]].add(new int[]{e[0], e[2]});
    }

    boolean[] inMST = new boolean[n];
    PriorityQueue<int[]> pq = new PriorityQueue<>((a, b) -> a[0] - b[0]);
    pq.offer(new int[]{0, 0}); // [weight, node], start at node 0

    int totalWeight = 0, nodesAdded = 0;

    while (!pq.isEmpty() && nodesAdded < n) {
        int[] curr = pq.poll();
        int w = curr[0], node = curr[1];

        if (inMST[node]) continue; // already in MST
        inMST[node] = true;
        totalWeight += w;
        nodesAdded++;

        for (int[] neighbor : adj[node]) {
            if (!inMST[neighbor[0]]) {
                pq.offer(new int[]{neighbor[1], neighbor[0]});
            }
        }
    }

    return nodesAdded == n ? totalWeight : -1;
}
```

---

## 4. Complexity Cheatsheet

| Algorithm | Time | Space |
|---|---|---|
| Dijkstra (binary heap) | O((V + E) log V) | O(V + E) |
| Dijkstra (Fibonacci heap) | O(E + V log V) | — (rarely used) |
| Kruskal's | O(E log E) | O(V) |
| Prim's (binary heap) | O((V + E) log V) | O(V + E) |
| Bellman-Ford | O(V × E) | O(V) |

---

## 5. Canonical Code Problems

### Core (Must Solve)

| LC # | Problem | Difficulty | Why Important |
|---|---|---|---|
| 743 | Network Delay Time | Medium | Classic Dijkstra |
| 1631 | Path With Minimum Effort | Medium | Dijkstra on grid |
| 787 | Cheapest Flights Within K Stops | Medium | Modified Dijkstra (BFS/DP) |
| 1135 | Connecting Cities With Minimum Cost | Medium | Kruskal's MST |
| 1584 | Min Cost to Connect All Points | Medium | Prim's or Kruskal's |

### Advanced Variations

| LC # | Problem | Difficulty | Why Important |
|---|---|---|---|
| 778 | Swim in Rising Water | Hard | Dijkstra on grid |
| 1514 | Path with Maximum Probability | Medium | Dijkstra with probability |
| 882 | Reachable Nodes in Subdivided Graph | Hard | Modified Dijkstra |
| 1168 | Optimize Water Distribution | Hard | Kruskal's with virtual node |
| 1334 | Find the City with Smallest Number of Neighbors | Medium | Floyd-Warshall |

---

## 6. Solve Step-by-Step — LC 743: Network Delay Time

**Problem:** Find the time for a signal to reach ALL nodes from source k.

```java
public int networkDelayTime(int[][] times, int n, int k) {
    List<int[]>[] adj = new List[n + 1];
    for (int i = 1; i <= n; i++) adj[i] = new ArrayList<>();
    for (int[] t : times) adj[t[0]].add(new int[]{t[1], t[2]});

    int[] dist = new int[n + 1];
    Arrays.fill(dist, Integer.MAX_VALUE);
    dist[k] = 0;

    PriorityQueue<int[]> pq = new PriorityQueue<>((a, b) -> a[0] - b[0]);
    pq.offer(new int[]{0, k});

    while (!pq.isEmpty()) {
        int[] curr = pq.poll();
        int d = curr[0], node = curr[1];

        if (d > dist[node]) continue;

        for (int[] next : adj[node]) {
            int newDist = dist[node] + next[1];
            if (newDist < dist[next[0]]) {
                dist[next[0]] = newDist;
                pq.offer(new int[]{newDist, next[0]});
            }
        }
    }

    int maxDist = 0;
    for (int i = 1; i <= n; i++) {
        if (dist[i] == Integer.MAX_VALUE) return -1; // unreachable
        maxDist = Math.max(maxDist, dist[i]);
    }
    return maxDist; // time for signal to reach ALL nodes = max shortest distance
}
```

---

## 7. Pattern Variations

| Problem | Algorithm Choice |
|---|---|
| Shortest path, non-negative weights | Dijkstra |
| Shortest path, negative weights | Bellman-Ford |
| Shortest path, unweighted | BFS |
| All-pairs shortest path | Floyd-Warshall |
| Min cost to connect all nodes | Kruskal's or Prim's |
| Max probability path | Dijkstra (negate log probabilities) |
| Path with minimum max edge | Dijkstra (minimize max seen so far) |

---

## 8. Common Interview Mistakes

1. **Dijkstra with negative weights** — doesn't work; Bellman-Ford handles this
2. **Not skipping stale entries** in heap — causes incorrect dist updates
3. **Kruskal's: forgetting MST needs exactly n-1 edges** — graph may be disconnected
4. **1-indexed nodes** — common in Code; initialize adj list of size n+1
5. **Prim's vs Kruskal's**: Prim's better for dense graphs (E≈V²), Kruskal's for sparse

---

## 9. Interview Cheat Sheet

```
DIJKSTRA — MENTAL CHECKLIST
=============================
□ Weighted graph, non-negative weights? → Dijkstra
□ Unweighted graph? → BFS (simpler)
□ Negative weights? → Bellman-Ford
□ Min spanning tree? → Kruskal (sparse) or Prim (dense)
□ Always skip stale heap entries: if (d > dist[node]) continue

DIJKSTRA STRUCTURE
==================
dist[src] = 0; pq = [(0, src)]
while pq not empty:
    (d, node) = pq.poll()
    if d > dist[node]: continue  ← CRITICAL
    for each (neighbor, weight) in adj[node]:
        if dist[node] + weight < dist[neighbor]:
            dist[neighbor] = dist[node] + weight
            pq.offer(dist[neighbor], neighbor)

KRUSKAL'S STRUCTURE
====================
sort edges by weight
for each edge (u, v, w):
    if union(u, v):  # not yet connected
        add to MST, totalWeight += w
        if edgesUsed == n-1: done

TRICKS
======
- Network delay = max of all shortest distances from source
- "K stops" variant: use BFS with (cost, node, stops) tuple
- Min effort path: Dijkstra where "distance" = max edge weight on path
- Max probability: Dijkstra with log probabilities (negate to minimize)
```

---

## 10. Practice Roadmap

**Beginner:**
- LC 743 — Network Delay Time
- LC 1791 — Find Center of Star Graph

**Intermediate:**
- LC 1631 — Path with Minimum Effort
- LC 1584 — Min Cost to Connect All Points
- LC 1514 — Path with Maximum Probability

**Taking Hard:**
- LC 787 — Cheapest Flights Within K Stops
- LC 778 — Swim in Rising Water
- LC 1168 — Optimize Water Distribution in a Village

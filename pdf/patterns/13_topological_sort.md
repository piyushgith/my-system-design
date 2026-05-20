# Pattern 13 — Topological Sort

---

## 1. Pattern Recognition Guide

**Keywords to look for:**
- "course prerequisites", "task dependencies"
- "build order", "compilation order"
- "can all tasks be completed"
- "order of execution", "scheduling"
- "detect cycle in directed graph"

**When to reach for Topological Sort:**
- Directed Acyclic Graph (DAG) implied
- Need ordering where all dependencies come before dependents
- Cycle detection in directed graph

**Signal phrase:** _"Course A requires course B as prerequisite"_ → Topological Sort
**Signal phrase:** _"Is it possible to complete all courses?"_ → Cycle detection via Topo Sort

---

## 2. Core Intuition

**What is topological order?**
A linear ordering of nodes such that for every directed edge u→v, node u comes before v.

Only possible for DAGs — a cycle means no valid ordering exists.

**Two algorithms:**

### Kahn's Algorithm (BFS-based) — Preferred in interviews
- Count in-degree (incoming edges) for each node
- Start with all nodes having in-degree 0 (no prerequisites)
- Process each, reduce neighbors' in-degrees; add to queue when they hit 0
- If all nodes processed → valid order. If not → cycle exists.

### DFS-based — Reverse post-order
- DFS from each unvisited node
- Add to result AFTER all descendants are processed (post-order)
- Reverse the result

```
Graph: 0→1→3
       ↓    ↑
       2————

In-degrees: 0:0, 1:1, 2:1, 3:2

Queue starts: [0]
Process 0 → reduce 1(→0), 2(→0) → queue: [1,2]
Process 1 → reduce 3(→1) → queue: [2]
Process 2 → reduce 3(→0) → queue: [3]
Process 3 → queue empty

Order: [0,1,2,3]  ✓
```

---

## 3. Generic Java Templates

### Template A — Kahn's Algorithm (BFS Topological Sort)
```java
public int[] topologicalSort(int n, int[][] prerequisites) {
    // Build adjacency list and compute in-degrees
    List<List<Integer>> adj = new ArrayList<>();
    int[] inDegree = new int[n];

    for (int i = 0; i < n; i++) adj.add(new ArrayList<>());

    for (int[] pre : prerequisites) {
        int course = pre[0], prereq = pre[1];
        adj.get(prereq).add(course); // prereq → course
        inDegree[course]++;
    }

    // Initialize queue with all zero in-degree nodes
    Queue<Integer> queue = new LinkedList<>();
    for (int i = 0; i < n; i++) {
        if (inDegree[i] == 0) queue.offer(i);
    }

    int[] order = new int[n];
    int idx = 0;

    while (!queue.isEmpty()) {
        int node = queue.poll();
        order[idx++] = node;

        for (int neighbor : adj.get(node)) {
            inDegree[neighbor]--;
            if (inDegree[neighbor] == 0) {
                queue.offer(neighbor);
            }
        }
    }

    // If idx == n, all nodes processed → no cycle
    return idx == n ? order : new int[]{};
}
```

### Template B — Can Finish (Cycle Detection)
```java
public boolean canFinish(int numCourses, int[][] prerequisites) {
    List<List<Integer>> adj = new ArrayList<>();
    int[] inDegree = new int[numCourses];

    for (int i = 0; i < numCourses; i++) adj.add(new ArrayList<>());

    for (int[] pre : prerequisites) {
        adj.get(pre[1]).add(pre[0]);
        inDegree[pre[0]]++;
    }

    Queue<Integer> queue = new LinkedList<>();
    for (int i = 0; i < numCourses; i++) {
        if (inDegree[i] == 0) queue.offer(i);
    }

    int processed = 0;
    while (!queue.isEmpty()) {
        int node = queue.poll();
        processed++;
        for (int neighbor : adj.get(node)) {
            if (--inDegree[neighbor] == 0) queue.offer(neighbor);
        }
    }

    return processed == numCourses; // true = no cycle
}
```

### Template C — DFS-Based Topological Sort
```java
// 0 = unvisited, 1 = in-progress, 2 = done
public int[] topoSortDFS(int n, int[][] edges) {
    List<List<Integer>> adj = new ArrayList<>();
    for (int i = 0; i < n; i++) adj.add(new ArrayList<>());
    for (int[] e : edges) adj.get(e[0]).add(e[1]);

    int[] state = new int[n]; // 0/1/2
    Deque<Integer> stack = new ArrayDeque<>();
    boolean[] hasCycle = {false};

    for (int i = 0; i < n; i++) {
        if (state[i] == 0) dfs(i, adj, state, stack, hasCycle);
    }

    if (hasCycle[0]) return new int[]{}; // cycle detected

    int[] result = new int[n];
    int i = 0;
    while (!stack.isEmpty()) result[i++] = stack.pop();
    return result;
}

private void dfs(int node, List<List<Integer>> adj, int[] state,
                 Deque<Integer> stack, boolean[] hasCycle) {
    state[node] = 1; // in progress

    for (int neighbor : adj.get(node)) {
        if (state[neighbor] == 1) { hasCycle[0] = true; return; } // back edge = cycle
        if (state[neighbor] == 0) dfs(neighbor, adj, state, stack, hasCycle);
    }

    state[node] = 2; // done
    stack.push(node); // add after all descendants
}
```

### Template D — Course Schedule with Levels (BFS order by level)
```java
// Process courses level by level (semester by semester)
public List<List<Integer>> findOrder(int n, int[][] prereqs) {
    // Same Kahn's but group nodes by BFS level
    // ... (same structure as Template A, but collect by level like BFS)
    List<List<Integer>> semesters = new ArrayList<>();
    // [omitted for brevity — same as level-order BFS]
    return semesters;
}
```

---

## 4. Complexity Cheatsheet

| Algorithm | Time | Space |
|---|---|---|
| Kahn's (BFS) | O(V + E) | O(V + E) |
| DFS-based | O(V + E) | O(V + E) |
| Cycle detection | O(V + E) | O(V) |

---

## 5. Canonical Code Problems

### Core (Must Solve)

| LC # | Problem | Difficulty | Why Important |
|---|---|---|---|
| 207 | Course Schedule | Medium | Cycle detection via topo sort |
| 210 | Course Schedule II | Medium | Return actual order |
| 310 | Minimum Height Trees | Medium | Topo sort from leaves inward |
| 269 | Alien Dictionary | Hard | Build graph from word order |
| 444 | Sequence Reconstruction | Medium | Unique topo order check |

### Advanced Variations

| LC # | Problem | Difficulty | Why Important |
|---|---|---|---|
| 1136 | Parallel Courses | Medium | Min semesters (BFS levels) |
| 2115 | Find All Possible Recipes | Medium | Multi-source topo sort |
| 329 | Longest Increasing Path in Matrix | Hard | DFS + memoization (implicit DAG) |
| 1203 | Sort Items by Groups | Hard | Two-level topological sort |
| 802 | Find Eventual Safe States | Medium | Reverse graph + topo sort |

---

## 6. Solve Step-by-Step — LC 210: Course Schedule II

**Problem:** Return the course order to finish all numCourses, or [] if impossible.

```java
public int[] findOrder(int numCourses, int[][] prerequisites) {
    List<List<Integer>> adj = new ArrayList<>();
    int[] inDegree = new int[numCourses];

    for (int i = 0; i < numCourses; i++) adj.add(new ArrayList<>());

    for (int[] pre : prerequisites) {
        // pre[1] must come before pre[0]
        adj.get(pre[1]).add(pre[0]);
        inDegree[pre[0]]++;
    }

    Queue<Integer> queue = new LinkedList<>();
    for (int i = 0; i < numCourses; i++) {
        if (inDegree[i] == 0) queue.offer(i);
    }

    int[] result = new int[numCourses];
    int idx = 0;

    while (!queue.isEmpty()) {
        int course = queue.poll();
        result[idx++] = course;
        for (int next : adj.get(course)) {
            if (--inDegree[next] == 0) queue.offer(next);
        }
    }

    return idx == numCourses ? result : new int[]{};
}
```

### Dry Run
```
numCourses=4, prerequisites=[[1,0],[2,0],[3,1],[3,2]]
Meaning: 0→1, 0→2, 1→3, 2→3

adj: 0→[1,2], 1→[3], 2→[3]
inDegree: [0, 1, 1, 2]

Queue starts: [0]  (only 0 has no prereqs)
Process 0: result=[0], reduce 1(→0), 2(→0) → queue=[1,2]
Process 1: result=[0,1], reduce 3(→1) → queue=[2]
Process 2: result=[0,1,2], reduce 3(→0) → queue=[3]
Process 3: result=[0,1,2,3]

idx=4 == numCourses=4 → return [0,1,2,3]  ✓
```

---

## 7. Pattern Variations

| Variation | Approach |
|---|---|
| Can all courses be taken? | Kahn's — check if all processed |
| Return actual order | Kahn's — collect in order |
| Minimum time (parallel) | Kahn's — count BFS levels |
| Detect back edge | DFS with 3-color marking |
| Reconstruct from partial order | Unique topological sort |
| Leaves inward (min height tree) | Reverse topo — trim leaves |

---

## 8. Common Interview Mistakes

1. **Edge direction**: prerequisites=[course, prereq] → edge is prereq→course. Easy to flip!
2. **Not checking `idx == n`** at the end — forgetting that incomplete processing = cycle
3. **DFS: not distinguishing in-progress (1) from done (2)** — both would look "visited"
4. **Kahn's vs DFS choice**: Kahn's is easier to implement and explain; prefer in interviews
5. **Forgetting to initialize all adjacency lists** — `adj.get(i)` NPE if not initialized

---

## 9. Interview Cheat Sheet

```
TOPOLOGICAL SORT — MENTAL CHECKLIST
=====================================
□ Dependencies / prerequisites / ordering? → Topological Sort
□ Directed graph with possible cycles? → Kahn's detects cycles
□ "Can all be completed?" → check processed count == n
□ Need actual order? → collect nodes as processed in Kahn's
□ Minimum time / parallel processing? → count BFS levels

KAHN'S ALGORITHM (MEMORIZE)
============================
1. Build adj list + compute inDegree[]
2. Queue all nodes with inDegree[i] == 0
3. While queue not empty:
   - poll node, add to result
   - for each neighbor: inDegree[neighbor]--
   - if inDegree[neighbor] == 0: add to queue
4. If result.size() == n: valid order. Else: cycle.

EDGE DIRECTION CHEATSHEET
==========================
prerequisites = [[course, prereq]]
→ prereq must come BEFORE course
→ Add edge: adj.get(prereq).add(course)
→ inDegree[course]++

TRICKS
======
- Minimum height trees: repeatedly remove leaf nodes (Kahn's from edges)
- "Safe states": reverse graph, find nodes that reach no cycles
- Two-level topo sort: sort within groups AND between groups
```

---

## 10. Practice Roadmap

**Beginner:**
- LC 207 — Course Schedule
- LC 210 — Course Schedule II

**Intermediate:**
- LC 310 — Minimum Height Trees
- LC 802 — Find Eventual Safe States
- LC 1136 — Parallel Courses

**Taking Hard:**
- LC 269 — Alien Dictionary
- LC 329 — Longest Increasing Path in Matrix
- LC 1203 — Sort Items by Groups Respecting Dependencies

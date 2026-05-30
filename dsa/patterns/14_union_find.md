# Pattern 14 — Union Find (Disjoint Set Union / DSU)

---

## 1. Pattern Recognition Guide

**Keywords to look for:**
- "connected components", "number of groups"
- "union two sets", "are these two elements connected"
- "detect cycle in undirected graph"
- "accounts merge", "friend circles"
- "redundant connection", "minimum spanning tree"

**When Union Find beats BFS/DFS:**
- Repeated connectivity queries (online / streaming)
- Dynamic connections (edges added over time)
- Need to merge groups efficiently
- Minimum spanning tree (Kruskal's algorithm)

**Signal phrase:** _"Dynamically connect elements and query connectivity"_ → Union Find
**Signal phrase:** _"Detect if adding this edge creates a cycle"_ → Union Find

---

## 2. Core Intuition

**The problem:** We have n elements. We need to:
1. **Union(x, y)**: merge the groups containing x and y
2. **Find(x)**: which group does x belong to?

**Naive approach:** An array where `group[i]` = group ID. Merging = O(n) update.

**Union Find:** Each element points to a "parent". Groups form trees. The root = group representative.

**Two optimizations that make it nearly O(1) amortized:**

1. **Union by Rank**: attach smaller tree under larger tree (keeps trees flat)
2. **Path Compression**: during Find, make every node on the path point directly to root

```
Initial: each node is its own parent
[0] [1] [2] [3] [4]

Union(0,1): 0 becomes parent of 1 (or vice versa by rank)
[0→0] [1→0] [2] [3] [4]

Union(2,3): 2 becomes parent of 3
[0→0] [1→0] [2→2] [3→2] [4]

Union(1,3): find(1)=0, find(3)=2 → merge: 2's root under 0's root
[0→0] [1→0] [2→0] [3→2→0] [4]

Path compression: when finding 3, 3→2→0, compress to 3→0
```

---

## 3. Generic Java Templates

### Template A — Full Union Find with Rank + Path Compression
```java
class UnionFind {
    private int[] parent;
    private int[] rank;
    private int components; // track number of connected components

    public UnionFind(int n) {
        parent = new int[n];
        rank = new int[n];
        components = n;
        for (int i = 0; i < n; i++) parent[i] = i; // each node is its own parent
    }

    // Find with path compression (iterative — safer for large inputs)
    public int find(int x) {
        while (parent[x] != x) {
            parent[x] = parent[parent[x]]; // path halving (one-pass compression)
            x = parent[x];
        }
        return x;
    }

    // Union by rank — returns true if actually merged (were in different components)
    public boolean union(int x, int y) {
        int rootX = find(x);
        int rootY = find(y);

        if (rootX == rootY) return false; // already connected

        if (rank[rootX] < rank[rootY]) {
            parent[rootX] = rootY;
        } else if (rank[rootX] > rank[rootY]) {
            parent[rootY] = rootX;
        } else {
            parent[rootY] = rootX;
            rank[rootX]++;
        }

        components--;
        return true;
    }

    public boolean connected(int x, int y) {
        return find(x) == find(y);
    }

    public int getComponents() {
        return components;
    }
}
```

### Template B — Number of Connected Components
```java
public int countComponents(int n, int[][] edges) {
    UnionFind uf = new UnionFind(n);

    for (int[] edge : edges) {
        uf.union(edge[0], edge[1]);
    }

    return uf.getComponents();
}
```

### Template C — Redundant Connection (Cycle Detection)
```java
public int[] findRedundantConnection(int[][] edges) {
    int n = edges.length;
    UnionFind uf = new UnionFind(n + 1); // nodes 1-indexed

    for (int[] edge : edges) {
        // If already connected → this edge creates a cycle
        if (!uf.union(edge[0], edge[1])) {
            return edge;
        }
    }

    return new int[]{};
}
```

### Template D — Accounts Merge (Union Find with HashMap)
```java
public List<List<String>> accountsMerge(List<List<String>> accounts) {
    // Map email → index
    Map<String, Integer> emailToId = new HashMap<>();
    Map<String, String> emailToName = new HashMap<>();
    int id = 0;

    for (List<String> account : accounts) {
        String name = account.get(0);
        for (int i = 1; i < account.size(); i++) {
            String email = account.get(i);
            if (!emailToId.containsKey(email)) {
                emailToId.put(email, id++);
                emailToName.put(email, name);
            }
        }
    }

    UnionFind uf = new UnionFind(id);

    for (List<String> account : accounts) {
        int firstId = emailToId.get(account.get(1));
        for (int i = 2; i < account.size(); i++) {
            uf.union(firstId, emailToId.get(account.get(i)));
        }
    }

    // Group emails by root
    Map<Integer, List<String>> groups = new HashMap<>();
    for (String email : emailToId.keySet()) {
        int root = uf.find(emailToId.get(email));
        groups.computeIfAbsent(root, k -> new ArrayList<>()).add(email);
    }

    List<List<String>> result = new ArrayList<>();
    for (List<String> emails : groups.values()) {
        Collections.sort(emails);
        emails.add(0, emailToName.get(emails.get(0)));
        result.add(emails);
    }

    return result;
}
```

---

## 4. Complexity Cheatsheet

| Operation | Time (with rank + compression) | Notes |
|---|---|---|
| Find | O(α(n)) ≈ O(1) amortized | α = inverse Ackermann |
| Union | O(α(n)) ≈ O(1) amortized | |
| n operations | O(n × α(n)) ≈ O(n) | Effectively linear |
| Space | O(n) | parent + rank arrays |

α(n) is the inverse Ackermann function — grows so slowly it's practically constant for any real input.

---

## 5. Canonical Code Problems

### Core (Must Solve)

| LC # | Problem | Difficulty | Why Important |
|---|---|---|---|
| 547 | Number of Provinces | Medium | Basic Union Find usage |
| 684 | Redundant Connection | Medium | Cycle detection |
| 200 | Number of Islands | Medium | Also solvable with UF |
| 721 | Accounts Merge | Medium | UF + HashMap |
| 1202 | Smallest String with Swaps | Medium | UF for index grouping |

### Advanced Variations

| LC # | Problem | Difficulty | Why Important |
|---|---|---|---|
| 685 | Redundant Connection II | Hard | Directed graph variant |
| 1061 | Lexicographically Smallest Equivalent | Medium | UF with custom root |
| 839 | Similar String Groups | Hard | UF + string comparison |
| 1579 | Remove Max Number of Edges | Hard | Two parallel UF |
| 765 | Couples Holding Hands | Hard | Cyclic swap via UF |

---

## 6. Solve Step-by-Step — LC 684: Redundant Connection

**Problem:** Find the edge that, when removed, results in a tree (undirected graph with one fewer edge than vertices, no cycle).

### Approach
Process edges one by one. The first edge that connects two already-connected nodes is the answer (it creates the cycle).

```java
public int[] findRedundantConnection(int[][] edges) {
    UnionFind uf = new UnionFind(edges.length + 1);

    for (int[] edge : edges) {
        if (!uf.union(edge[0], edge[1])) {
            return edge; // this edge connects already-connected nodes
        }
    }

    return new int[]{};
}
```

### Dry Run
```
edges = [[1,2],[1,3],[2,3]]

Union(1,2): 1 and 2 not connected → merge → components=2
Union(1,3): 1 and 3 not connected → merge → components=1
Union(2,3): find(2)=1, find(3)=1 → SAME ROOT → cycle! return [2,3]
```

---

## 7. Pattern Variations

| Problem | Union Find Role |
|---|---|
| Count components | Count components field |
| Detect cycle (undirected) | union() returns false = cycle |
| Minimum spanning tree | Kruskal's = sort edges + union |
| Merge accounts/groups | Union emails/elements together |
| Percolation | Top-to-bottom connectivity |
| Swim in rising water | Union cells as they become accessible |

---

## 8. Common Interview Mistakes

1. **Forgetting path compression** — Find without it is O(n) in worst case
2. **1-indexed vs 0-indexed nodes** — Code often uses 1-indexed; initialize size n+1
3. **Union returning false = important signal** — this is how you detect cycles; don't ignore
4. **Not using rank** — without rank, union by random = O(log n) instead of O(α(n))
5. **Accounts merge: unioning index 1 with all others** — index 0 is the name, not an email

---

## 9. Interview Cheat Sheet

```
UNION FIND — MENTAL CHECKLIST
===============================
□ Dynamic connectivity queries? → Union Find
□ Detect cycle in undirected? → union() returns false = cycle
□ Count components? → track components field
□ Group elements with shared property? → union them
□ Kruskal's MST? → sort edges + union

UNION FIND TEMPLATE (MEMORIZE)
================================
parent[i] = i  (init)
rank[i] = 0    (init)

find(x): while parent[x]!=x: parent[x]=parent[parent[x]]; x=parent[x]
         return x

union(x,y): rx=find(x), ry=find(y)
            if rx==ry: return false (cycle/already connected)
            attach smaller rank under larger
            if equal: rank[rx]++
            return true

TRICKS
======
- Path halving (parent[x]=parent[parent[x]]) ≈ full path compression, simpler
- Weighted/ranked union avoids degenerate chains
- UF + HashMap for string/object keys: map to integer IDs first
- Kruskal's: sort all edges by weight, union until n-1 edges added
```

---

## 10. Practice Roadmap

**Beginner:**
- LC 547 — Number of Provinces
- LC 684 — Redundant Connection

**Intermediate:**
- LC 200 — Number of Islands (UF approach)
- LC 721 — Accounts Merge
- LC 1202 — Smallest String with Swaps

**Taking Hard:**
- LC 685 — Redundant Connection II
- LC 1579 — Remove Max Number of Edges to Keep Graph Fully Traversable
- LC 839 — Similar String Groups

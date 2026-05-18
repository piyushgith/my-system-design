# Patterns Master Index — Java + Code
### candidate's Interview Preparation — Complete 25-Pattern Reference

---

## Complete File Index (All Gaps Filled)

| # | File | Pattern | Category |
|---|---|---|---|
| 00 | `00_master_index.md` | This file | Navigation |
| 01 | `01_two_pointers.md` | Two Pointers | Arrays & Strings |
| 02 | `02_prefix_sum.md` | Prefix Sum | Arrays & Strings |
| 03 | `03_sliding_window.md` | Sliding Window | Arrays & Strings |
| 04 | `04_kadanes_algorithm.md` | Kadane's Algorithm | Arrays & Strings |
| 05 | `05_merge_intervals.md` | Merge Intervals | Arrays & Strings |
| 06 | `06_tree_dfs_recursive.md` | Tree DFS (Recursive) | Trees |
| 07 | `07_tree_bfs.md` | Tree BFS / Level Order | Trees |
| 08 | `08_tree_dfs_iterative.md` | Tree DFS (Iterative) | Trees |
| 09 | `09_fast_slow_pointers.md` | Fast & Slow Pointers | Linked List |
| 10 | `10_linked_list_reversal_dummy.md` | In-Place Reversal + Dummy Nodes | Linked List |
| 11 | `11_graph_dfs_bfs.md` | Graph DFS & BFS | Graphs |
| 12 | `12_multi_source_bfs_matrix.md` | Multi-Source BFS + Matrix | Graphs |
| 13 | `13_topological_sort.md` | Topological Sort | Advanced Graphs |
| 14 | `14_union_find.md` | Union Find (DSU) | Advanced Graphs |
| 15 | `15_dijkstra_mst.md` | Dijkstra + Kruskal/Prim's | Advanced Graphs |
| 16 | `16_heaps.md` | Top K, Two Heaps, Merge K Sorted | Heaps |
| 17 | `17_binary_search.md` | Binary Search (All Variants) | Binary Search |
| 18 | `18_dynamic_programming.md` | Dynamic Programming (Complete) | DP |
| 19 | `19_backtracking.md` | Backtracking (Permutations/Subsets/Combos/N-Queens) | Backtracking |
| 20 | `20_monotonic_stack_parentheses.md` | Monotonic Stack + Parentheses | Stacks & Queues |
| 21 | `21_hashing.md` | Hashing (All Variants) | Hashing |
| 22 | `22_subarray_count.md` | Subarray Count Problems | Arrays & Strings |
| 23 | `23_implicit_graphs.md` | Implicit Graphs / BFS on State Spaces | Graphs |
| 24 | `24_heap_greedy_minimum.md` | Heap + Greedy Minimum Problems | Heaps |
| 25 | `25_tree_maze_grid_backtracking.md` | Tree Maze & Grid Backtracking | Backtracking |

---

## Master Pattern Decision Tree

```
WHAT KIND OF PROBLEM?
│
├── ARRAY / STRING
│   ├── Pair/triplet with sum → Two Pointers (sorted) OR Hashing (unsorted)
│   ├── Max/min CONTIGUOUS subarray sum → Kadane's
│   ├── Longest/shortest subarray with constraint → Sliding Window
│   ├── Range sum query → Prefix Sum
│   ├── Count subarrays sum=k → Prefix Sum + HashMap
│   ├── Count subarrays exactly k distinct → AtMost(k) - AtMost(k-1)
│   └── Overlapping intervals → Merge Intervals
│
├── LINKED LIST
│   ├── Cycle / middle / nth from end → Fast & Slow Pointers
│   └── Reverse / merge / reorder → In-Place Reversal + Dummy Node
│
├── TREE
│   ├── Level-by-level / min depth → Tree BFS
│   ├── Path sum / height / LCA / max path → Tree DFS Recursive
│   ├── BST iterator / no recursion allowed → Tree DFS Iterative
│   ├── All root-to-leaf paths → Tree Maze Backtracking (25)
│   └── Path sum any node→node → Prefix Sum on Tree (25)
│
├── GRAPH (EXPLICIT)
│   ├── Connectivity / flood fill / components → Graph DFS or BFS
│   ├── Shortest path UNWEIGHTED → BFS
│   ├── Distance from multiple sources simultaneously → Multi-Source BFS
│   ├── Shortest path WEIGHTED (non-negative) → Dijkstra
│   ├── Shortest path WEIGHTED (negative) → Bellman-Ford
│   ├── Minimum spanning tree → Kruskal (sparse) / Prim (dense)
│   ├── Task ordering / dependency / cycle detection → Topological Sort
│   └── Dynamic connectivity / merge groups → Union Find
│
├── GRAPH (IMPLICIT / STATE SPACE)
│   ├── "Min steps to transform X to Y" → Implicit Graph BFS (23)
│   ├── "Min moves to reach target state" → Implicit Graph BFS (23)
│   └── Word Ladder / Lock combinations / Sliding puzzle → BFS on States (23)
│
├── ORDER STATISTICS / SORTED ACCESS
│   ├── Kth largest / top K → Min-Heap of size k
│   ├── Median of stream → Two Heaps (16)
│   ├── Merge K sorted lists → K-size Min-Heap (16)
│   ├── "Always pick best available" greedily → Heap + Greedy (24)
│   ├── Items unlock at threshold → Two Heap unlock pattern (24)
│   └── Binary search on sorted / monotonic → Binary Search (17)
│
├── ENUMERATION
│   ├── ALL permutations / combinations / subsets → Backtracking (19)
│   ├── ALL paths in grid visiting all cells → Grid Backtracking (25)
│   └── Exact sequence matching in grid → Word Search Backtracking (19)
│
├── OPTIMIZATION WITH CHOICES
│   ├── Overlapping subproblems + optimal substructure → DP (18)
│   ├── Always one greedy locally optimal choice → Greedy
│   └── Count distinct paths (no must-visit-all) → DP, not backtracking
│
└── SPECIAL STRUCTURES
    ├── Next greater/smaller element → Monotonic Stack (20)
    ├── Bracket matching / longest valid → Stack (20)
    ├── Frequency / grouping / O(1) lookup → Hashing (21)
    └── Pair complement lookup → Hashing (21)
```

---

## Time Complexity Reference

```
O(1)         HashMap/Set lookup, Heap peek, Array index
O(log n)     Binary Search, Heap push/pop, BST ops
O(n)         Single-pass: Two Pointers, Sliding Window, Kadane's,
             Prefix Sum, Hashing, Monotonic Stack
O(n log n)   Sort-first: Merge Intervals, Kruskal's
             Top K from n elements (n log k ≈ n log n when k~n)
O(n log k)   Heap with k-size: Top K, Merge K sorted
O(V + E)     Graph traversal: DFS, BFS, Topological Sort
O((V+E)logV) Dijkstra with binary heap
O(E log E)   Kruskal's MST
O(n × W)     Knapsack DP (W = weight/capacity)
O(n × m)     2D DP: LCS, Edit Distance, Grid DP
O(n²)        LIS (naive), some DP pair states
O(S × T)     Implicit Graph BFS: S = states, T = transitions per state
O(2ⁿ)        Backtracking subsets, some DP bit-mask
O(n!)        Backtracking permutations
```

---

## Space Complexity Reference

```
O(1)      Two Pointers, Kadane's, Binary Search (iterative)
O(h)      Tree DFS recursion (h = height; O(n) skewed, O(log n) balanced)
O(n)      Prefix array, HashMap/Set, Stack/Queue, Heap
O(W)      1D DP (rolling array optimization)
O(V)      Graph visited array
O(V + E)  Adjacency list representation
O(n × m)  2D DP table, grid visited
O(S)      Implicit graph visited states
```

---

## Pattern Confusion Guide

| Confused Pair | Distinguisher |
|---|---|
| **Sliding Window vs Kadane's** | Kadane's: negatives OK, decide extend-or-restart. SW: shrink window when invalid |
| **Sliding Window vs Prefix Sum** | SW: O(1) state update while sweeping. PS: precompute O(n), then O(1) per query |
| **Prefix+HashMap vs Sliding Window for count** | Has negatives → Prefix+HashMap. Non-negative + constraint → Sliding Window |
| **Graph BFS vs Dijkstra** | Unweighted edges → BFS. Weighted non-negative → Dijkstra |
| **Topological Sort vs DFS** | Topo Sort = DFS + post-order collection + cycle check |
| **Union Find vs BFS/DFS** | UF: dynamic connectivity, merge, MST. BFS/DFS: traversal, shortest path |
| **0/1 Knapsack vs Unbounded** | 0/1 = inner loop right→left (prevent reuse). Unbounded = left→right (allow reuse) |
| **Backtracking vs DP** | Backtracking: enumerate ALL solutions. DP: find OPTIMAL with memoization |
| **Tree Maze vs Grid Word Search** | Tree Maze: path satisfies structural property (sum, visit all). Word Search: must spell specific word |
| **Implicit Graph BFS vs Explicit Graph BFS** | Implicit: generate neighbors on the fly. Explicit: read from adjacency list |
| **Heap+Greedy vs Pure Greedy** | Heap+Greedy: best choice changes dynamically (need O(log n) access). Pure Greedy: sort once, pick linearly |
| **Binary Search on Values vs Two Pointers** | BS: monotonic function, any sorted structure. TP: sorted array, need pairs |

---

## Taking Interview Signal Phrases → Pattern

| If you hear / read... | Pattern |
|---|---|
| "sorted array, find pair with sum" | Two Pointers |
| "maximum sum contiguous subarray" | Kadane's |
| "longest/shortest subarray where..." | Sliding Window |
| "range sum / range query" | Prefix Sum |
| "count subarrays with sum = k" | Prefix + HashMap |
| "count subarrays with exactly k distinct" | AtMost(k) - AtMost(k-1) |
| "merge / combine overlapping intervals" | Merge Intervals |
| "linked list cycle / find middle" | Fast & Slow Pointers |
| "reverse / reorder linked list" | In-Place Reversal + Dummy |
| "level order / minimum depth in tree" | Tree BFS |
| "all root-to-leaf paths / path sum" | Tree DFS + Backtracking |
| "path sum from any node to any node" | Prefix Sum on Tree |
| "BST iterator / in-order without recursion" | Tree DFS Iterative |
| "connected components / flood fill" | Graph DFS or BFS |
| "shortest path (unweighted graph)" | BFS |
| "distance from ALL sources simultaneously" | Multi-Source BFS |
| "minimum steps to transform X into Y" | Implicit Graph BFS |
| "word ladder / lock dial / sliding puzzle" | Implicit Graph BFS |
| "shortest path (weighted, non-negative)" | Dijkstra |
| "minimum cost to connect all nodes" | Kruskal's / Prim's |
| "course prerequisites / task ordering" | Topological Sort |
| "dynamic connectivity / merge groups" | Union Find |
| "kth largest / top K elements" | Min-Heap of size K |
| "median of a stream" | Two Heaps |
| "merge K sorted lists" | K-size Min-Heap |
| "always pick best available, changes over time" | Heap + Greedy |
| "find in sorted array in O(log n)" | Binary Search |
| "minimum X such that condition holds" | Binary Search on Answer |
| "all permutations / all subsets / all combos" | Backtracking |
| "all paths visiting all cells" | Grid Backtracking |
| "N-Queens / Sudoku / constraint placement" | Backtracking |
| "maximum/minimum with repeated subproblems" | Dynamic Programming |
| "number of ways to reach / count paths" | DP |
| "next greater element / stock span" | Monotonic Stack |
| "valid / longest valid parentheses" | Stack |
| "two sum / group anagrams / duplicates" | Hashing |

---

## 6-Week Taking Prep Roadmap

### Week 1 — Array Foundations
- Day 1-2: Two Pointers, Hashing → LC 1, 15, 11, 49, 128
- Day 3-4: Sliding Window, Kadane's → LC 3, 76, 53, 152
- Day 5-6: Prefix Sum, Subarray Count → LC 560, 974, 992
- Day 7: Merge Intervals → LC 56, 57, 253

### Week 2 — Linear Structures
- Day 1-2: Linked List → LC 141, 142, 206, 92, 23
- Day 3-4: Tree DFS (all orders, all variants) → LC 104, 543, 124, 236
- Day 5-6: Tree BFS → LC 102, 199, 103
- Day 7: Tree DFS Iterative + BST → LC 94, 173, 230

### Week 3 — Graphs
- Day 1-2: Graph DFS/BFS → LC 200, 133, 547, 797
- Day 3: Multi-Source BFS → LC 994, 542, 417
- Day 4: Implicit Graph BFS → LC 127, 752, 773
- Day 5: Topological Sort → LC 207, 210, 269
- Day 6-7: Union Find + Dijkstra → LC 684, 721, 743, 1584

### Week 4 — Sorted Structures
- Day 1-2: Binary Search (all variants) → LC 704, 34, 33, 875, 410
- Day 3-4: Heaps (Top K, Two Heaps, Merge K) → LC 215, 295, 23, 347
- Day 5-6: Heap + Greedy → LC 621, 767, 871, 502
- Day 7: Monotonic Stack → LC 739, 84, 32, 907

### Week 5 — DP & Backtracking
- Day 1-2: Linear DP, Knapsack → LC 322, 416, 198, 300
- Day 3-4: 2D DP (LCS, Edit Distance, Grid) → LC 1143, 72, 62, 516
- Day 5-6: Backtracking → LC 78, 46, 39, 51, 131
- Day 7: Grid Backtracking + Tree Maze → LC 113, 437, 980

### Week 6 — Mock Interviews
- 2 timed problems per day (45 min each)
- One easy/medium warm-up + one medium/hard
- Practice talking through pattern recognition out loud
- Review all cheat sheets (bottom of each file)

---

## Edge Cases Always Test

```
□ Empty input (n=0, null root, empty string)
□ Single element
□ All same elements
□ All negative values (Kadane's, sums)
□ Target not reachable (BFS, backtracking)
□ Integer overflow (use long for sums)
□ Cycle in graph (mark visited!)
□ Skewed tree (DFS stack depth)
□ Duplicate elements (sort + skip)
□ k > n (top K when fewer elements exist)
```

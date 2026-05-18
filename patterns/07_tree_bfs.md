# Pattern 07 — Tree Breadth-First Search (BFS / Level Order)

---

## 1. Pattern Recognition Guide

**Keywords to look for:**
- "level order traversal"
- "minimum depth", "shortest path in tree"
- "right side view", "left side view"
- "average of each level", "sum per level"
- "zigzag traversal"
- "connect next right pointers"

**When to reach for Tree BFS:**
- Process nodes level by level
- Find minimum depth (DFS finds any path; BFS finds shortest)
- Need nodes at the same depth grouped together
- Right/left view problems (last/first node per level)

**Signal phrase:** _"Level order"_ → BFS immediately
**Signal phrase:** _"Minimum depth"_ → BFS (DFS would be inefficient — finds deepest first)

---

## 2. Core Intuition

**Why BFS for levels?**
DFS goes deep before wide — it can't naturally group nodes by level.
BFS uses a queue: process all nodes at distance d before any at distance d+1.

```
        1          ← Level 0
       / \
      2   3        ← Level 1
     / \   \
    4   5   6      ← Level 2

Queue progression:
Start: [1]
Pop 1, push 2,3 → [2,3]      level 0 done: [1]
Pop 2, push 4,5 → [3,4,5]
Pop 3, push 6   → [4,5,6]    level 1 done: [2,3]
Pop 4,5,6 (no children)      level 2 done: [4,5,6]
```

**Core invariant:**
At the start of each outer loop iteration, the queue contains **exactly all nodes at the current level**.
Process them all, adding their children for the next level.

---

## 3. Generic Java Templates

### Template A — Level Order (Groups by Level)
```java
public List<List<Integer>> levelOrder(TreeNode root) {
    List<List<Integer>> result = new ArrayList<>();
    if (root == null) return result;

    Queue<TreeNode> queue = new LinkedList<>();
    queue.offer(root);

    while (!queue.isEmpty()) {
        int levelSize = queue.size(); // snapshot: nodes at this level
        List<Integer> level = new ArrayList<>();

        for (int i = 0; i < levelSize; i++) {
            TreeNode node = queue.poll();
            level.add(node.val);

            if (node.left != null)  queue.offer(node.left);
            if (node.right != null) queue.offer(node.right);
        }

        result.add(level);
    }

    return result;
}
```

### Template B — Right Side View (Last Node Per Level)
```java
public List<Integer> rightSideView(TreeNode root) {
    List<Integer> result = new ArrayList<>();
    if (root == null) return result;

    Queue<TreeNode> queue = new LinkedList<>();
    queue.offer(root);

    while (!queue.isEmpty()) {
        int levelSize = queue.size();

        for (int i = 0; i < levelSize; i++) {
            TreeNode node = queue.poll();

            // Last node in the level = rightmost visible
            if (i == levelSize - 1) result.add(node.val);

            if (node.left != null)  queue.offer(node.left);
            if (node.right != null) queue.offer(node.right);
        }
    }

    return result;
}
```

### Template C — Minimum Depth
```java
public int minDepth(TreeNode root) {
    if (root == null) return 0;

    Queue<TreeNode> queue = new LinkedList<>();
    queue.offer(root);
    int depth = 1;

    while (!queue.isEmpty()) {
        int levelSize = queue.size();

        for (int i = 0; i < levelSize; i++) {
            TreeNode node = queue.poll();

            // First leaf found = minimum depth (BFS guarantee)
            if (node.left == null && node.right == null) return depth;

            if (node.left != null)  queue.offer(node.left);
            if (node.right != null) queue.offer(node.right);
        }

        depth++;
    }

    return depth;
}
```

### Template D — Zigzag Level Order
```java
public List<List<Integer>> zigzagLevelOrder(TreeNode root) {
    List<List<Integer>> result = new ArrayList<>();
    if (root == null) return result;

    Queue<TreeNode> queue = new LinkedList<>();
    queue.offer(root);
    boolean leftToRight = true;

    while (!queue.isEmpty()) {
        int levelSize = queue.size();
        LinkedList<Integer> level = new LinkedList<>(); // LinkedList for addFirst

        for (int i = 0; i < levelSize; i++) {
            TreeNode node = queue.poll();

            if (leftToRight) level.addLast(node.val);
            else             level.addFirst(node.val); // reverse order

            if (node.left != null)  queue.offer(node.left);
            if (node.right != null) queue.offer(node.right);
        }

        result.add(level);
        leftToRight = !leftToRight;
    }

    return result;
}
```

---

## 4. Complexity Cheatsheet

| Operation | Time | Space |
|---|---|---|
| Level order traversal | O(n) | O(w) — w = max width |
| Complete binary tree max width | O(n) | O(n/2) ≈ O(n) |
| Skewed tree max width | O(n) | O(1) |

BFS space = width of widest level (worst case: last level of complete tree = n/2 nodes).

---

## 5. Canonical Code Problems

### Core (Must Solve)

| LC # | Problem | Difficulty | Why Important |
|---|---|---|---|
| 102 | Binary Tree Level Order Traversal | Medium | Core template |
| 107 | Level Order Traversal II (bottom-up) | Medium | Reverse result |
| 199 | Binary Tree Right Side View | Medium | Last node per level |
| 111 | Minimum Depth of Binary Tree | Easy | BFS vs DFS distinction |
| 637 | Average of Levels in Binary Tree | Easy | Aggregate per level |

### Advanced Variations

| LC # | Problem | Difficulty | Why Important |
|---|---|---|---|
| 103 | Binary Tree Zigzag Level Order | Medium | Direction toggle |
| 116 | Populating Next Right Pointers | Medium | BFS without extra space |
| 515 | Find Largest Value in Each Row | Medium | Max per level |
| 1161 | Maximum Level Sum of a Binary Tree | Medium | Sum per level |
| 958 | Check Completeness of a Binary Tree | Medium | Null-gap detection in BFS |

---

## 6. Solve Step-by-Step — LC 199: Binary Tree Right Side View

**Problem:** Return the values visible from the right side of the tree (one per level).

### Intuition
Standing to the right, you see the last node at each level. BFS naturally processes level by level — take the last node of each iteration.

```java
public List<Integer> rightSideView(TreeNode root) {
    List<Integer> result = new ArrayList<>();
    if (root == null) return result;

    Queue<TreeNode> queue = new LinkedList<>();
    queue.offer(root);

    while (!queue.isEmpty()) {
        int levelSize = queue.size();
        TreeNode lastNode = null;

        for (int i = 0; i < levelSize; i++) {
            lastNode = queue.poll();
            if (lastNode.left != null)  queue.offer(lastNode.left);
            if (lastNode.right != null) queue.offer(lastNode.right);
        }

        result.add(lastNode.val); // last node processed at this level
    }

    return result;
}
```

### Dry Run
```
Tree:   1
       / \
      2   3
       \   \
        5   4

Level 0: queue=[1],   process 1, push 2,3    → last=1  → result=[1]
Level 1: queue=[2,3], process 2(push 5), 3(push 4) → last=3 → result=[1,3]
Level 2: queue=[5,4], process 5, 4           → last=4  → result=[1,3,4]
```

### Edge Cases
- Left-skewed tree: right view = all left-side nodes (only one per level)
- Single node: `[root.val]`
- Root with only left child: both root and left child are visible

---

## 7. Pattern Variations

| Variation | Change from Template A |
|---|---|
| Bottom-up levels | Reverse result list at end |
| Right side view | Keep only last node per level |
| Left side view | Keep only first node per level |
| Level averages | Sum / levelSize |
| Zigzag | Toggle addFirst / addLast |
| Max per level | Track max during inner loop |
| Check completeness | Track null-seen flag in BFS |

---

## 8. Common Interview Mistakes

1. **Forgetting `levelSize = queue.size()` snapshot** — queue grows during inner loop, processing would continue into next level
2. **Using stack instead of queue** — stack gives DFS order, not BFS
3. **Not handling null root** — always check at the start
4. **Minimum depth pitfall**: DFS returns depth of first leaf found, not shallowest. BFS is correct here.
5. **Zigzag: using Collections.reverse()** — O(n) extra per level; `LinkedList.addFirst()` is cleaner and O(1)

---

## 9. Interview Cheat Sheet

```
TREE BFS — MENTAL CHECKLIST
=============================
□ Level-by-level processing? → BFS with queue
□ Minimum depth / shortest path? → BFS (not DFS!)
□ Right/Left view? → last/first node per level
□ Always snapshot: levelSize = queue.size() before inner loop
□ Offer children: only if not null

STRUCTURE
=========
Queue<TreeNode> queue = new LinkedList<>();
queue.offer(root);
while (!queue.isEmpty()) {
    int size = queue.size();    ← SNAPSHOT
    for (int i = 0; i < size; i++) {
        TreeNode node = queue.poll();
        // process node
        if (node.left != null)  queue.offer(node.left);
        if (node.right != null) queue.offer(node.right);
    }
    // level complete
}

TRICKS
======
- Right view = last node; Left view = first node (i==0)
- Zigzag: use LinkedList, toggle addFirst/addLast
- Bottom-up: Collections.reverse(result) at the end
- Check completeness: after first null in BFS, no more non-nulls allowed
```

---

## 10. Practice Roadmap

**Beginner:**
- LC 102 — Level Order Traversal
- LC 111 — Minimum Depth
- LC 637 — Average of Levels

**Intermediate:**
- LC 199 — Right Side View
- LC 103 — Zigzag Level Order
- LC 515 — Find Largest Value in Each Row
- LC 958 — Check Completeness

**Taking Hard:**
- LC 116 — Populating Next Right Pointers (O(1) space variant)
- LC 117 — Populating Next Right Pointers II
- LC 1161 — Maximum Level Sum

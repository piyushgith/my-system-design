# Pattern 06 — Tree Depth-First Search (Recursive)

---

## 1. Pattern Recognition Guide

**Keywords to look for:**
- "height / depth of tree"
- "path sum", "root to leaf"
- "lowest common ancestor"
- "validate BST", "symmetric tree"
- "serialize / deserialize"
- "diameter", "maximum path sum"

**When to reach for Tree DFS:**
- Need to visit every node
- Problem involves path from root to leaf
- Need information from children to compute parent's value (post-order)
- Need to propagate information from root downward (pre-order)

**Signal phrase:** _"Find all paths from root to leaf with sum = k"_ → DFS pre-order
**Signal phrase:** _"Height of tree"_ → DFS post-order (need children first)

---

## 2. Core Intuition

**Three traversal orders — know when to use each:**

```
        1
       / \
      2   3
     / \
    4   5

Pre-order  (Root → Left → Right): 1, 2, 4, 5, 3  ← process before children
In-order   (Left → Root → Right): 4, 2, 5, 1, 3  ← BST sorted order
Post-order (Left → Right → Root): 4, 5, 2, 3, 1  ← process after children
```

**When to use which:**
- **Pre-order**: copy tree, serialize, pass state DOWN (root → leaves)
- **In-order**: BST problems (gives sorted sequence)
- **Post-order**: compute height/size, delete tree, pass info UP (leaves → root)

**Core invariant:**
At each node, trust the recursion — your function returns the correct answer for any subtree. Just handle the current node + combine children's results.

---

## 3. Generic Java Templates

### TreeNode Definition
```java
public class TreeNode {
    int val;
    TreeNode left;
    TreeNode right;
    TreeNode(int val) { this.val = val; }
}
```

### Template A — Pre-order (pass state down)
```java
public void preorder(TreeNode node, int runningState) {
    if (node == null) return;

    // Process current node FIRST
    runningState += node.val; // example: accumulate path sum

    // Base case: leaf node
    if (node.left == null && node.right == null) {
        // check/store result
        return;
    }

    preorder(node.left, runningState);
    preorder(node.right, runningState);
}
```

### Template B — Post-order (return info up)
```java
public int postorder(TreeNode node) {
    if (node == null) return 0; // base case

    int leftResult = postorder(node.left);   // get from left child
    int rightResult = postorder(node.right); // get from right child

    // Combine children results + current node
    return 1 + Math.max(leftResult, rightResult); // example: height
}
```

### Template C — Global Max with Local Computation (Diameter / Max Path Sum)
```java
private int globalMax = 0; // or Integer.MIN_VALUE for sum problems

public int solve(TreeNode root) {
    dfs(root);
    return globalMax;
}

private int dfs(TreeNode node) {
    if (node == null) return 0;

    int left = dfs(node.left);
    int right = dfs(node.right);

    // Update global answer (may "use" current node as root of path)
    globalMax = Math.max(globalMax, left + right + node.val);

    // Return to parent: best single path through this node
    return node.val + Math.max(left, right);
}
```

### Template D — In-order BST Validation / Operations
```java
private Integer prev = null; // track previous value in in-order

public boolean isValidBST(TreeNode node) {
    if (node == null) return true;

    if (!isValidBST(node.left)) return false; // left subtree

    // In-order: process current
    if (prev != null && node.val <= prev) return false;
    prev = node.val;

    return isValidBST(node.right); // right subtree
}
```

### Template E — Return Multiple Values (avoid global with int[])
```java
// Return [height, isDiameter] or use a custom class
private int[] dfs(TreeNode node) {
    // int[0] = height, int[1] = diameter through subtree
    if (node == null) return new int[]{0, 0};

    int[] left = dfs(node.left);
    int[] right = dfs(node.right);

    int height = 1 + Math.max(left[0], right[0]);
    int diameter = Math.max(left[0] + right[0], Math.max(left[1], right[1]));

    return new int[]{height, diameter};
}
```

---

## 4. Complexity Cheatsheet

| Operation | Time | Space (call stack) |
|---|---|---|
| Any full traversal | O(n) | O(h) — h = height |
| Balanced tree height | O(log n) | O(log n) |
| Skewed tree height | O(n) | O(n) ← watch for stack overflow |
| BST search | O(h) | O(h) |

---

## 5. Canonical Code Problems

### Core (Must Solve)

| LC # | Problem | Difficulty | Why Important |
|---|---|---|---|
| 104 | Maximum Depth of Binary Tree | Easy | Basic post-order template |
| 112 | Path Sum | Easy | Pre-order with running sum |
| 226 | Invert Binary Tree | Easy | Pre-order swap |
| 543 | Diameter of Binary Tree | Medium | Global max + post-order |
| 98 | Validate Binary Search Tree | Medium | In-order with bounds |

### Advanced Variations

| LC # | Problem | Difficulty | Why Important |
|---|---|---|---|
| 124 | Binary Tree Maximum Path Sum | Hard | Global max with negatives |
| 236 | Lowest Common Ancestor | Medium | Post-order return signal |
| 297 | Serialize and Deserialize Binary Tree | Hard | Pre-order + reconstruction |
| 105 | Construct Tree from Preorder+Inorder | Medium | Divide and conquer |
| 1448 | Count Good Nodes in Binary Tree | Medium | Pre-order with max-so-far |

---

## 6. Solve Step-by-Step — LC 124: Binary Tree Maximum Path Sum

**Problem:** Find the maximum path sum where path can start and end at any node.

### Key Insight
A path can go: left subtree → current node → right subtree (doesn't have to go through root).
At each node we decide: can including this node's subtree improve the path?

```java
class Solution {
    private int maxSum = Integer.MIN_VALUE;

    public int maxPathSum(TreeNode root) {
        dfs(root);
        return maxSum;
    }

    // Returns: max path sum starting from this node going DOWN (one direction only)
    private int dfs(TreeNode node) {
        if (node == null) return 0;

        // Only take positive contributions from children
        int leftGain = Math.max(0, dfs(node.left));
        int rightGain = Math.max(0, dfs(node.right));

        // This node AS the "top" of the path: can use both children
        int pathThroughNode = node.val + leftGain + rightGain;
        maxSum = Math.max(maxSum, pathThroughNode);

        // Return to parent: can only extend in ONE direction
        return node.val + Math.max(leftGain, rightGain);
    }
}
```

### Dry Run
```
Tree:    -10
         /  \
        9    20
            /  \
           15    7

dfs(9)  → leftGain=0, rightGain=0, path=9,  maxSum=9,  return 9
dfs(15) → leftGain=0, rightGain=0, path=15, maxSum=15, return 15
dfs(7)  → leftGain=0, rightGain=0, path=7,  maxSum=15, return 7
dfs(20) → leftGain=15, rightGain=7, path=20+15+7=42, maxSum=42, return 20+15=35
dfs(-10)→ leftGain=max(0,9)=9, rightGain=max(0,35)=35
          path=-10+9+35=34, maxSum=42, return -10+35=25

Answer: 42  ✓
```

### Edge Cases
- All negative: `[-3,-1,-2]` → `-1` (single node, `Math.max(0, child)` handles this)
- Single node: return that node's value
- Path doesn't go through root at all

---

## 7. Pattern Variations

| Problem Type | Order | Key Trick |
|---|---|---|
| Height, size, sum | Post-order | Combine children results |
| Path sum root→leaf | Pre-order | Pass running sum down |
| Max path any node | Post-order | Global max + take max(0, child) |
| LCA | Post-order | Return signal when found |
| BST validate | In-order | Track previous node |
| Level sum / zigzag | BFS | Use queue instead |
| Build tree from traversals | Divide & conquer | Split by root index |

---

## 8. Common Interview Mistakes

1. **Forgetting null check** at the top of every recursive call
2. **Not clamping negatives**: `Math.max(0, dfs(child))` — negative subtree hurts the path
3. **Confusing "return to parent" vs "update global"** — they're different values in Template C
4. **Stack overflow for skewed trees** — mention iterative DFS as follow-up
5. **In-order BST: using a global prev** — works but be careful in Java with `Integer prev = null` (not `int`)
6. **LCA: returning non-null from both children** is the signal — don't keep recursing

---

## 9. Interview Cheat Sheet

```
TREE DFS — MENTAL CHECKLIST
============================
□ What order? Pre/In/Post?
□ Passing data DOWN? → Pre-order + parameter
□ Collecting data UP? → Post-order + return value
□ Global max? → Instance variable + post-order
□ BST? → In-order, or use min/max bounds
□ Null base case always first!

ORDER CHEATSHEET
================
Pre  (R,L,R): top-down, copy, serialize, path from root
In   (L,R,R): BST sorted order, validate, kth smallest
Post (L,R,R): height, size, LCA, max path sum, delete

TRICKS
======
- max(0, child) → don't take negative subtrees
- Return int[] or small class to avoid global variables
- LCA: if both left/right non-null → current node is LCA
- BST validate: pass (min, max) bounds down instead of tracking prev
```

---

## 10. Practice Roadmap

**Beginner:**
- LC 104 — Max Depth
- LC 226 — Invert Binary Tree
- LC 112 — Path Sum
- LC 144/94/145 — Pre/In/Post-order traversal

**Intermediate:**
- LC 543 — Diameter of Binary Tree
- LC 98 — Validate BST
- LC 236 — Lowest Common Ancestor
- LC 1448 — Count Good Nodes
- LC 113 — Path Sum II (all paths)

**Taking Hard:**
- LC 124 — Binary Tree Maximum Path Sum
- LC 297 — Serialize and Deserialize Binary Tree
- LC 105 — Construct from Preorder + Inorder
- LC 968 — Binary Tree Cameras

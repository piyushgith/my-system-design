# Pattern 08 — Tree DFS Iterative

---

## 1. Pattern Recognition Guide

**When to use Iterative DFS over Recursive:**
- Interviewer asks "can you do this without recursion?"
- Tree is highly skewed (n=10⁵ depth) → stack overflow risk
- Need explicit control over the call stack
- In-order iterative is common in BST iterator problems

**Same problems as recursive DFS** — iterative is an implementation choice, not a different pattern.

**Signal phrase:** _"Implement BST iterator"_ → In-order iterative with explicit stack
**Signal phrase:** _"Without recursion"_ → Iterative DFS

---

## 2. Core Intuition

**Recursion = implicit call stack. Iterative = explicit stack (or queue for BFS).**

For DFS, you simulate what the call stack does:
- Push node
- Pop and process
- Push children (right first so left is processed first — stack is LIFO)

```
Pre-order iterative logic:
Push root → pop → process → push right then left → pop left → ...

Stack: [1]
Pop 1, process 1, push 3, push 2  → Stack: [3,2]
Pop 2, process 2, push 5, push 4  → Stack: [3,5,4]
Pop 4, process 4                   → Stack: [3,5]
Pop 5, process 5                   → Stack: [3]
Pop 3, process 3                   → Stack: []
Result: 1,2,4,5,3  ✓ (pre-order)
```

**In-order is trickier** — you must go all the way left first, then process, then go right.

---

## 3. Generic Java Templates

### Template A — Pre-order Iterative
```java
public List<Integer> preorderTraversal(TreeNode root) {
    List<Integer> result = new ArrayList<>();
    if (root == null) return result;

    Deque<TreeNode> stack = new ArrayDeque<>();
    stack.push(root);

    while (!stack.isEmpty()) {
        TreeNode node = stack.pop();
        result.add(node.val);           // process BEFORE pushing children

        // Push right first so left is processed first (LIFO)
        if (node.right != null) stack.push(node.right);
        if (node.left != null)  stack.push(node.left);
    }

    return result;
}
```

### Template B — In-order Iterative (Most Important)
```java
public List<Integer> inorderTraversal(TreeNode root) {
    List<Integer> result = new ArrayList<>();
    Deque<TreeNode> stack = new ArrayDeque<>();
    TreeNode curr = root;

    while (curr != null || !stack.isEmpty()) {
        // Go as far left as possible
        while (curr != null) {
            stack.push(curr);
            curr = curr.left;
        }

        // Process node (leftmost unprocessed)
        curr = stack.pop();
        result.add(curr.val);

        // Move to right subtree
        curr = curr.right;
    }

    return result;
}
```

### Template C — Post-order Iterative (Two-Stack Trick)
```java
public List<Integer> postorderTraversal(TreeNode root) {
    List<Integer> result = new ArrayList<>();
    if (root == null) return result;

    Deque<TreeNode> stack = new ArrayDeque<>();
    stack.push(root);

    // Trick: do "reverse pre-order" (Root → Right → Left), then reverse result
    while (!stack.isEmpty()) {
        TreeNode node = stack.pop();
        result.add(node.val);               // add to result

        if (node.left != null)  stack.push(node.left);  // push left first
        if (node.right != null) stack.push(node.right); // right processed next
    }

    Collections.reverse(result); // reverse to get Left → Right → Root
    return result;
}
```

### Template D — BST Iterator (In-order, Lazy)
```java
// LC 173: Implement BST iterator — next() returns in-order next, hasNext() check
class BSTIterator {
    private Deque<TreeNode> stack = new ArrayDeque<>();

    public BSTIterator(TreeNode root) {
        pushLeft(root); // push all leftmost nodes
    }

    public int next() {
        TreeNode node = stack.pop();
        pushLeft(node.right); // push left spine of right subtree
        return node.val;
    }

    public boolean hasNext() {
        return !stack.isEmpty();
    }

    private void pushLeft(TreeNode node) {
        while (node != null) {
            stack.push(node);
            node = node.left;
        }
    }
}
```

### Template E — General DFS with State (Iterative Path Sum)
```java
public boolean hasPathSum(TreeNode root, int targetSum) {
    if (root == null) return false;

    // Stack stores [node, remainingSum]
    Deque<Object[]> stack = new ArrayDeque<>();
    stack.push(new Object[]{root, targetSum});

    while (!stack.isEmpty()) {
        Object[] top = stack.pop();
        TreeNode node = (TreeNode) top[0];
        int remaining = (int) top[1];
        remaining -= node.val;

        if (node.left == null && node.right == null && remaining == 0) return true;

        if (node.right != null) stack.push(new Object[]{node.right, remaining});
        if (node.left  != null) stack.push(new Object[]{node.left, remaining});
    }

    return false;
}
```

---

## 4. Complexity Cheatsheet

| Traversal | Time | Space |
|---|---|---|
| Pre-order iterative | O(n) | O(h) |
| In-order iterative | O(n) | O(h) |
| Post-order iterative | O(n) | O(h) |
| BST Iterator next() | O(h) amortized O(1) | O(h) |

---

## 5. Canonical Code Problems

### Core (Must Solve)

| LC # | Problem | Difficulty | Why Important |
|---|---|---|---|
| 144 | Binary Tree Preorder Traversal | Easy | Template A |
| 94 | Binary Tree Inorder Traversal | Easy | Template B (critical) |
| 145 | Binary Tree Postorder Traversal | Easy | Template C |
| 173 | Binary Search Tree Iterator | Medium | Template D (BST + lazy) |
| 230 | Kth Smallest in BST | Medium | In-order iterative |

### Advanced Variations

| LC # | Problem | Difficulty | Why Important |
|---|---|---|---|
| 285 | Inorder Successor in BST | Medium | Modified in-order |
| 426 | Convert BST to Sorted Doubly LL | Medium | In-order with prev pointer |
| 114 | Flatten Binary Tree to Linked List | Medium | Pre-order iterative |
| 1028 | Recover a Tree From Preorder | Hard | Stack + depth tracking |
| 255 | Verify Preorder Sequence in BST | Medium | Monotonic stack on traversal |

---

## 6. Solve Step-by-Step — LC 173: BST Iterator

**Problem:** Implement next() and hasNext() with O(h) space.

### Why This Is Clever
We don't traverse all at once. Instead, we lazily maintain a stack of "pending" nodes — always the left spine of the remaining tree.

```java
class BSTIterator {
    private Deque<TreeNode> stack = new ArrayDeque<>();

    public BSTIterator(TreeNode root) {
        pushLeft(root);
    }

    public int next() {
        TreeNode node = stack.pop();  // smallest remaining
        pushLeft(node.right);         // prepare right subtree's in-order
        return node.val;
    }

    public boolean hasNext() {
        return !stack.isEmpty();
    }

    private void pushLeft(TreeNode node) {
        while (node != null) {
            stack.push(node);
            node = node.left;
        }
    }
}
```

### Dry Run
```
BST:   7
      / \
     3   15
        /  \
       9    20

Init: pushLeft(7) → stack: [7, 3] (3 on top)

next() → pop 3, pushLeft(3.right=null) → stack: [7], return 3
next() → pop 7, pushLeft(7.right=15) → pushLeft pushes 15,9 → stack: [15,9], return 7
next() → pop 9, pushLeft(null) → stack: [15], return 9
next() → pop 15, pushLeft(20) → stack: [20], return 15
next() → pop 20, pushLeft(null) → stack: [], return 20
hasNext() → false
```

### Amortized Analysis
Each node is pushed and popped exactly once across all next() calls → O(n) total → O(1) amortized per call. Space = O(h) for left spine.

---

## 7. Pattern Variations

| Variation | Key Difference |
|---|---|
| Pre-order | Push right first, then left |
| In-order | Explicit "go left" loop + stack |
| Post-order | Reverse of modified pre-order |
| BST Iterator | Lazy in-order with pushLeft helper |
| Path tracking | Push (node, state) pairs onto stack |
| Morris Traversal | O(1) space — modify tree temporarily |

---

## 8. Common Interview Mistakes

1. **Pre-order: push left first** — wrong! Push right first so left is popped first (LIFO)
2. **In-order: checking `stack.isEmpty()` only** — also need `curr != null` in while condition
3. **Post-order two-stack**: forgetting to reverse at the end
4. **BST Iterator: not calling `pushLeft(node.right)`** after popping — misses right subtree
5. **Using `stack.push` vs `stack.add`** — stick to `ArrayDeque` with `push/pop` for stack semantics

---

## 9. Interview Cheat Sheet

```
ITERATIVE DFS — MENTAL CHECKLIST
==================================
□ Pre-order?  → push root, loop: pop→process→push right→push left
□ In-order?   → curr pointer + stack: go left, pop+process, go right
□ Post-order? → reverse-pre-order trick, then reverse result
□ BST Iterator? → pushLeft helper, lazy evaluation

IN-ORDER PATTERN (MEMORIZE)
============================
curr = root
while (curr != null || !stack.isEmpty()):
    while (curr != null): stack.push(curr); curr = curr.left
    curr = stack.pop()
    process(curr)
    curr = curr.right

TRICKS
======
- Always use ArrayDeque, never Stack<> class (synchronized, slow)
- Post-order = reverse of (Root → Right → Left) traversal
- BST Iterator amortized O(1): each node pushed/popped once total
- Morris Traversal: O(1) space, modifies tree temporarily (mention as bonus)
```

---

## 10. Practice Roadmap

**Beginner:**
- LC 144 — Preorder Traversal
- LC 94 — Inorder Traversal
- LC 145 — Postorder Traversal

**Intermediate:**
- LC 173 — BST Iterator
- LC 230 — Kth Smallest in BST
- LC 114 — Flatten Binary Tree to Linked List

**Taking Hard:**
- LC 1028 — Recover Tree From Preorder
- LC 426 — Convert BST to Sorted Doubly Linked List
- LC 255 — Verify Preorder Sequence in BST

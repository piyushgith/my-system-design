## Common Patterns
 
- **Two Pointers** → LinkedList cycle, Palindrome, 2Sum II, Container With Most Water
- **Fast/Slow Pointers** → Cycle detection, Palindrome LL
- **Stack** → Tree traversals (iterative), Valid Parentheses, Verify Preorder
- **BFS (Queue)** → Level Order Traversal, Number of Islands, Connected Components
- **DFS (Recursion/Stack)** → Path Sum, Binary Tree Paths, Course Schedule
- **Divide & Conquer** → Unique BST, Construct Binary Tree
- **Dynamic Programming** → Unique BST count, Paint Fence, House Robber III
- **Bit Manipulation** → Missing Number, Single Number, Sum of Two Integers, Subsets


# 01 — Linked List

```java
// Shared node definition used across all linked list problems
public class ListNode {
    int val;
    ListNode next;
    ListNode(int x) { val = x; }
}
```

---

## 1.1 Linked List Cycle
**URL:** https://leetcode.com/problems/linked-list-cycle/

> Floyd's tortoise-and-hare: fast pointer moves 2 steps, slow moves 1. If they meet → cycle.

```java
public boolean hasCycle(ListNode head) {
    if (head == null) return false;
    ListNode slow = head, fast = head;
    while (fast != null && fast.next != null) {
        slow = slow.next;
        fast = fast.next.next;
        if (fast == slow) return true;
    }
    return false;
}
```

---

## 1.2 Linked List Cycle II
**URL:** https://leetcode.com/problems/linked-list-cycle-ii/

> After detecting the meeting point, reset slow to head. Both advance 1 step — they meet at the cycle start.

```java
public ListNode detectCycle(ListNode head) {
    if (head == null) return null;
    ListNode slow = head, fast = head;
    boolean hasCycle = false;
    while (fast != null && fast.next != null) {
        slow = slow.next;
        fast = fast.next.next;
        if (fast == slow) { hasCycle = true; break; }
    }
    if (!hasCycle) return null;
    slow = head;
    while (fast != slow) {
        fast = fast.next;
        slow = slow.next;
    }
    return slow;
}
```

---

## 1.3 Reverse Linked List
**URL:** https://leetcode.com/problems/reverse-linked-list/

```java
public ListNode reverseList(ListNode head) {
    ListNode prev = null, curr = head;
    while (curr != null) {
        ListNode next = curr.next;
        curr.next = prev;
        prev = curr;
        curr = next;
    }
    return prev;
}
```

---

## 1.4 Delete Node in a Linked List
**URL:** https://leetcode.com/problems/delete-node-in-a-linked-list/

> Copy value from next node into current, then skip next.

```java
public void deleteNode(ListNode node) {
    node.val = node.next.val;
    node.next = node.next.next;
}
```

---

## 1.5 Merge Two Sorted Lists
**URL:** https://leetcode.com/problems/merge-two-sorted-lists/

```java
public ListNode mergeTwoLists(ListNode l1, ListNode l2) {
    ListNode dummy = new ListNode(0), p = dummy;
    while (l1 != null && l2 != null) {
        if (l1.val < l2.val) { p.next = l1; l1 = l1.next; }
        else                 { p.next = l2; l2 = l2.next; }
        p = p.next;
    }
    p.next = (l1 != null) ? l1 : l2;
    return dummy.next;
}
```

---

## 1.6 Intersection of Two Linked Lists
**URL:** https://leetcode.com/problems/intersection-of-two-linked-lists/

> Measure both lengths. Advance the longer pointer by the difference, then walk together.

```java
public ListNode getIntersectionNode(ListNode headA, ListNode headB) {
    int lenA = length(headA), lenB = length(headB);
    while (lenA > lenB) { headA = headA.next; lenA--; }
    while (lenB > lenA) { headB = headB.next; lenB--; }
    while (headA != headB) { headA = headA.next; headB = headB.next; }
    return headA;
}

private int length(ListNode node) {
    int len = 0;
    while (node != null) { node = node.next; len++; }
    return len;
}
```

---

## 1.7 Palindrome Linked List
**URL:** https://leetcode.com/problems/palindrome-linked-list/

> Push first half onto a stack using fast/slow pointers, then compare with second half.

```java
public boolean isPalindrome(ListNode head) {
    if (head == null || head.next == null) return true;
    ListNode slow = head, fast = head;
    Deque<Integer> stack = new ArrayDeque<>();
    while (fast != null && fast.next != null) {
        stack.push(slow.val);
        slow = slow.next;
        fast = fast.next.next;
    }
    if (fast != null) slow = slow.next; // odd length: skip middle
    while (slow != null) {
        if (slow.val != stack.pop()) return false;
        slow = slow.next;
    }
    return true;
}
```

---

## 1.8 Remove Linked List Elements
**URL:** https://leetcode.com/problems/remove-linked-list-elements/

```java
public ListNode removeElements(ListNode head, int val) {
    ListNode dummy = new ListNode(0);
    dummy.next = head;
    ListNode prev = dummy, curr = head;
    while (curr != null) {
        if (curr.val == val) prev.next = curr.next;
        else                 prev = curr;
        curr = curr.next;
    }
    return dummy.next;
}
```

---

## 1.9 Remove Duplicates from Sorted List
**URL:** https://leetcode.com/problems/remove-duplicates-from-sorted-list/

```java
public ListNode deleteDuplicates(ListNode head) {
    ListNode curr = head;
    while (curr != null && curr.next != null) {
        if (curr.val == curr.next.val) curr.next = curr.next.next;
        else                           curr = curr.next;
    }
    return head;
}
```

---

## 1.10 Remove Duplicates from Sorted List II
**URL:** https://leetcode.com/problems/remove-duplicates-from-sorted-list-ii/

> Remove ALL nodes with duplicate values, leaving only distinct ones.

```java
public ListNode deleteDuplicatesII(ListNode head) {
    ListNode dummy = new ListNode(0);
    dummy.next = head;
    ListNode prev = dummy;
    while (prev.next != null) {
        ListNode curr = prev.next;
        boolean isDup = false;
        while (curr.next != null && curr.val == curr.next.val) {
            curr = curr.next;
            isDup = true;
        }
        if (isDup) prev.next = curr.next;
        else       prev = prev.next;
    }
    return dummy.next;
}
```

---

## 1.11 Remove Nth Node from End of List
**URL:** https://leetcode.com/problems/remove-nth-node-from-end-of-list/

> Two-pointer one-pass: advance fast n steps ahead, then move both until fast reaches end.

```java
public ListNode removeNthFromEnd(ListNode head, int n) {
    ListNode dummy = new ListNode(0);
    dummy.next = head;
    ListNode fast = dummy, slow = dummy;
    for (int i = 0; i < n; i++) fast = fast.next;
    while (fast.next != null) { fast = fast.next; slow = slow.next; }
    slow.next = slow.next.next;
    return dummy.next;
}
```

# 01b — Linked List Supplement

> This file covers **Swap Nodes in Pairs**, which appeared in the PDF table of contents
> (section 1.2.11) but had no Python implementation in the source PDF.

---

## Swap Nodes in Pairs
**URL:** https://leetcode.com/problems/swap-nodes-in-pairs/

Swap every two adjacent nodes and return the head. You may **not** modify node values —
only node links may be changed.

**Example:** `1→2→3→4` → `2→1→4→3`

### Iterative (Recommended)

```java
public ListNode swapPairs(ListNode head) {
    ListNode dummy = new ListNode(0);
    dummy.next = head;
    ListNode prev = dummy;

    while (prev.next != null && prev.next.next != null) {
        ListNode first  = prev.next;
        ListNode second = prev.next.next;

        // Rewire: prev → second → first → (rest)
        first.next  = second.next;
        second.next = first;
        prev.next   = second;

        prev = first;   // advance past the swapped pair
    }
    return dummy.next;
}
```

**Complexity:** O(n) time, O(1) space.

---

### Recursive (Elegant, O(n) stack space)

```java
public ListNode swapPairsRecursive(ListNode head) {
    if (head == null || head.next == null) return head;

    ListNode second = head.next;
    head.next       = swapPairsRecursive(second.next);   // recurse on tail
    second.next     = head;                               // link second → first
    return second;                                        // second is new head of pair
}
```

**Trade-off:** Recursive is cleaner to read but uses O(n/2) stack frames — avoid for very long lists.

---

### Walkthrough (Iterative, n=4)

```
Initial:  dummy → 1 → 2 → 3 → 4
                  ↑first ↑second

Step 1:   dummy → 2 → 1 → 3 → 4
                      ↑prev moves here

Step 2:   dummy → 2 → 1 → 4 → 3
                              ↑done
```


# 02 — Trees

```java
// Shared node definition used across all tree problems
public class TreeNode {
    int val;
    TreeNode left, right;
    TreeNode(int x) { val = x; }
}
```

---

## 2.1 Serialize and Deserialize Binary Tree
**URL:** https://leetcode.com/problems/serialize-and-deserialize-binary-tree/

> Pre-order DFS using a queue for deserialization index tracking.

```java
public class Codec {
    public String serialize(TreeNode root) {
        StringBuilder sb = new StringBuilder();
        serializeHelper(root, sb);
        return sb.toString();
    }

    private void serializeHelper(TreeNode node, StringBuilder sb) {
        if (node == null) { sb.append("null,"); return; }
        sb.append(node.val).append(",");
        serializeHelper(node.left, sb);
        serializeHelper(node.right, sb);
    }

    public TreeNode deserialize(String data) {
        Deque<String> queue = new ArrayDeque<>(Arrays.asList(data.split(",")));
        return deserializeHelper(queue);
    }

    private TreeNode deserializeHelper(Deque<String> queue) {
        String val = queue.poll();
        if ("null".equals(val)) return null;
        TreeNode node = new TreeNode(Integer.parseInt(val));
        node.left  = deserializeHelper(queue);
        node.right = deserializeHelper(queue);
        return node;
    }
}
```

---

## 2.2 Preorder Traversal (Iterative)
**URL:** https://leetcode.com/problems/binary-tree-preorder-traversal/

```java
public List<Integer> preorderTraversal(TreeNode root) {
    List<Integer> result = new ArrayList<>();
    if (root == null) return result;
    Deque<TreeNode> stack = new ArrayDeque<>();
    stack.push(root);
    while (!stack.isEmpty()) {
        TreeNode node = stack.pop();
        result.add(node.val);
        if (node.right != null) stack.push(node.right);
        if (node.left  != null) stack.push(node.left);
    }
    return result;
}
```

---

## 2.3 BST Iterator
**URL:** https://leetcode.com/problems/binary-search-tree-iterator/

> Maintain a stack of left-spine nodes. O(h) memory, O(1) amortized next().

```java
public class BSTIterator {
    private final Deque<TreeNode> stack = new ArrayDeque<>();

    public BSTIterator(TreeNode root) {
        pushLeft(root);
    }

    public boolean hasNext() {
        return !stack.isEmpty();
    }

    public int next() {
        TreeNode node = stack.pop();
        pushLeft(node.right);
        return node.val;
    }

    private void pushLeft(TreeNode node) {
        while (node != null) { stack.push(node); node = node.left; }
    }
}
```

---

## 2.4 Inorder Traversal (Iterative)
**URL:** https://leetcode.com/problems/binary-tree-inorder-traversal/

```java
public List<Integer> inorderTraversal(TreeNode root) {
    List<Integer> result = new ArrayList<>();
    Deque<TreeNode> stack = new ArrayDeque<>();
    TreeNode node = root;
    while (!stack.isEmpty() || node != null) {
        if (node != null) { stack.push(node); node = node.left; }
        else { node = stack.pop(); result.add(node.val); node = node.right; }
    }
    return result;
}
```

---

## 2.5 Postorder Traversal (Iterative)
**URL:** https://leetcode.com/problems/binary-tree-postorder-traversal/

> Modified preorder (root→right→left), then reverse the result.

```java
public List<Integer> postorderTraversal(TreeNode root) {
    LinkedList<Integer> result = new LinkedList<>();
    if (root == null) return result;
    Deque<TreeNode> stack = new ArrayDeque<>();
    stack.push(root);
    while (!stack.isEmpty()) {
        TreeNode node = stack.pop();
        result.addFirst(node.val);         // prepend = reverse at the end
        if (node.left  != null) stack.push(node.left);
        if (node.right != null) stack.push(node.right);
    }
    return result;
}
```

---

## 2.6 Symmetric Tree
**URL:** https://leetcode.com/problems/symmetric-tree/

```java
public boolean isSymmetric(TreeNode root) {
    return root == null || isMirror(root.left, root.right);
}

private boolean isMirror(TreeNode l, TreeNode r) {
    if (l == null && r == null) return true;
    if (l == null || r == null) return false;
    return l.val == r.val
        && isMirror(l.left,  r.right)
        && isMirror(l.right, r.left);
}
```

---

## 2.7 Balanced Binary Tree
**URL:** https://leetcode.com/problems/balanced-binary-tree/

> Return -1 from getHeight() as a sentinel for "unbalanced".

```java
public boolean isBalanced(TreeNode root) {
    return getHeight(root) != -1;
}

private int getHeight(TreeNode node) {
    if (node == null) return 0;
    int left  = getHeight(node.left);
    if (left  == -1) return -1;
    int right = getHeight(node.right);
    if (right == -1) return -1;
    if (Math.abs(left - right) > 1) return -1;
    return Math.max(left, right) + 1;
}
```

---

## 2.8 Closest BST Value
**URL:** https://leetcode.com/problems/closest-binary-search-tree-value/

```java
public int closestValue(TreeNode root, double target) {
    int closest = root.val;
    while (root != null) {
        if (Math.abs(root.val - target) < Math.abs(closest - target))
            closest = root.val;
        root = target < root.val ? root.left : root.right;
    }
    return closest;
}
```

---

## 2.9 Maximum Depth of Binary Tree
**URL:** https://leetcode.com/problems/maximum-depth-of-binary-tree/

```java
public int maxDepth(TreeNode root) {
    if (root == null) return 0;
    return Math.max(maxDepth(root.left), maxDepth(root.right)) + 1;
}
```

---

## 2.10 Minimum Depth of Binary Tree
**URL:** https://leetcode.com/problems/minimum-depth-of-binary-tree/

> A node with only one child is NOT a leaf — must reach a true leaf.

```java
public int minDepth(TreeNode root) {
    if (root == null) return 0;
    if (root.left == null && root.right == null) return 1;
    if (root.left  == null) return minDepth(root.right) + 1;
    if (root.right == null) return minDepth(root.left)  + 1;
    return Math.min(minDepth(root.left), minDepth(root.right)) + 1;
}
```

---

## 2.11 Invert Binary Tree
**URL:** https://leetcode.com/problems/invert-binary-tree/

```java
public TreeNode invertTree(TreeNode root) {
    if (root == null) return null;
    Deque<TreeNode> stack = new ArrayDeque<>();
    stack.push(root);
    while (!stack.isEmpty()) {
        TreeNode node = stack.pop();
        TreeNode tmp  = node.left;
        node.left     = node.right;
        node.right    = tmp;
        if (node.left  != null) stack.push(node.left);
        if (node.right != null) stack.push(node.right);
    }
    return root;
}
```

---

## 2.12 Same Tree
**URL:** https://leetcode.com/problems/same-tree/

```java
public boolean isSameTree(TreeNode p, TreeNode q) {
    if (p == null && q == null) return true;
    if (p == null || q == null) return false;
    return p.val == q.val
        && isSameTree(p.left,  q.left)
        && isSameTree(p.right, q.right);
}
```

---

## 2.13 Lowest Common Ancestor — BST
**URL:** https://leetcode.com/problems/lowest-common-ancestor-of-a-binary-search-tree/

> In BST, LCA is the first node whose value is between p and q.

```java
public TreeNode lowestCommonAncestorBST(TreeNode root, TreeNode p, TreeNode q) {
    while (root != null) {
        if (p.val < root.val && q.val < root.val) root = root.left;
        else if (p.val > root.val && q.val > root.val) root = root.right;
        else return root;
    }
    return null;
}
```

---

## 2.14 Lowest Common Ancestor — Binary Tree
**URL:** https://leetcode.com/problems/lowest-common-ancestor-of-a-binary-tree/

```java
public TreeNode lowestCommonAncestor(TreeNode root, TreeNode p, TreeNode q) {
    if (root == null || root == p || root == q) return root;
    TreeNode left  = lowestCommonAncestor(root.left,  p, q);
    TreeNode right = lowestCommonAncestor(root.right, p, q);
    if (left != null && right != null) return root;
    return left != null ? left : right;
}
```

---

## 2.15 Unique Binary Search Trees (Count)
**URL:** https://leetcode.com/problems/unique-binary-search-trees/

> Catalan number via memoized recursion.

```java
public int numTrees(int n) {
    int[] dp = new int[n + 1];
    dp[0] = dp[1] = 1;
    for (int i = 2; i <= n; i++)
        for (int j = 0; j < i; j++)
            dp[i] += dp[j] * dp[i - 1 - j];
    return dp[n];
}
```

---

## 2.16 Unique Binary Search Trees II (Generate All)
**URL:** https://leetcode.com/problems/unique-binary-search-trees-ii/

```java
public List<TreeNode> generateTrees(int n) {
    if (n == 0) return new ArrayList<>();
    return generate(1, n);
}

private List<TreeNode> generate(int start, int end) {
    List<TreeNode> result = new ArrayList<>();
    if (start > end) { result.add(null); return result; }
    for (int i = start; i <= end; i++) {
        for (TreeNode left  : generate(start, i - 1))
        for (TreeNode right : generate(i + 1, end)) {
            TreeNode root = new TreeNode(i);
            root.left  = left;
            root.right = right;
            result.add(root);
        }
    }
    return result;
}
```

---

## 2.17 Path Sum
**URL:** https://leetcode.com/problems/path-sum/

> Iterative DFS: push (node, runningSum) pairs onto the stack.

```java
public boolean hasPathSum(TreeNode root, int sum) {
    if (root == null) return false;
    Deque<TreeNode> nodes = new ArrayDeque<>();
    Deque<Integer>  sums  = new ArrayDeque<>();
    nodes.push(root);
    sums.push(root.val);
    while (!nodes.isEmpty()) {
        TreeNode node    = nodes.pop();
        int      pathSum = sums.pop();
        if (node.left == null && node.right == null && pathSum == sum) return true;
        if (node.right != null) { nodes.push(node.right); sums.push(pathSum + node.right.val); }
        if (node.left  != null) { nodes.push(node.left);  sums.push(pathSum + node.left.val);  }
    }
    return false;
}
```

---

## 2.18 Path Sum II
**URL:** https://leetcode.com/problems/path-sum-ii/

```java
public List<List<Integer>> pathSum(TreeNode root, int sum) {
    List<List<Integer>> result = new ArrayList<>();
    if (root == null) return result;
    // Stack stores: node, current path list, running sum
    Deque<Object[]> stack = new ArrayDeque<>();
    stack.push(new Object[]{root, new ArrayList<>(List.of(root.val)), root.val});
    while (!stack.isEmpty()) {
        Object[]       entry    = stack.pop();
        TreeNode       node     = (TreeNode)       entry[0];
        List<Integer>  path     = (List<Integer>)  entry[1];
        int            pathSum  = (int)             entry[2];
        if (node.left == null && node.right == null && pathSum == sum) result.add(path);
        if (node.right != null) {
            List<Integer> newPath = new ArrayList<>(path);
            newPath.add(node.right.val);
            stack.push(new Object[]{node.right, newPath, pathSum + node.right.val});
        }
        if (node.left != null) {
            List<Integer> newPath = new ArrayList<>(path);
            newPath.add(node.left.val);
            stack.push(new Object[]{node.left, newPath, pathSum + node.left.val});
        }
    }
    return result;
}
```

---

## 2.19 Binary Tree Maximum Path Sum
**URL:** https://leetcode.com/problems/binary-tree-maximum-path-sum/

> Track global max in a single-element array to allow mutation inside recursion.

```java
public int maxPathSum(TreeNode root) {
    int[] maxSum = {Integer.MIN_VALUE};
    findMax(root, maxSum);
    return maxSum[0];
}

private int findMax(TreeNode node, int[] maxSum) {
    if (node == null) return 0;
    int left  = Math.max(0, findMax(node.left,  maxSum));
    int right = Math.max(0, findMax(node.right, maxSum));
    maxSum[0] = Math.max(maxSum[0], node.val + left + right);
    return node.val + Math.max(left, right);
}
```

---

## 2.20 Binary Tree Level Order Traversal
**URL:** https://leetcode.com/problems/binary-tree-level-order-traversal/

> BFS with a sentinel null to mark level boundaries.

```java
public List<List<Integer>> levelOrder(TreeNode root) {
    List<List<Integer>> result = new ArrayList<>();
    if (root == null) return result;
    Queue<TreeNode> queue = new LinkedList<>();
    queue.offer(root);
    while (!queue.isEmpty()) {
        int size = queue.size();
        List<Integer> level = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            TreeNode node = queue.poll();
            level.add(node.val);
            if (node.left  != null) queue.offer(node.left);
            if (node.right != null) queue.offer(node.right);
        }
        result.add(level);
    }
    return result;
}
```

---

## 2.21 Binary Level Order Traversal II (Bottom-Up)
**URL:** https://leetcode.com/problems/binary-tree-level-order-traversal-ii/

```java
public List<List<Integer>> levelOrderBottom(TreeNode root) {
    LinkedList<List<Integer>> result = new LinkedList<>();
    if (root == null) return result;
    Queue<TreeNode> queue = new LinkedList<>();
    queue.offer(root);
    while (!queue.isEmpty()) {
        int size = queue.size();
        List<Integer> level = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            TreeNode node = queue.poll();
            level.add(node.val);
            if (node.left  != null) queue.offer(node.left);
            if (node.right != null) queue.offer(node.right);
        }
        result.addFirst(level);   // prepend each level
    }
    return result;
}
```

---

## 2.22 Binary Tree Zigzag Level Order Traversal
**URL:** https://leetcode.com/problems/binary-tree-zigzag-level-order-traversal/

```java
public List<List<Integer>> zigzagLevelOrder(TreeNode root) {
    List<List<Integer>> result = new ArrayList<>();
    if (root == null) return result;
    Queue<TreeNode> queue = new LinkedList<>();
    queue.offer(root);
    boolean leftToRight = true;
    while (!queue.isEmpty()) {
        int size = queue.size();
        LinkedList<Integer> level = new LinkedList<>();
        for (int i = 0; i < size; i++) {
            TreeNode node = queue.poll();
            if (leftToRight) level.addLast(node.val);
            else             level.addFirst(node.val);
            if (node.left  != null) queue.offer(node.left);
            if (node.right != null) queue.offer(node.right);
        }
        result.add(level);
        leftToRight = !leftToRight;
    }
    return result;
}
```

---

## 2.23 Binary Tree Right Side View
**URL:** https://leetcode.com/problems/binary-tree-right-side-view/

```java
public List<Integer> rightSideView(TreeNode root) {
    List<Integer> result = new ArrayList<>();
    if (root == null) return result;
    Queue<TreeNode> queue = new LinkedList<>();
    queue.offer(root);
    while (!queue.isEmpty()) {
        int size = queue.size();
        for (int i = 0; i < size; i++) {
            TreeNode node = queue.poll();
            if (i == size - 1) result.add(node.val);   // last in level = rightmost
            if (node.left  != null) queue.offer(node.left);
            if (node.right != null) queue.offer(node.right);
        }
    }
    return result;
}
```

---

## 2.24 Sum Root to Leaf Numbers
**URL:** https://leetcode.com/problems/sum-root-to-leaf-numbers/

```java
public int sumNumbers(TreeNode root) {
    if (root == null) return 0;
    int total = 0;
    Deque<TreeNode> nodes = new ArrayDeque<>();
    Deque<Integer>  nums  = new ArrayDeque<>();
    nodes.push(root);
    nums.push(root.val);
    while (!nodes.isEmpty()) {
        TreeNode node = nodes.pop();
        int      num  = nums.pop();
        if (node.left == null && node.right == null) { total += num; continue; }
        if (node.right != null) { nodes.push(node.right); nums.push(num * 10 + node.right.val); }
        if (node.left  != null) { nodes.push(node.left);  nums.push(num * 10 + node.left.val);  }
    }
    return total;
}
```

---

## 2.25 Validate Binary Search Tree
**URL:** https://leetcode.com/problems/validate-binary-search-tree/

> Inorder traversal: each visited value must be greater than the last printed.

```java
public boolean isValidBST(TreeNode root) {
    long[] lastPrinted = {Long.MIN_VALUE};
    return validate(root, lastPrinted);
}

private boolean validate(TreeNode node, long[] last) {
    if (node == null) return true;
    if (!validate(node.left, last)) return false;
    if (node.val <= last[0]) return false;
    last[0] = node.val;
    return validate(node.right, last);
}
```

---

## 2.26 Convert Sorted Array to BST
**URL:** https://leetcode.com/problems/convert-sorted-array-to-binary-search-tree/

```java
public TreeNode sortedArrayToBST(int[] nums) {
    return toBST(nums, 0, nums.length - 1);
}

private TreeNode toBST(int[] nums, int start, int end) {
    if (start > end) return null;
    int mid = start + (end - start) / 2;
    TreeNode node = new TreeNode(nums[mid]);
    node.left  = toBST(nums, start, mid - 1);
    node.right = toBST(nums, mid + 1, end);
    return node;
}
```

---

## 2.27 Flatten Binary Tree to Linked List
**URL:** https://leetcode.com/problems/flatten-binary-tree-to-linked-list/

> Iterative: when a node has a left child, move it to the right; push old right subtree onto stack.

```java
public void flatten(TreeNode root) {
    if (root == null) return;
    Deque<TreeNode> stack = new ArrayDeque<>();
    TreeNode curr = root;
    while (curr != null || !stack.isEmpty()) {
        if (curr.right != null) stack.push(curr.right);
        if (curr.left  != null) { curr.right = curr.left; curr.left = null; }
        else if (!stack.isEmpty()) { curr.right = stack.pop(); }
        curr = curr.right;
    }
}
```

---

## 2.28 Construct Binary Tree from Preorder and Inorder
**URL:** https://leetcode.com/problems/construct-binary-tree-from-preorder-and-inorder-traversal/

```java
public TreeNode buildTree(int[] preorder, int[] inorder) {
    Map<Integer, Integer> indexMap = new HashMap<>();
    for (int i = 0; i < inorder.length; i++) indexMap.put(inorder[i], i);
    return build(preorder, 0, preorder.length - 1,
                 inorder,  0, inorder.length  - 1, indexMap);
}

private TreeNode build(int[] pre, int pLow, int pHigh,
                       int[] in,  int iLow, int iHigh,
                       Map<Integer,Integer> map) {
    if (pLow > pHigh || iLow > iHigh) return null;
    TreeNode root = new TreeNode(pre[pLow]);
    int divIdx    = map.get(root.val);
    int leftSize  = divIdx - iLow;
    root.left  = build(pre, pLow + 1,            pLow + leftSize, in, iLow,       divIdx - 1, map);
    root.right = build(pre, pLow + leftSize + 1, pHigh,           in, divIdx + 1, iHigh,      map);
    return root;
}
```

---

## 2.29 Construct Binary Tree from Inorder and Postorder
**URL:** https://leetcode.com/problems/construct-binary-tree-from-inorder-and-postorder-traversal/

```java
public TreeNode buildTreePostorder(int[] inorder, int[] postorder) {
    Map<Integer, Integer> map = new HashMap<>();
    for (int i = 0; i < inorder.length; i++) map.put(inorder[i], i);
    return buildPost(inorder, 0, inorder.length - 1,
                     postorder, 0, postorder.length - 1, map);
}

private TreeNode buildPost(int[] in, int iLow, int iHigh,
                            int[] post, int pLow, int pHigh,
                            Map<Integer,Integer> map) {
    if (pLow > pHigh || iLow > iHigh) return null;
    TreeNode root  = new TreeNode(post[pHigh]);
    int divIdx     = map.get(root.val);
    int rightSize  = iHigh - divIdx;
    root.right = buildPost(in, divIdx + 1, iHigh, post, pHigh - rightSize,     pHigh - 1, map);
    root.left  = buildPost(in, iLow, divIdx - 1,  post, pLow,                  pHigh - rightSize - 1, map);
    return root;
}
```

---

## 2.30 Binary Tree Paths
**URL:** https://leetcode.com/problems/binary-tree-paths/

```java
public List<String> binaryTreePaths(TreeNode root) {
    List<String> paths = new ArrayList<>();
    if (root == null) return paths;
    Deque<TreeNode> nodes = new ArrayDeque<>();
    Deque<String>   strs  = new ArrayDeque<>();
    nodes.push(root);
    strs.push(String.valueOf(root.val));
    while (!nodes.isEmpty()) {
        TreeNode node = nodes.pop();
        String   path = strs.pop();
        if (node.left == null && node.right == null) { paths.add(path); continue; }
        if (node.right != null) { nodes.push(node.right); strs.push(path + "->" + node.right.val); }
        if (node.left  != null) { nodes.push(node.left);  strs.push(path + "->" + node.left.val);  }
    }
    return paths;
}
```

---

## 2.31 Recover Binary Search Tree
**URL:** https://leetcode.com/problems/recover-binary-search-tree/

> Inorder traversal finds exactly two nodes out of order (prev > curr). Swap their values.

```java
private TreeNode prev = null, node1 = null, node2 = null;

public void recoverTree(TreeNode root) {
    recoverHelper(root);
    int tmp = node1.val; node1.val = node2.val; node2.val = tmp;
}

private void recoverHelper(TreeNode node) {
    if (node == null) return;
    recoverHelper(node.left);
    if (prev != null && prev.val > node.val) {
        if (node1 == null) node1 = prev;
        node2 = node;
    }
    prev = node;
    recoverHelper(node.right);
}
```

---

## 2.32 Kth Smallest Element in a BST
**URL:** https://leetcode.com/problems/kth-smallest-element-in-a-bst/

> Iterative inorder — pop when counter reaches k.

```java
public int kthSmallest(TreeNode root, int k) {
    Deque<TreeNode> stack = new ArrayDeque<>();
    int count = 0;
    while (!stack.isEmpty() || root != null) {
        if (root != null) { stack.push(root); root = root.left; }
        else {
            root = stack.pop();
            if (++count == k) return root.val;
            root = root.right;
        }
    }
    return -1;
}
```

---

## 2.33 House Robber III
**URL:** https://leetcode.com/problems/house-robber-iii/

> Each call returns [robThisNode, skipThisNode] profit pair.

```java
public int rob(TreeNode root) {
    int[] res = robMax(root);
    return Math.max(res[0], res[1]);
}

private int[] robMax(TreeNode node) {
    if (node == null) return new int[]{0, 0};
    int[] left  = robMax(node.left);
    int[] right = robMax(node.right);
    // rob this node: must skip children
    int robThis  = node.val + left[1] + right[1];
    // skip this node: take best of each child
    int skipThis = Math.max(left[0], left[1]) + Math.max(right[0], right[1]);
    return new int[]{robThis, skipThis};
}
```

---

## 2.34 Inorder Successor in BST
**URL:** https://leetcode.com/problems/inorder-successor-in-bst/

```java
public TreeNode inorderSuccessor(TreeNode root, TreeNode p) {
    TreeNode successor = null;
    while (root != null && root.val != p.val) {
        if (root.val > p.val) { successor = root; root = root.left; }
        else                    root = root.right;
    }
    if (root == null) return null;
    if (root.right == null) return successor;
    root = root.right;
    while (root.left != null) root = root.left;
    return root;
}
```

---

## 2.35 Binary Tree Longest Consecutive Sequence
**URL:** https://leetcode.com/problems/binary-tree-longest-consecutive-sequence/

> BFS: track the consecutive length arriving at each node.

```java
public int longestConsecutive(TreeNode root) {
    if (root == null) return 0;
    int maxLen = 1;
    Queue<TreeNode> nodeQ = new LinkedList<>();
    Queue<Integer>  lenQ  = new LinkedList<>();
    nodeQ.offer(root); lenQ.offer(1);
    while (!nodeQ.isEmpty()) {
        TreeNode node = nodeQ.poll();
        int      len  = lenQ.poll();
        for (TreeNode child : new TreeNode[]{node.left, node.right}) {
            if (child == null) continue;
            int childLen = (node.val == child.val - 1) ? len + 1 : 1;
            maxLen = Math.max(maxLen, childLen);
            nodeQ.offer(child); lenQ.offer(childLen);
        }
    }
    return maxLen;
}
```

---

## 2.36 Verify Preorder Sequence in BST
**URL:** https://leetcode.com/problems/verify-preorder-sequence-in-binary-search-tree/

> Simulate BST traversal: use a stack as the path; maintain a lower-bound root.

```java
public boolean verifyPreorder(int[] preorder) {
    Deque<Integer> stack = new ArrayDeque<>();
    int root = Integer.MIN_VALUE;
    for (int val : preorder) {
        if (val < root) return false;
        while (!stack.isEmpty() && stack.peek() < val) root = stack.pop();
        stack.push(val);
    }
    return true;
}
```

---

## 2.37 Binary Tree Upside Down
**URL:** https://leetcode.com/problems/binary-tree-upside-down/

```java
public TreeNode upsideDownBinaryTree(TreeNode root) {
    TreeNode p = root, parent = null, parentRight = null;
    while (p != null) {
        TreeNode left    = p.left;
        p.left           = parentRight;
        parentRight      = p.right;
        p.right          = parent;
        parent           = p;
        p                = left;
    }
    return parent;
}
```

---

## 2.38 Count Univalue Subtrees
**URL:** https://leetcode.com/problems/count-univalue-subtrees/

```java
private int count = 0;

public int countUnivalSubtrees(TreeNode root) {
    isUnivalue(root);
    return count;
}

private boolean isUnivalue(TreeNode node) {
    if (node == null) return true;
    if (node.left == null && node.right == null) { count++; return true; }
    boolean left  = isUnivalue(node.left);
    boolean right = isUnivalue(node.right);
    if (left && right
            && (node.left  == null || node.left.val  == node.val)
            && (node.right == null || node.right.val == node.val)) {
        count++;
        return true;
    }
    return false;
}
```


# 03 — Graphs

---

## 3.1 Number of Connected Components in an Undirected Graph
**URL:** https://leetcode.com/problems/number-of-connected-components-in-an-undirected-graph/

> Build adjacency list; BFS from each unvisited node; count connected components.

```java
public int countComponents(int n, int[][] edges) {
    List<List<Integer>> adj = new ArrayList<>();
    for (int i = 0; i < n; i++) adj.add(new ArrayList<>());
    for (int[] e : edges) {
        adj.get(e[0]).add(e[1]);
        adj.get(e[1]).add(e[0]);
    }
    boolean[] visited = new boolean[n];
    int count = 0;
    for (int i = 0; i < n; i++) {
        if (!visited[i]) { bfs(i, adj, visited); count++; }
    }
    return count;
}

private void bfs(int start, List<List<Integer>> adj, boolean[] visited) {
    Queue<Integer> queue = new LinkedList<>();
    queue.offer(start);
    visited[start] = true;
    while (!queue.isEmpty()) {
        int node = queue.poll();
        for (int nbr : adj.get(node)) {
            if (!visited[nbr]) { visited[nbr] = true; queue.offer(nbr); }
        }
    }
}
```

---

## 3.2 Course Schedule (Cycle Detection)
**URL:** https://leetcode.com/problems/course-schedule/

> Directed graph: if a DFS finds a back-edge (gray node) → cycle → cannot finish.

```java
public boolean canFinish(int numCourses, int[][] prerequisites) {
    List<List<Integer>> adj = new ArrayList<>();
    for (int i = 0; i < numCourses; i++) adj.add(new ArrayList<>());
    for (int[] p : prerequisites) adj.get(p[1]).add(p[0]);

    // 0 = white, 1 = gray (in-progress), 2 = black (done)
    int[] color = new int[numCourses];
    for (int i = 0; i < numCourses; i++)
        if (color[i] == 0 && hasCycle(i, adj, color)) return false;
    return true;
}

private boolean hasCycle(int node, List<List<Integer>> adj, int[] color) {
    color[node] = 1;
    for (int nbr : adj.get(node)) {
        if (color[nbr] == 1) return true;
        if (color[nbr] == 0 && hasCycle(nbr, adj, color)) return true;
    }
    color[node] = 2;
    return false;
}
```

---

## 3.3 Graph Valid Tree
**URL:** https://leetcode.com/problems/graph-valid-tree/

> A valid tree has exactly n-1 edges AND is fully connected (single component, no cycles).

```java
public boolean validTree(int n, int[][] edges) {
    if (edges.length != n - 1) return false;   // tree must have exactly n-1 edges
    List<List<Integer>> adj = new ArrayList<>();
    for (int i = 0; i < n; i++) adj.add(new ArrayList<>());
    for (int[] e : edges) {
        adj.get(e[0]).add(e[1]);
        adj.get(e[1]).add(e[0]);
    }
    boolean[] visited = new boolean[n];
    Deque<Integer> stack = new ArrayDeque<>();
    stack.push(0);
    visited[0] = true;
    int count = 1;
    while (!stack.isEmpty()) {
        int node = stack.pop();
        for (int nbr : adj.get(node)) {
            if (!visited[nbr]) { visited[nbr] = true; stack.push(nbr); count++; }
        }
    }
    return count == n;   // all nodes reachable = connected
}
```

---

## 3.4 Course Schedule II (Topological Sort)
**URL:** https://leetcode.com/problems/course-schedule-ii/

> Kahn's algorithm: repeatedly dequeue nodes with in-degree 0. If all nodes processed → no cycle.

```java
public int[] findOrder(int numCourses, int[][] prerequisites) {
    List<List<Integer>> adj = new ArrayList<>();
    int[] indegree = new int[numCourses];
    for (int i = 0; i < numCourses; i++) adj.add(new ArrayList<>());
    for (int[] p : prerequisites) {
        adj.get(p[1]).add(p[0]);
        indegree[p[0]]++;
    }
    Queue<Integer> queue = new LinkedList<>();
    for (int i = 0; i < numCourses; i++) if (indegree[i] == 0) queue.offer(i);

    int[] order = new int[numCourses];
    int   idx   = 0;
    while (!queue.isEmpty()) {
        int course = queue.poll();
        order[idx++] = course;
        for (int nbr : adj.get(course)) {
            if (--indegree[nbr] == 0) queue.offer(nbr);
        }
    }
    return idx == numCourses ? order : new int[]{};
}
```

---

## 3.5 Number of Islands
**URL:** https://leetcode.com/problems/number-of-islands/

> DFS flood-fill: mark visited cells as '0' (or use a separate boolean matrix).

```java
public int numIslands(char[][] grid) {
    if (grid == null || grid.length == 0) return 0;
    int count = 0;
    for (int i = 0; i < grid.length; i++)
        for (int j = 0; j < grid[0].length; j++)
            if (grid[i][j] == '1') { dfs(grid, i, j); count++; }
    return count;
}

private void dfs(char[][] grid, int r, int c) {
    if (r < 0 || r >= grid.length || c < 0 || c >= grid[0].length || grid[r][c] != '1') return;
    grid[r][c] = '0';   // mark visited
    dfs(grid, r - 1, c);
    dfs(grid, r + 1, c);
    dfs(grid, r, c - 1);
    dfs(grid, r, c + 1);
}
```

# 04 — Heaps

---

## 4.1 Merge K Sorted Linked Lists
**URL:** https://leetcode.com/problems/merge-k-sorted-lists/

> Use a min-heap (PriorityQueue) seeded with the head of each list.
> Each pop gives the current minimum; push its next node to maintain the invariant.

```java
public ListNode mergeKLists(ListNode[] lists) {
    // PriorityQueue ordered by node value
    PriorityQueue<ListNode> pq = new PriorityQueue<>(Comparator.comparingInt(n -> n.val));
    for (ListNode node : lists) if (node != null) pq.offer(node);

    ListNode dummy = new ListNode(0), curr = dummy;
    while (!pq.isEmpty()) {
        ListNode node = pq.poll();
        curr.next = node;
        curr = curr.next;
        if (node.next != null) pq.offer(node.next);
    }
    return dummy.next;
}
```

**Complexity:** O(N log k) time, O(k) heap space  
where N = total nodes, k = number of lists.

---

## 4.2 Kth Largest Element in an Array
**URL:** https://leetcode.com/problems/kth-largest-element-in-an-array/

> Maintain a min-heap of size k. After processing all elements, the heap root is the kth largest.

```java
public int findKthLargest(int[] nums, int k) {
    PriorityQueue<Integer> minHeap = new PriorityQueue<>();   // min-heap (natural order)
    for (int num : nums) {
        minHeap.offer(num);
        if (minHeap.size() > k) minHeap.poll();   // evict smallest, keep top-k
    }
    return minHeap.peek();   // root = kth largest
}
```

**Complexity:** O(N log k) time, O(k) space.

> **Alternative — QuickSelect O(N) average:**
```java
public int findKthLargestQuickSelect(int[] nums, int k) {
    int target = nums.length - k;   // kth largest = (n-k)th smallest
    int lo = 0, hi = nums.length - 1;
    while (lo < hi) {
        int pivot = partition(nums, lo, hi);
        if      (pivot == target) return nums[pivot];
        else if (pivot  < target) lo = pivot + 1;
        else                      hi = pivot - 1;
    }
    return nums[lo];
}

private int partition(int[] nums, int lo, int hi) {
    int pivot = nums[hi], i = lo;
    for (int j = lo; j < hi; j++) {
        if (nums[j] <= pivot) { int tmp = nums[i]; nums[i++] = nums[j]; nums[j] = tmp; }
    }
    int tmp = nums[i]; nums[i] = nums[hi]; nums[hi] = tmp;
    return i;
}
```


# 05 — Arrays

---

## 5.1 Two Sum
**URL:** https://leetcode.com/problems/two-sum/

```java
public int[] twoSum(int[] nums, int target) {
    Map<Integer, Integer> map = new HashMap<>();
    for (int i = 0; i < nums.length; i++) {
        int complement = target - nums[i];
        if (map.containsKey(complement)) return new int[]{map.get(complement), i};
        map.put(nums[i], i);
    }
    return new int[]{};
}
```

---

## 5.2 Two Sum II (Sorted Input)
**URL:** https://leetcode.com/problems/two-sum-ii-input-array-is-sorted/

```java
public int[] twoSumII(int[] numbers, int target) {
    int lo = 0, hi = numbers.length - 1;
    while (lo < hi) {
        int sum = numbers[lo] + numbers[hi];
        if      (sum == target) return new int[]{lo + 1, hi + 1};
        else if (sum  < target) lo++;
        else                    hi--;
    }
    return new int[]{-1, -1};
}
```

---

## 5.3 Two Sum III (Data Structure Design)
**URL:** https://leetcode.com/problems/two-sum-iii-data-structure-design/

```java
class TwoSum {
    private final Map<Integer, Integer> counts = new HashMap<>();

    public void add(int number) {
        counts.merge(number, 1, Integer::sum);
    }

    public boolean find(int value) {
        for (int num : counts.keySet()) {
            int target = value - num;
            if (counts.containsKey(target) && (num != target || counts.get(num) > 1))
                return true;
        }
        return false;
    }
}
```

---

## 5.4 3 Sum
**URL:** https://leetcode.com/problems/3sum/

> Sort, then for each anchor i use two-pointer for the remaining pair. Skip duplicates.

```java
public List<List<Integer>> threeSum(int[] nums) {
    Arrays.sort(nums);
    List<List<Integer>> result = new ArrayList<>();
    for (int i = 0; i < nums.length - 2; i++) {
        if (i > 0 && nums[i] == nums[i - 1]) continue;  // skip duplicate anchors
        int lo = i + 1, hi = nums.length - 1;
        while (lo < hi) {
            int sum = nums[i] + nums[lo] + nums[hi];
            if      (sum == 0) {
                result.add(Arrays.asList(nums[i], nums[lo], nums[hi]));
                while (lo < hi && nums[lo] == nums[lo + 1]) lo++;
                while (lo < hi && nums[hi] == nums[hi - 1]) hi--;
                lo++; hi--;
            }
            else if (sum < 0) lo++;
            else              hi--;
        }
    }
    return result;
}
```

---

## 5.5 3 Sum Closest
**URL:** https://leetcode.com/problems/3sum-closest/

```java
public int threeSumClosest(int[] nums, int target) {
    Arrays.sort(nums);
    int closest = nums[0] + nums[1] + nums[2];
    for (int i = 0; i < nums.length - 2; i++) {
        int lo = i + 1, hi = nums.length - 1;
        while (lo < hi) {
            int sum  = nums[i] + nums[lo] + nums[hi];
            if (Math.abs(sum - target) < Math.abs(closest - target)) closest = sum;
            if      (sum == target) return sum;
            else if (sum  < target) lo++;
            else                    hi--;
        }
    }
    return closest;
}
```

---

## 5.6 3 Sum Smaller
**URL:** https://leetcode.com/problems/3sum-smaller/

> When sum < target, ALL pairs (lo, lo+1..hi) qualify → add (hi - lo) to count.

```java
public int threeSumSmaller(int[] nums, int target) {
    Arrays.sort(nums);
    int count = 0;
    for (int i = 0; i < nums.length - 2; i++) {
        int lo = i + 1, hi = nums.length - 1;
        while (lo < hi) {
            if (nums[i] + nums[lo] + nums[hi] < target) { count += hi - lo; lo++; }
            else hi--;
        }
    }
    return count;
}
```

---

## 5.7 Contains Duplicate
**URL:** https://leetcode.com/problems/contains-duplicate/

```java
public boolean containsDuplicate(int[] nums) {
    Set<Integer> seen = new HashSet<>();
    for (int n : nums) if (!seen.add(n)) return true;
    return false;
}
```

---

## 5.8 Contains Duplicate II
**URL:** https://leetcode.com/problems/contains-duplicate-ii/

```java
public boolean containsNearbyDuplicate(int[] nums, int k) {
    Map<Integer, Integer> indexMap = new HashMap<>();
    for (int i = 0; i < nums.length; i++) {
        if (indexMap.containsKey(nums[i]) && i - indexMap.get(nums[i]) <= k) return true;
        indexMap.put(nums[i], i);
    }
    return false;
}
```

---

## 5.9 Rotate Array
**URL:** https://leetcode.com/problems/rotate-array/

> Three-reverse trick: reverse [0..n-k-1], reverse [n-k..n-1], reverse all.

```java
public void rotate(int[] nums, int k) {
    int n = nums.length;
    k %= n;
    if (k == 0) return;
    reverse(nums, 0, n - k - 1);
    reverse(nums, n - k, n - 1);
    reverse(nums, 0, n - 1);
}

private void reverse(int[] nums, int lo, int hi) {
    while (lo < hi) { int tmp = nums[lo]; nums[lo++] = nums[hi]; nums[hi--] = tmp; }
}
```

---

## 5.10 Majority Element
**URL:** https://leetcode.com/problems/majority-element/

> Boyer-Moore Voting: candidate with count > 0 survives.

```java
public int majorityElement(int[] nums) {
    int candidate = nums[0], count = 1;
    for (int i = 1; i < nums.length; i++) {
        if (count == 0) { candidate = nums[i]; count = 1; }
        else count += (nums[i] == candidate) ? 1 : -1;
    }
    return candidate;
}
```

---

## 5.11 Remove Duplicates from Sorted Array
**URL:** https://leetcode.com/problems/remove-duplicates-from-sorted-array/

```java
public int removeDuplicates(int[] nums) {
    if (nums.length == 0) return 0;
    int j = 0;
    for (int i = 1; i < nums.length; i++)
        if (nums[i] != nums[j]) nums[++j] = nums[i];
    return j + 1;
}
```

---

## 5.12 Remove Element
**URL:** https://leetcode.com/problems/remove-element/

```java
public int removeElement(int[] nums, int val) {
    int i = 0;
    for (int j = 0; j < nums.length; j++)
        if (nums[j] != val) nums[i++] = nums[j];
    return i;
}
```

---

## 5.13 Move Zeroes
**URL:** https://leetcode.com/problems/move-zeroes/

```java
public void moveZeroes(int[] nums) {
    int i = 0;
    for (int j = 0; j < nums.length; j++)
        if (nums[j] != 0) nums[i++] = nums[j];
    while (i < nums.length) nums[i++] = 0;
}
```

---

## 5.14 Plus One
**URL:** https://leetcode.com/problems/plus-one/

```java
public int[] plusOne(int[] digits) {
    for (int i = digits.length - 1; i >= 0; i--) {
        if (digits[i] < 9) { digits[i]++; return digits; }
        digits[i] = 0;
    }
    int[] result = new int[digits.length + 1];
    result[0] = 1;
    return result;
}
```

---

## 5.15 Best Time to Buy and Sell Stock I
**URL:** https://leetcode.com/problems/best-time-to-buy-and-sell-stock/

```java
public int maxProfit(int[] prices) {
    int minPrice = Integer.MAX_VALUE, maxProfit = 0;
    for (int price : prices) {
        minPrice  = Math.min(minPrice, price);
        maxProfit = Math.max(maxProfit, price - minPrice);
    }
    return maxProfit;
}
```

---

## 5.16 Best Time to Buy and Sell Stock II (Unlimited Transactions)
**URL:** https://leetcode.com/problems/best-time-to-buy-and-sell-stock-ii/

```java
public int maxProfitII(int[] prices) {
    int profit = 0;
    for (int i = 1; i < prices.length; i++)
        if (prices[i] > prices[i - 1]) profit += prices[i] - prices[i - 1];
    return profit;
}
```

---

## 5.17 Container With Most Water
**URL:** https://leetcode.com/problems/container-with-most-water/

```java
public int maxArea(int[] height) {
    int lo = 0, hi = height.length - 1, maxArea = 0;
    while (lo < hi) {
        maxArea = Math.max(maxArea, Math.min(height[lo], height[hi]) * (hi - lo));
        if (height[lo] < height[hi]) lo++;
        else                          hi--;
    }
    return maxArea;
}
```

---

## 5.18 Product of Array Except Self
**URL:** https://leetcode.com/problems/product-of-array-except-self/

> Two-pass prefix/suffix product without division.

```java
public int[] productExceptSelf(int[] nums) {
    int n = nums.length;
    int[] result = new int[n];
    Arrays.fill(result, 1);
    int left = 1;
    for (int i = 0; i < n; i++) { result[i] = left; left *= nums[i]; }
    int right = 1;
    for (int i = n - 1; i >= 0; i--) { result[i] *= right; right *= nums[i]; }
    return result;
}
```

---

## 5.19 Trapping Rain Water
**URL:** https://leetcode.com/problems/trapping-rain-water/

> For each bar, water = min(maxLeft, maxRight) - height. Precompute maxRight array.

```java
public int trap(int[] height) {
    int n = height.length;
    int[] maxRight = new int[n];
    maxRight[n - 1] = height[n - 1];
    for (int i = n - 2; i >= 0; i--)
        maxRight[i] = Math.max(maxRight[i + 1], height[i]);
    int water = 0, maxLeft = 0;
    for (int i = 0; i < n; i++) {
        water  += Math.max(0, Math.min(maxRight[i], maxLeft) - height[i]);
        maxLeft = Math.max(maxLeft, height[i]);
    }
    return water;
}
```

---

## 5.20 Maximum Subarray (Kadane's Algorithm)
**URL:** https://leetcode.com/problems/maximum-subarray/

```java
public int maxSubArray(int[] nums) {
    int maxSum = nums[0], curr = nums[0];
    for (int i = 1; i < nums.length; i++) {
        curr   = Math.max(nums[i], curr + nums[i]);
        maxSum = Math.max(maxSum, curr);
    }
    return maxSum;
}
```

---

## 5.21 Find Minimum in Rotated Sorted Array
**URL:** https://leetcode.com/problems/find-minimum-in-rotated-sorted-array/

```java
public int findMin(int[] nums) {
    int lo = 0, hi = nums.length - 1;
    while (lo < hi) {
        int mid = lo + (hi - lo) / 2;
        if (nums[mid] >= nums[hi]) lo = mid + 1;
        else                        hi = mid;
    }
    return nums[lo];
}
```

---

## 5.22 Merge Sorted Arrays
**URL:** https://leetcode.com/problems/merge-sorted-array/

> Fill from the back to avoid shifting elements.

```java
public void merge(int[] nums1, int m, int[] nums2, int n) {
    int i = m - 1, j = n - 1, k = m + n - 1;
    while (i >= 0 && j >= 0)
        nums1[k--] = (nums1[i] >= nums2[j]) ? nums1[i--] : nums2[j--];
    while (j >= 0) nums1[k--] = nums2[j--];
}
```

---

## 5.23 Intersection of Two Arrays
**URL:** https://leetcode.com/problems/intersection-of-two-arrays/

```java
public int[] intersection(int[] nums1, int[] nums2) {
    Set<Integer> set = new HashSet<>();
    for (int n : nums1) set.add(n);
    Set<Integer> result = new HashSet<>();
    for (int n : nums2) if (set.contains(n)) result.add(n);
    return result.stream().mapToInt(Integer::intValue).toArray();
}
```

---

## 5.24 Intersection of Two Arrays II
**URL:** https://leetcode.com/problems/intersection-of-two-arrays-ii/

```java
public int[] intersect(int[] nums1, int[] nums2) {
    Arrays.sort(nums1); Arrays.sort(nums2);
    List<Integer> list = new ArrayList<>();
    int i = 0, j = 0;
    while (i < nums1.length && j < nums2.length) {
        if      (nums1[i] < nums2[j]) i++;
        else if (nums2[j] < nums1[i]) j++;
        else    { list.add(nums1[i++]); j++; }
    }
    return list.stream().mapToInt(Integer::intValue).toArray();
}
```

---

## 5.25 Shortest Word Distance
**URL:** https://leetcode.com/problems/shortest-word-distance/

```java
public int shortestDistance(String[] words, String word1, String word2) {
    int minDist = Integer.MAX_VALUE, pos1 = -1, pos2 = -1;
    for (int i = 0; i < words.length; i++) {
        if (words[i].equals(word1)) pos1 = i;
        if (words[i].equals(word2)) pos2 = i;
        if (pos1 != -1 && pos2 != -1)
            minDist = Math.min(minDist, Math.abs(pos1 - pos2));
    }
    return minDist;
}
```

---

## 5.26 Pascal's Triangle
**URL:** https://leetcode.com/problems/pascals-triangle/

```java
public List<List<Integer>> generate(int numRows) {
    List<List<Integer>> result = new ArrayList<>();
    for (int i = 0; i < numRows; i++) {
        List<Integer> row = new ArrayList<>();
        for (int j = 0; j <= i; j++)
            row.add(j == 0 || j == i ? 1
                    : result.get(i - 1).get(j - 1) + result.get(i - 1).get(j));
        result.add(row);
    }
    return result;
}
```

---

## 5.27 Pascal's Triangle II (kth Row)
**URL:** https://leetcode.com/problems/pascals-triangle-ii/

```java
public List<Integer> getRow(int rowIndex) {
    List<Integer> row = new ArrayList<>(Collections.nCopies(rowIndex + 1, 0));
    row.set(0, 1);
    for (int i = 1; i <= rowIndex; i++)
        for (int j = i; j >= 1; j--)
            row.set(j, row.get(j) + row.get(j - 1));
    return row;
}
```

---

## 5.28 Summary Ranges
**URL:** https://leetcode.com/problems/summary-ranges/

```java
public List<String> summaryRanges(int[] nums) {
    List<String> result = new ArrayList<>();
    if (nums.length == 0) return result;
    int start = nums[0];
    for (int i = 1; i <= nums.length; i++) {
        if (i == nums.length || nums[i] - nums[i - 1] != 1) {
            int end = nums[i - 1];
            result.add(start == end ? String.valueOf(start) : start + "->" + end);
            if (i < nums.length) start = nums[i];
        }
    }
    return result;
}
```

---

## 5.29 Missing Number
**URL:** https://leetcode.com/problems/missing-number/

> XOR all indices 0..n and all elements; duplicates cancel, leaving the missing value.

```java
public int missingNumber(int[] nums) {
    int xor = 0;
    for (int i = 0; i <= nums.length; i++) xor ^= i;
    for (int n : nums)                     xor ^= n;
    return xor;
}
```

---

## 5.30 Nested List Weight Sum
**URL:** https://leetcode.com/problems/nested-list-weight-sum/

```java
// Assuming NestedInteger interface is provided by the platform
public int depthSum(List<NestedInteger> nestedList) {
    return depthHelper(nestedList, 1);
}

private int depthHelper(List<NestedInteger> list, int depth) {
    int sum = 0;
    for (NestedInteger ni : list) {
        if (ni.isInteger()) sum += ni.getInteger() * depth;
        else                sum += depthHelper(ni.getList(), depth + 1);
    }
    return sum;
}
```

# 05b — Arrays Supplement

> This file covers **Nested List Weight Sum II**, which appeared in the PDF (section 1.6.17)
> but had no Python implementation. The original Nested List Weight Sum I is in `05_Arrays.md`.

---

## Nested List Weight Sum II
**URL:** https://leetcode.com/problems/nested-list-weight-sum-ii/

The weight is defined **bottom-up**: leaf-level integers have weight 1, root-level integers
have the largest weight. This is the inverse of Nested List Weight Sum I.

**Example 1:** `[[1,1],2,[1,1]]` → `8`  
(four 1s at depth 1 → weight 2, one 2 at depth 0 → weight... wait, depth is inverted)  
Actually: 2 is at the outermost level (depth=2 in bottom-up), 1s are one level deeper (depth=1).  
`4×1×1 + 1×2×2 = 4 + 4 = 8` ✓

**Example 2:** `[1,[4,[6]]]` → `17`  
1 at depth 3, 4 at depth 2, 6 at depth 1: `1×3 + 4×2 + 6×1 = 17` ✓

---

### Approach 1 — Two-Pass (find max depth, then compute)

```java
public int depthSumInverse(List<NestedInteger> nestedList) {
    int maxDepth = findMaxDepth(nestedList);
    return weightedSum(nestedList, maxDepth);
}

private int findMaxDepth(List<NestedInteger> list) {
    int depth = 1;
    for (NestedInteger ni : list) {
        if (!ni.isInteger())
            depth = Math.max(depth, 1 + findMaxDepth(ni.getList()));
    }
    return depth;
}

private int weightedSum(List<NestedInteger> list, int weight) {
    int sum = 0;
    for (NestedInteger ni : list) {
        if (ni.isInteger()) sum += ni.getInteger() * weight;
        else                sum += weightedSum(ni.getList(), weight - 1);
    }
    return sum;
}
```

---

### Approach 2 — BFS with Accumulation (One-Pass, O(n))

> Key insight: instead of computing depth up-front, accumulate a running `unweighted` sum
> each level. The final answer = sum of all per-level unweighted totals, each added once
> per remaining level below it.

```java
public int depthSumInverseBFS(List<NestedInteger> nestedList) {
    Queue<NestedInteger> queue = new LinkedList<>(nestedList);
    int totalSum = 0, levelSum = 0;

    while (!queue.isEmpty()) {
        int size = queue.size();
        levelSum = 0;
        for (int i = 0; i < size; i++) {
            NestedInteger ni = queue.poll();
            if (ni.isInteger()) {
                levelSum += ni.getInteger();
            } else {
                queue.addAll(ni.getList());
            }
        }
        totalSum += levelSum;   // each level's integers get +1 weight for every deeper level
    }
    // totalSum accumulates: deepest integers added once per BFS round above them
    return totalSum;
}
```

**Why this works:** An integer at depth `d` (1-indexed from deepest) is added to `totalSum`
exactly `d` times — once for each BFS round from its own level back to the root. That
is exactly the bottom-up weight.

---

### Approach 3 — DFS with `(maxDepth - depth + 1)` weight

```java
private int maxDepth = 0;

public int depthSumInverseDFS(List<NestedInteger> nestedList) {
    findDepth(nestedList, 1);
    return dfsSum(nestedList, 1);
}

private void findDepth(List<NestedInteger> list, int depth) {
    maxDepth = Math.max(maxDepth, depth);
    for (NestedInteger ni : list)
        if (!ni.isInteger()) findDepth(ni.getList(), depth + 1);
}

private int dfsSum(List<NestedInteger> list, int depth) {
    int sum = 0;
    for (NestedInteger ni : list) {
        if (ni.isInteger()) sum += ni.getInteger() * (maxDepth - depth + 1);
        else                sum += dfsSum(ni.getList(), depth + 1);
    }
    return sum;
}
```

---

### Comparison

| Approach | Time | Space | Notes |
|----------|------|-------|-------|
| Two-pass DFS | O(n) | O(d) | Simple, two tree walks |
| BFS accumulation | O(n) | O(w) | One-pass, elegant — preferred |
| DFS with maxDepth | O(n) | O(d) | Same as two-pass, slightly cleaner |

where `d` = max depth, `w` = max width of a single level.

# 05b — Arrays Supplement

> This file covers **Nested List Weight Sum II**, which appeared in the PDF (section 1.6.17)
> but had no Python implementation. The original Nested List Weight Sum I is in `05_Arrays.md`.

---

## Nested List Weight Sum II
**URL:** https://leetcode.com/problems/nested-list-weight-sum-ii/

The weight is defined **bottom-up**: leaf-level integers have weight 1, root-level integers
have the largest weight. This is the inverse of Nested List Weight Sum I.

**Example 1:** `[[1,1],2,[1,1]]` → `8`  
(four 1s at depth 1 → weight 2, one 2 at depth 0 → weight... wait, depth is inverted)  
Actually: 2 is at the outermost level (depth=2 in bottom-up), 1s are one level deeper (depth=1).  
`4×1×1 + 1×2×2 = 4 + 4 = 8` ✓

**Example 2:** `[1,[4,[6]]]` → `17`  
1 at depth 3, 4 at depth 2, 6 at depth 1: `1×3 + 4×2 + 6×1 = 17` ✓

---

### Approach 1 — Two-Pass (find max depth, then compute)

```java
public int depthSumInverse(List<NestedInteger> nestedList) {
    int maxDepth = findMaxDepth(nestedList);
    return weightedSum(nestedList, maxDepth);
}

private int findMaxDepth(List<NestedInteger> list) {
    int depth = 1;
    for (NestedInteger ni : list) {
        if (!ni.isInteger())
            depth = Math.max(depth, 1 + findMaxDepth(ni.getList()));
    }
    return depth;
}

private int weightedSum(List<NestedInteger> list, int weight) {
    int sum = 0;
    for (NestedInteger ni : list) {
        if (ni.isInteger()) sum += ni.getInteger() * weight;
        else                sum += weightedSum(ni.getList(), weight - 1);
    }
    return sum;
}
```

---

### Approach 2 — BFS with Accumulation (One-Pass, O(n))

> Key insight: instead of computing depth up-front, accumulate a running `unweighted` sum
> each level. The final answer = sum of all per-level unweighted totals, each added once
> per remaining level below it.

```java
public int depthSumInverseBFS(List<NestedInteger> nestedList) {
    Queue<NestedInteger> queue = new LinkedList<>(nestedList);
    int totalSum = 0, levelSum = 0;

    while (!queue.isEmpty()) {
        int size = queue.size();
        levelSum = 0;
        for (int i = 0; i < size; i++) {
            NestedInteger ni = queue.poll();
            if (ni.isInteger()) {
                levelSum += ni.getInteger();
            } else {
                queue.addAll(ni.getList());
            }
        }
        totalSum += levelSum;   // each level's integers get +1 weight for every deeper level
    }
    // totalSum accumulates: deepest integers added once per BFS round above them
    return totalSum;
}
```

**Why this works:** An integer at depth `d` (1-indexed from deepest) is added to `totalSum`
exactly `d` times — once for each BFS round from its own level back to the root. That
is exactly the bottom-up weight.

---

### Approach 3 — DFS with `(maxDepth - depth + 1)` weight

```java
private int maxDepth = 0;

public int depthSumInverseDFS(List<NestedInteger> nestedList) {
    findDepth(nestedList, 1);
    return dfsSum(nestedList, 1);
}

private void findDepth(List<NestedInteger> list, int depth) {
    maxDepth = Math.max(maxDepth, depth);
    for (NestedInteger ni : list)
        if (!ni.isInteger()) findDepth(ni.getList(), depth + 1);
}

private int dfsSum(List<NestedInteger> list, int depth) {
    int sum = 0;
    for (NestedInteger ni : list) {
        if (ni.isInteger()) sum += ni.getInteger() * (maxDepth - depth + 1);
        else                sum += dfsSum(ni.getList(), depth + 1);
    }
    return sum;
}
```

---

### Comparison

| Approach | Time | Space | Notes |
|----------|------|-------|-------|
| Two-pass DFS | O(n) | O(d) | Simple, two tree walks |
| BFS accumulation | O(n) | O(w) | One-pass, elegant — preferred |
| DFS with maxDepth | O(n) | O(d) | Same as two-pass, slightly cleaner |

where `d` = max depth, `w` = max width of a single level.


# 07 — Bit Manipulation

---

## 7.1 Sum of Two Integers (No + or -)
**URL:** https://leetcode.com/problems/sum-of-two-integers/

> XOR gives the sum without carry; AND shifted left gives the carry. Recurse until carry = 0.
> Java requires masking to handle negative numbers due to arbitrary-precision in some contexts.

```java
public int getSum(int a, int b) {
    while (b != 0) {
        int carry = (a & b) << 1;
        a = a ^ b;
        b = carry;
    }
    return a;
}
```

---

## 7.2 Single Number (Every Element Appears Twice Except One)
**URL:** https://leetcode.com/problems/single-number/

> XOR is self-inverse: x ^ x = 0, x ^ 0 = x. Paired elements cancel.

```java
public int singleNumber(int[] nums) {
    int result = 0;
    for (int n : nums) result ^= n;
    return result;
}
```

---

## 7.3 Single Number II (Every Element Appears Three Times Except One)

> Count set bits mod 3 for each bit position; reconstruct the integer.

```java
public int singleNumberII(int[] nums) {
    int result = 0;
    for (int bit = 0; bit < 32; bit++) {
        int sum = 0;
        for (int n : nums) sum += (n >> bit) & 1;
        result |= (sum % 3) << bit;
    }
    return result;
}
```

---

## 7.4 Single Number III (Two Elements Appear Once, Rest Appear Twice)

> XOR all to get x^y. Use any set bit as a mask to split into two groups; XOR each group.

```java
public int[] singleNumberIII(int[] nums) {
    int xorAll = 0;
    for (int n : nums) xorAll ^= n;
    int mask = xorAll & (-xorAll);   // lowest set bit: differentiates x and y
    int a = 0, b = 0;
    for (int n : nums) {
        if ((n & mask) != 0) a ^= n;
        else                  b ^= n;
    }
    return new int[]{a, b};
}
```

---

## 7.5 Missing Number (XOR Approach)
**URL:** https://leetcode.com/problems/missing-number/

```java
// Also covered in Arrays section — XOR variant
public int missingNumber(int[] nums) {
    int xor = nums.length;
    for (int i = 0; i < nums.length; i++) xor ^= i ^ nums[i];
    return xor;
}
```


# 08 — Maths

---

## 8.1 Reverse Integer
**URL:** https://leetcode.com/problems/reverse-integer/

> Handle sign, build reversed number digit by digit, check 32-bit overflow.

```java
public int reverse(int x) {
    long result = 0;
    while (x != 0) {
        result = result * 10 + x % 10;
        x /= 10;
    }
    return (result > Integer.MAX_VALUE || result < Integer.MIN_VALUE) ? 0 : (int) result;
}
```

---

## 8.2 Palindrome Number
**URL:** https://leetcode.com/problems/palindrome-number/

> Reverse the integer, compare with original (negative numbers are never palindromes).

```java
public boolean isPalindrome(int x) {
    if (x < 0) return false;
    int original = x, rev = 0;
    while (x != 0) { rev = rev * 10 + x % 10; x /= 10; }
    return rev == original;
}
```

---

## 8.3 Pow(x, n)
**URL:** https://leetcode.com/problems/powx-n/

> Fast exponentiation (square-and-multiply). O(log n).

```java
public double myPow(double x, int n) {
    if (n < 0) { x = 1 / x; n = -n; }  // handle negative; note: -Integer.MIN_VALUE overflows — use long if needed
    return power(x, n);
}

private double power(double x, long n) {
    if (n == 0) return 1;
    double half = power(x, n / 2);
    return (n % 2 == 0) ? half * half : half * half * x;
}
```

---

## 8.4 Subsets I (Distinct Elements)
**URL:** https://leetcode.com/problems/subsets/

> Bit-mask approach: each of the 2^n integers represents a subset.

```java
public List<List<Integer>> subsets(int[] nums) {
    int n = 1 << nums.length;
    List<List<Integer>> result = new ArrayList<>();
    for (int mask = 0; mask < n; mask++) {
        List<Integer> subset = new ArrayList<>();
        for (int i = 0; i < nums.length; i++)
            if ((mask >> i & 1) == 1) subset.add(nums[i]);
        result.add(subset);
    }
    return result;
}

// Alternatively — DFS backtracking:
public List<List<Integer>> subsetsDFS(int[] nums) {
    Arrays.sort(nums);
    List<List<Integer>> result = new ArrayList<>();
    dfs(nums, 0, new ArrayList<>(), result);
    return result;
}

private void dfs(int[] nums, int start, List<Integer> current, List<List<Integer>> result) {
    result.add(new ArrayList<>(current));
    for (int i = start; i < nums.length; i++) {
        current.add(nums[i]);
        dfs(nums, i + 1, current, result);
        current.remove(current.size() - 1);
    }
}
```

---

## 8.5 Subsets II (With Duplicates)
**URL:** https://leetcode.com/problems/subsets-ii/

```java
public List<List<Integer>> subsetsWithDup(int[] nums) {
    Arrays.sort(nums);
    List<List<Integer>> result = new ArrayList<>();
    dfsII(nums, 0, new ArrayList<>(), result);
    return result;
}

private void dfsII(int[] nums, int start, List<Integer> curr, List<List<Integer>> result) {
    result.add(new ArrayList<>(curr));
    for (int i = start; i < nums.length; i++) {
        if (i > start && nums[i] == nums[i - 1]) continue;  // skip duplicate at same level
        curr.add(nums[i]);
        dfsII(nums, i + 1, curr, result);
        curr.remove(curr.size() - 1);
    }
}
```

---

## 8.6 Self Crossing
**URL:** https://leetcode.com/problems/self-crossing/

> Three geometric crossing conditions based on distance comparisons.

```java
public boolean isSelfCrossing(int[] x) {
    for (int i = 3; i < x.length; i++) {
        // Case 1: current line crosses the line 3 steps ahead
        if (x[i] >= x[i-2] && x[i-1] <= x[i-3]) return true;
        // Case 2: current line crosses the line 4 steps ahead
        if (i >= 4 && x[i-1] == x[i-3] && x[i] + x[i-4] >= x[i-2]) return true;
        // Case 3: current line crosses the line 5 steps ahead
        if (i >= 5 && x[i-2] >= x[i-4] && x[i] + x[i-4] >= x[i-2]
                   && x[i-1] <= x[i-3] && x[i-1] + x[i-5] >= x[i-3]) return true;
    }
    return false;
}
```

---

## 8.7 Paint Fence
**URL:** https://leetcode.com/problems/paint-fence/

> dp[i] = number of ways to paint i posts with no more than 2 adjacent same colors.
> Recurrence: `dp[i] = (k-1) * (dp[i-1] + dp[i-2])`

```java
public int numWays(int n, int k) {
    if (n == 0) return 0;
    if (n == 1) return k;
    int prev2 = k, prev1 = k * k;
    for (int i = 2; i < n; i++) {
        int curr = (k - 1) * (prev1 + prev2);
        prev2 = prev1;
        prev1 = curr;
    }
    return prev1;
}
```

---

## 8.8 Bulb Switcher
**URL:** https://leetcode.com/problems/bulb-switcher/

> A bulb ends ON only if toggled an odd number of times = only perfect squares have odd divisor counts.

```java
public int bulbSwitch(int n) {
    return (int) Math.sqrt(n);
}
```

---

## 8.9 Nim Game
**URL:** https://leetcode.com/problems/nim-game/

> If n is a multiple of 4, you lose (opponent can always mirror to keep you on multiples of 4).

```java
public boolean canWinNim(int n) {
    return n % 4 != 0;
}
```

# 08b — Maths Extended

> The PDF listed these problems in its table of contents (sections 1.9.6–1.9.24) but
> provided **no Python implementations** — the pages were blank stubs. Full Java solutions
> are provided here, completing the entire PDF problem set.

---

## 8b.1 Fraction to Recurring Decimal
**URL:** https://leetcode.com/problems/fraction-to-recurring-decimal/

> Simulate long division. Track remainder → position in a map to detect the cycle start.

```java
public String fractionToDecimal(int numerator, int denominator) {
    if (numerator == 0) return "0";

    StringBuilder sb = new StringBuilder();
    // Sign
    if ((numerator < 0) ^ (denominator < 0)) sb.append("-");

    long num = Math.abs((long) numerator);
    long den = Math.abs((long) denominator);

    // Integer part
    sb.append(num / den);
    long remainder = num % den;
    if (remainder == 0) return sb.toString();

    sb.append(".");
    Map<Long, Integer> remainderMap = new HashMap<>();
    while (remainder != 0) {
        if (remainderMap.containsKey(remainder)) {
            sb.insert(remainderMap.get(remainder), "(");
            sb.append(")");
            break;
        }
        remainderMap.put(remainder, sb.length());
        remainder *= 10;
        sb.append(remainder / den);
        remainder %= den;
    }
    return sb.toString();
}
```

---

## 8b.2 Excel Sheet Column Number
**URL:** https://leetcode.com/problems/excel-sheet-column-number/

> Base-26 number system where A=1, Z=26, AA=27, etc.

```java
public int titleToNumber(String columnTitle) {
    int result = 0;
    for (char c : columnTitle.toCharArray())
        result = result * 26 + (c - 'A' + 1);
    return result;
}
```

---

## 8b.3 Excel Sheet Column Title
**URL:** https://leetcode.com/problems/excel-sheet-column-title/

> Reverse of the above. Subtract 1 before modulo to map 26 → 'Z' (not 0).

```java
public String convertToTitle(int columnNumber) {
    StringBuilder sb = new StringBuilder();
    while (columnNumber > 0) {
        columnNumber--;                                    // shift to 0-indexed
        sb.append((char) ('A' + columnNumber % 26));
        columnNumber /= 26;
    }
    return sb.reverse().toString();
}
```

---

## 8b.4 Factorial Trailing Zeros
**URL:** https://leetcode.com/problems/factorial-trailing-zeros/

> Trailing zeros = factors of 10 = min(factors of 2, factors of 5). Fives are always
> the bottleneck. Count multiples of 5, 25, 125, … in [1..n].

```java
public int trailingZeroes(int n) {
    int count = 0;
    while (n >= 5) { n /= 5; count += n; }
    return count;
}
```

---

## 8b.5 Happy Number
**URL:** https://leetcode.com/problems/happy-number/

> Use Floyd's cycle detection on the digit-square-sum sequence. Cycle → not happy.

```java
public boolean isHappy(int n) {
    int slow = n, fast = digitSquareSum(n);
    while (fast != 1 && fast != slow) {
        slow = digitSquareSum(slow);
        fast = digitSquareSum(digitSquareSum(fast));
    }
    return fast == 1;
}

private int digitSquareSum(int n) {
    int sum = 0;
    while (n > 0) { int d = n % 10; sum += d * d; n /= 10; }
    return sum;
}
```

---

## 8b.6 Count Primes
**URL:** https://leetcode.com/problems/count-primes/

> Sieve of Eratosthenes: mark composites, count remaining primes below n.

```java
public int countPrimes(int n) {
    if (n < 2) return 0;
    boolean[] notPrime = new boolean[n];
    int count = 0;
    for (int i = 2; i < n; i++) {
        if (!notPrime[i]) {
            count++;
            for (long j = (long) i * i; j < n; j += i)
                notPrime[(int) j] = true;
        }
    }
    return count;
}
```

**Complexity:** O(n log log n) time, O(n) space.

---

## 8b.7 Divide Two Integers (Without * / %)
**URL:** https://leetcode.com/problems/divide-two-integers/

> Double the divisor repeatedly (bit shift = multiply by 2) to find the largest multiple
> ≤ dividend. Subtract and continue. Handle overflow edge case.

```java
public int divide(int dividend, int divisor) {
    if (dividend == Integer.MIN_VALUE && divisor == -1) return Integer.MAX_VALUE;
    int sign = (dividend > 0) ^ (divisor > 0) ? -1 : 1;
    long dvd = Math.abs((long) dividend);
    long dvs = Math.abs((long) divisor);
    int result = 0;

    while (dvd >= dvs) {
        long temp = dvs, multiple = 1;
        while (dvd >= (temp << 1)) { temp <<= 1; multiple <<= 1; }
        dvd    -= temp;
        result += multiple;
    }
    return sign * result;
}
```

---

## 8b.8 Multiply Strings
**URL:** https://leetcode.com/problems/multiply-strings/

> Simulate grade-school multiplication. Product of num1[i] × num2[j] contributes to
> positions [i+j] and [i+j+1] in the result array.

```java
public String multiply(String num1, String num2) {
    int m = num1.length(), n = num2.length();
    int[] pos = new int[m + n];

    for (int i = m - 1; i >= 0; i--) {
        for (int j = n - 1; j >= 0; j--) {
            int mul   = (num1.charAt(i) - '0') * (num2.charAt(j) - '0');
            int p1    = i + j, p2 = i + j + 1;
            int sum   = mul + pos[p2];
            pos[p2]   = sum % 10;
            pos[p1]  += sum / 10;
        }
    }

    StringBuilder sb = new StringBuilder();
    for (int p : pos) if (!(sb.length() == 0 && p == 0)) sb.append(p);
    return sb.length() == 0 ? "0" : sb.toString();
}
```

---

## 8b.9 Max Points on a Line
**URL:** https://leetcode.com/problems/max-points-on-a-line/

> For each point, compute slope to every other point using GCD-reduced fractions.
> The slope with the most matches + 1 (the anchor) = line size.

```java
public int maxPoints(int[][] points) {
    int n = points.length;
    if (n < 3) return n;
    int max = 0;

    for (int i = 0; i < n; i++) {
        Map<String, Integer> slopeCount = new HashMap<>();
        int duplicate = 1, localMax = 0;

        for (int j = i + 1; j < n; j++) {
            int dx = points[j][0] - points[i][0];
            int dy = points[j][1] - points[i][1];

            if (dx == 0 && dy == 0) { duplicate++; continue; }

            int g     = gcd(Math.abs(dx), Math.abs(dy));
            dx /= g; dy /= g;
            if (dx < 0) { dx = -dx; dy = -dy; }   // canonical form
            String slope = dx + "," + dy;
            slopeCount.merge(slope, 1, Integer::sum);
            localMax = Math.max(localMax, slopeCount.get(slope));
        }
        max = Math.max(max, localMax + duplicate);
    }
    return max;
}

private int gcd(int a, int b) { return b == 0 ? a : gcd(b, a % b); }
```

---

## 8b.10 Power of Three
**URL:** https://leetcode.com/problems/power-of-three/

```java
// Approach 1 — Loop (O(log n))
public boolean isPowerOfThree(int n) {
    if (n <= 0) return false;
    while (n % 3 == 0) n /= 3;
    return n == 1;
}

// Approach 2 — Math: largest int power of 3 is 3^19 = 1162261467
// n is a power of 3 iff it divides 3^19 evenly
public boolean isPowerOfThreeO1(int n) {
    return n > 0 && 1162261467 % n == 0;
}
```

---

## 8b.11 Power of Four
**URL:** https://leetcode.com/problems/power-of-four/

> A power of 4 must: (1) be a power of 2, AND (2) have its single set bit at an even position.

```java
public boolean isPowerOfFour(int n) {
    // 0xAAAAAAAA = bits at odd positions; mask with 0x55555555 (even positions)
    return n > 0 && (n & (n - 1)) == 0 && (n & 0x55555555) != 0;
}
```

---

## 8b.12 Integer Break
**URL:** https://leetcode.com/problems/integer-break/

> DP: `dp[i]` = max product from breaking integer `i`.
> Key insight: break into 3s as much as possible; avoid factor of 1.

```java
public int integerBreak(int n) {
    int[] dp = new int[n + 1];
    dp[1] = 1;
    for (int i = 2; i <= n; i++)
        for (int j = 1; j < i; j++)
            dp[i] = Math.max(dp[i], Math.max(j, dp[j]) * Math.max(i - j, dp[i - j]));
    return dp[n];
}

// Math shortcut: greedy — use 3s, adjust last piece
public int integerBreakMath(int n) {
    if (n == 2) return 1;
    if (n == 3) return 2;
    int product = 1;
    while (n > 4) { product *= 3; n -= 3; }
    return product * n;   // n is 2, 3, or 4 at this point
}
```

---

## 8b.13 Add Digits (Digital Root)
**URL:** https://leetcode.com/problems/add-digits/

> Repeatedly sum digits until single digit. The mathematical formula: digital root = 1 + (n-1) % 9.

```java
// Iterative O(log n)
public int addDigits(int num) {
    while (num >= 10) {
        int sum = 0;
        while (num > 0) { sum += num % 10; num /= 10; }
        num = sum;
    }
    return num;
}

// O(1) digital root formula
public int addDigitsO1(int num) {
    if (num == 0) return 0;
    return 1 + (num - 1) % 9;
}
```

---

## 8b.14 Ugly Number
**URL:** https://leetcode.com/problems/ugly-number/

> An ugly number has only 2, 3, 5 as prime factors. Divide out each factor completely.

```java
public boolean isUgly(int n) {
    if (n <= 0) return false;
    for (int f : new int[]{2, 3, 5})
        while (n % f == 0) n /= f;
    return n == 1;
}
```

---

## 8b.15 Ugly Number II (nth Ugly Number)
**URL:** https://leetcode.com/problems/ugly-number-ii/

> Three-pointer DP: maintain next multiples of 2, 3, 5; pick the minimum each step.

```java
public int nthUglyNumber(int n) {
    int[] ugly = new int[n];
    ugly[0] = 1;
    int i2 = 0, i3 = 0, i5 = 0;

    for (int i = 1; i < n; i++) {
        int next = Math.min(ugly[i2] * 2, Math.min(ugly[i3] * 3, ugly[i5] * 5));
        ugly[i] = next;
        if (next == ugly[i2] * 2) i2++;
        if (next == ugly[i3] * 3) i3++;
        if (next == ugly[i5] * 5) i5++;
    }
    return ugly[n - 1];
}
```

---

## 8b.16 Super Ugly Number
**URL:** https://leetcode.com/problems/super-ugly-number/

> Generalize Ugly Number II to k prime factors using a pointer array.

```java
public int nthSuperUglyNumber(int n, int[] primes) {
    int k = primes.length;
    int[] ugly    = new int[n];
    int[] indices = new int[k];   // pointer for each prime
    ugly[0] = 1;

    for (int i = 1; i < n; i++) {
        int min = Integer.MAX_VALUE;
        for (int j = 0; j < k; j++)
            min = Math.min(min, ugly[indices[j]] * primes[j]);
        ugly[i] = min;
        for (int j = 0; j < k; j++)
            if (min == ugly[indices[j]] * primes[j]) indices[j]++;
    }
    return ugly[n - 1];
}
```

---

## 8b.17 Find K Pairs with Smallest Sums
**URL:** https://leetcode.com/problems/find-k-pairs-with-smallest-sums/

> Min-heap seeded with (nums1[i], nums2[0]) for all i. Each pop yields the next smallest pair;
> push (nums1[i], nums2[j+1]) to explore the next candidate from the same row.

```java
public List<List<Integer>> kSmallestPairs(int[] nums1, int[] nums2, int k) {
    List<List<Integer>> result = new ArrayList<>();
    if (nums1.length == 0 || nums2.length == 0 || k == 0) return result;

    // Heap entry: [sum, i, j]
    PriorityQueue<int[]> pq = new PriorityQueue<>(Comparator.comparingInt(a -> a[0]));
    for (int i = 0; i < Math.min(nums1.length, k); i++)
        pq.offer(new int[]{nums1[i] + nums2[0], i, 0});

    while (!pq.isEmpty() && result.size() < k) {
        int[] curr = pq.poll();
        int i = curr[1], j = curr[2];
        result.add(Arrays.asList(nums1[i], nums2[j]));
        if (j + 1 < nums2.length)
            pq.offer(new int[]{nums1[i] + nums2[j + 1], i, j + 1});
    }
    return result;
}
```

**Complexity:** O(k log k) time — only k heap operations, heap size ≤ min(n, k).



# 09 — Matrix

---

## 9.1 Rotate Image (90° Clockwise, In-Place)
**URL:** https://leetcode.com/problems/rotate-image/

> Layer-by-layer rotation: save top, move left→top, bottom→left, right→bottom, saved→right.

```java
public void rotate(int[][] matrix) {
    int n = matrix.length;
    for (int layer = 0; layer < n / 2; layer++) {
        int first = layer, last = n - 1 - layer;
        for (int i = first; i < last; i++) {
            int offset = i - first;
            int top = matrix[first][i];
            // left → top
            matrix[first][i]             = matrix[last - offset][first];
            // bottom → left
            matrix[last - offset][first] = matrix[last][last - offset];
            // right → bottom
            matrix[last][last - offset]  = matrix[i][last];
            // top → right
            matrix[i][last]              = top;
        }
    }
}
```

---

## 9.2 Set Matrix Zeroes
**URL:** https://leetcode.com/problems/set-matrix-zeroes/

**O(m+n) space solution:**
```java
public void setZeroes(int[][] matrix) {
    int m = matrix.length, n = matrix[0].length;
    boolean[] zeroRow = new boolean[m], zeroCol = new boolean[n];
    for (int i = 0; i < m; i++)
        for (int j = 0; j < n; j++)
            if (matrix[i][j] == 0) { zeroRow[i] = true; zeroCol[j] = true; }
    for (int i = 0; i < m; i++)
        for (int j = 0; j < n; j++)
            if (zeroRow[i] || zeroCol[j]) matrix[i][j] = 0;
}
```

**O(1) space solution — use first row/column as markers:**
```java
public void setZeroesConstantSpace(int[][] matrix) {
    int m = matrix.length, n = matrix[0].length;
    boolean firstRowZero = false, firstColZero = false;
    for (int j = 0; j < n; j++) if (matrix[0][j] == 0) firstRowZero = true;
    for (int i = 0; i < m; i++) if (matrix[i][0] == 0) firstColZero = true;
    // Use first row/col as markers for the rest of the matrix
    for (int i = 1; i < m; i++)
        for (int j = 1; j < n; j++)
            if (matrix[i][j] == 0) { matrix[i][0] = 0; matrix[0][j] = 0; }
    // Zero cells based on markers
    for (int i = 1; i < m; i++)
        for (int j = 1; j < n; j++)
            if (matrix[i][0] == 0 || matrix[0][j] == 0) matrix[i][j] = 0;
    if (firstRowZero) for (int j = 0; j < n; j++) matrix[0][j] = 0;
    if (firstColZero) for (int i = 0; i < m; i++) matrix[i][0] = 0;
}
```

---

## 9.3 Search a 2D Matrix
**URL:** https://leetcode.com/problems/search-a-2d-matrix/

> Sorted rows + first element of each row > last of previous = treat as flat sorted array, binary search.
> Alternatively: start top-right, move down or left.

```java
public boolean searchMatrix(int[][] matrix, int target) {
    if (matrix.length == 0) return false;
    int r = 0, c = matrix[0].length - 1;
    while (r < matrix.length && c >= 0) {
        if      (matrix[r][c] == target) return true;
        else if (target > matrix[r][c])  r++;
        else                              c--;
    }
    return false;
}
```

---

## 9.4 Search a 2D Matrix II
**URL:** https://leetcode.com/problems/search-a-2d-matrix-ii/

> Same top-right corner strategy (works for the more general sorted-rows-and-cols matrix too).

```java
public boolean searchMatrixII(int[][] matrix, int target) {
    if (matrix.length == 0) return false;
    int r = 0, c = matrix[0].length - 1;
    while (r < matrix.length && c >= 0) {
        if      (matrix[r][c] == target) return true;
        else if (target > matrix[r][c])  r++;
        else                              c--;
    }
    return false;
}
```

---

## 9.5 Spiral Matrix (Read)
**URL:** https://leetcode.com/problems/spiral-matrix/

> Maintain four boundaries: top, bottom, left, right. Shrink after each pass.

```java
public List<Integer> spiralOrder(int[][] matrix) {
    List<Integer> result = new ArrayList<>();
    if (matrix == null || matrix.length == 0) return result;
    int top = 0, bottom = matrix.length - 1, left = 0, right = matrix[0].length - 1;
    while (top <= bottom && left <= right) {
        for (int j = left;  j <= right;  j++) result.add(matrix[top][j]);   top++;
        for (int i = top;   i <= bottom; i++) result.add(matrix[i][right]); right--;
        if (top <= bottom) {
            for (int j = right; j >= left;   j--) result.add(matrix[bottom][j]); bottom--;
        }
        if (left <= right) {
            for (int i = bottom; i >= top;   i--) result.add(matrix[i][left]);   left++;
        }
    }
    return result;
}
```

---

## 9.6 Spiral Matrix II (Fill)
**URL:** https://leetcode.com/problems/spiral-matrix-ii/

> Same boundary technique but write 1..n² instead of reading.

```java
public int[][] generateMatrix(int n) {
    int[][] matrix = new int[n][n];
    int top = 0, bottom = n - 1, left = 0, right = n - 1, num = 1;
    while (top <= bottom && left <= right) {
        for (int j = left;  j <= right;  j++) matrix[top][j]    = num++;  top++;
        for (int i = top;   i <= bottom; i++) matrix[i][right]   = num++;  right--;
        if (top <= bottom)
            for (int j = right; j >= left;   j--) matrix[bottom][j] = num++;  bottom--;
        if (left <= right)
            for (int i = bottom; i >= top;   i--) matrix[i][left]   = num++;  left++;
    }
    return matrix;
}
```

# 10 — Design

---

## 10.1 LRU Cache
**URL:** https://leetcode.com/problems/lru-cache/

> `LinkedHashMap` with access-order mode provides exactly LRU semantics.
> Override `removeEldestEntry` to evict automatically when capacity is exceeded.

```java
import java.util.LinkedHashMap;
import java.util.Map;

class LRUCache {
    private final int capacity;
    private final LinkedHashMap<Integer, Integer> cache;

    public LRUCache(int capacity) {
        this.capacity = capacity;
        // accessOrder=true: get() and put() move entries to the tail (most recently used)
        this.cache = new LinkedHashMap<>(capacity, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Integer, Integer> eldest) {
                return size() > capacity;  // evict head (least recently used) when over capacity
            }
        };
    }

    public int get(int key) {
        return cache.getOrDefault(key, -1);
    }

    public void put(int key, int value) {
        cache.put(key, value);
    }
}
```

---

## 10.2 LRU Cache — Manual Doubly-Linked List + HashMap
> For interviews that disallow `LinkedHashMap`. O(1) get and put.

```java
class LRUCacheManual {
    private static class Node {
        int key, val;
        Node prev, next;
        Node(int k, int v) { key = k; val = v; }
    }

    private final int capacity;
    private final Map<Integer, Node> map = new HashMap<>();
    private final Node head = new Node(0, 0);  // dummy head (LRU end)
    private final Node tail = new Node(0, 0);  // dummy tail (MRU end)

    public LRUCacheManual(int capacity) {
        this.capacity = capacity;
        head.next = tail;
        tail.prev = head;
    }

    public int get(int key) {
        if (!map.containsKey(key)) return -1;
        Node node = map.get(key);
        remove(node);
        insertAtTail(node);
        return node.val;
    }

    public void put(int key, int value) {
        if (map.containsKey(key)) remove(map.get(key));
        Node node = new Node(key, value);
        insertAtTail(node);
        map.put(key, node);
        if (map.size() > capacity) {
            Node lru = head.next;
            remove(lru);
            map.remove(lru.key);
        }
    }

    private void remove(Node node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
    }

    private void insertAtTail(Node node) {
        node.prev = tail.prev;
        node.next = tail;
        tail.prev.next = node;
        tail.prev = node;
    }
}
```

**Complexity:** O(1) for both `get` and `put`.  
**Trade-offs:** Manual implementation exposes the internals clearly but `LinkedHashMap` is preferred in production for readability and reliability.





# Pattern 10 — In-Place Linked List Reversal + Dummy Nodes

---

## 1. Pattern Recognition Guide

**Keywords to look for:**
- "reverse a linked list"
- "reverse between positions i and j"
- "reverse in groups of k"
- "rotate linked list"
- "reorder list"

**Dummy Node triggers:**
- "delete node", "merge lists", "add node at head"
- Whenever the head itself might change
- Result list built incrementally

**Signal phrase:** _"Reverse the linked list in place"_ → Three-pointer reversal
**Signal phrase:** _"Reverse nodes in k-group"_ → Reversal with counting + reconnection

---

## 2. Core Intuition

**Why you need three pointers:**
You can't reverse a link without knowing where to go back. You need:
- `prev` — the node that `curr` should now point to
- `curr` — the node being processed
- `next` — saved before overwriting `curr.next`

```
Before: prev=null, curr=1→2→3→null

Step 1: save next=2, curr.next=prev(null), prev=curr(1), curr=next(2)
        null ← 1   2→3→null

Step 2: save next=3, curr.next=prev(1), prev=curr(2), curr=next(3)
        null ← 1 ← 2   3→null

Step 3: save next=null, curr.next=prev(2), prev=curr(3), curr=next(null)
        null ← 1 ← 2 ← 3

Loop ends (curr=null), return prev (3) = new head
```

**Dummy Node intuition:**
When the head might change (deleting head, inserting before head, merging lists), add a fake node before head. You always return `dummy.next` at the end — no special-case logic.

---

## 3. Generic Java Templates

### Template A — Full List Reversal
```java
public ListNode reverseList(ListNode head) {
    ListNode prev = null;
    ListNode curr = head;

    while (curr != null) {
        ListNode next = curr.next; // save next
        curr.next = prev;          // reverse the link
        prev = curr;               // advance prev
        curr = next;               // advance curr
    }

    return prev; // new head
}
```

### Template B — Reverse Between Positions (1-indexed)
```java
public ListNode reverseBetween(ListNode head, int left, int right) {
    ListNode dummy = new ListNode(0);
    dummy.next = head;
    ListNode prev = dummy;

    // Move prev to node just before position 'left'
    for (int i = 1; i < left; i++) {
        prev = prev.next;
    }

    ListNode curr = prev.next; // first node to reverse
    ListNode next = null;

    // Reverse (right - left) times
    for (int i = 0; i < right - left; i++) {
        next = curr.next;
        curr.next = next.next;
        next.next = prev.next;
        prev.next = next;
    }

    return dummy.next;
}
```

### Template C — Reverse in K-Groups
```java
public ListNode reverseKGroup(ListNode head, int k) {
    ListNode dummy = new ListNode(0);
    dummy.next = head;
    ListNode groupPrev = dummy;

    while (true) {
        // Check if k nodes remain
        ListNode kth = getKth(groupPrev, k);
        if (kth == null) break;

        ListNode groupNext = kth.next;

        // Reverse the group
        ListNode prev = groupNext;
        ListNode curr = groupPrev.next;
        while (curr != groupNext) {
            ListNode next = curr.next;
            curr.next = prev;
            prev = curr;
            curr = next;
        }

        // Reconnect: groupPrev.next was group start, now points to old tail
        ListNode tmp = groupPrev.next;
        groupPrev.next = kth;
        groupPrev = tmp; // advance groupPrev to end of reversed group
    }

    return dummy.next;
}

private ListNode getKth(ListNode curr, int k) {
    while (curr != null && k > 0) {
        curr = curr.next;
        k--;
    }
    return curr;
}
```

### Template D — Dummy Node Pattern (Merge Two Sorted Lists)
```java
public ListNode mergeTwoLists(ListNode l1, ListNode l2) {
    ListNode dummy = new ListNode(0); // sentinel head
    ListNode curr = dummy;

    while (l1 != null && l2 != null) {
        if (l1.val <= l2.val) {
            curr.next = l1;
            l1 = l1.next;
        } else {
            curr.next = l2;
            l2 = l2.next;
        }
        curr = curr.next;
    }

    // Attach remaining
    curr.next = (l1 != null) ? l1 : l2;

    return dummy.next; // skip sentinel
}
```

### Template E — Reorder List (Mid + Reverse + Merge)
```java
public void reorderList(ListNode head) {
    if (head == null || head.next == null) return;

    // Step 1: Find middle
    ListNode slow = head, fast = head;
    while (fast.next != null && fast.next.next != null) {
        slow = slow.next;
        fast = fast.next.next;
    }

    // Step 2: Reverse second half
    ListNode second = reverseList(slow.next);
    slow.next = null; // cut first half

    // Step 3: Merge alternating
    ListNode first = head;
    while (second != null) {
        ListNode tmp1 = first.next;
        ListNode tmp2 = second.next;
        first.next = second;
        second.next = tmp1;
        first = tmp1;
        second = tmp2;
    }
}
```

---

## 4. Complexity Cheatsheet

| Problem | Time | Space |
|---|---|---|
| Full reversal | O(n) | O(1) |
| Reverse between i,j | O(n) | O(1) |
| Reverse k-groups | O(n) | O(1) |
| Merge two sorted lists | O(n+m) | O(1) |
| Reorder list | O(n) | O(1) |

---

## 5. Canonical Code Problems

### Core (Must Solve)

| LC # | Problem | Difficulty | Why Important |
|---|---|---|---|
| 206 | Reverse Linked List | Easy | Core template |
| 92 | Reverse Linked List II | Medium | Partial reversal |
| 21 | Merge Two Sorted Lists | Easy | Dummy node template |
| 143 | Reorder List | Medium | Mid + Reverse + Merge |
| 25 | Reverse Nodes in k-Group | Hard | Group reversal |

### Advanced Variations

| LC # | Problem | Difficulty | Why Important |
|---|---|---|---|
| 2 | Add Two Numbers | Medium | Dummy node + carry |
| 82 | Remove Duplicates from Sorted List II | Medium | Dummy + skip |
| 86 | Partition List | Medium | Two dummy lists |
| 148 | Sort List | Medium | Merge sort on list |
| 23 | Merge K Sorted Lists | Hard | Heap + dummy |

---

## 6. Solve Step-by-Step — LC 92: Reverse Linked List II

**Problem:** Reverse nodes from position `left` to `right` (1-indexed).

### Approach: "Head Insertion" Trick

Instead of traditional 3-pointer reversal (which requires tracking 4 pointers across segment boundaries), use head insertion:
- Keep `prev` at position just before `left`
- Repeatedly take `curr.next` and insert it right after `prev`

```java
public ListNode reverseBetween(ListNode head, int left, int right) {
    ListNode dummy = new ListNode(0);
    dummy.next = head;
    ListNode prev = dummy;

    // Reach position before 'left'
    for (int i = 1; i < left; i++) prev = prev.next;

    ListNode curr = prev.next;

    for (int i = 0; i < right - left; i++) {
        ListNode next = curr.next;   // node to insert at front
        curr.next = next.next;       // skip next
        next.next = prev.next;       // next points to current front
        prev.next = next;            // prev points to next (new front)
    }

    return dummy.next;
}
```

### Dry Run: `[1,2,3,4,5]`, left=2, right=4
```
dummy → 1 → 2 → 3 → 4 → 5
prev stops at 1 (position before left=2)
curr = 2

i=0: next=3; curr(2).next=4; next(3).next=2; prev(1).next=3
     dummy → 1 → 3 → 2 → 4 → 5
i=1: next=4; curr(2).next=5; next(4).next=3; prev(1).next=4
     dummy → 1 → 4 → 3 → 2 → 5

Result: [1,4,3,2,5]  ✓
```

---

## 7. Pattern Variations

| Problem | Key Technique |
|---|---|
| Full reversal | 3-pointer: prev/curr/next |
| Partial reversal | Dummy + head insertion |
| K-group reversal | Count k, reverse segment, reconnect |
| Merge two lists | Dummy node + two pointers |
| Reorder | Find mid + reverse + interleave |
| Partition | Two dummy lists for < and >= pivot |
| Add two numbers | Dummy + carry variable |

---

## 8. Common Interview Mistakes

1. **Losing track of next pointer** — always `ListNode next = curr.next` BEFORE modifying `curr.next`
2. **Not using dummy node when head might change** — causes null pointer or incorrect head
3. **Reverse between: off-by-one in for loop** — loop runs `right - left` times (not `right - left + 1`)
4. **K-group: not checking remaining nodes** — must verify k nodes exist before reversing
5. **Reorder list: not cutting first half** — `slow.next = null` is mandatory before merging
6. **Returning `prev` vs `dummy.next`** — when using dummy, always return `dummy.next`

---

## 9. Interview Cheat Sheet

```
LINKED LIST REVERSAL — MENTAL CHECKLIST
=========================================
□ Head might change? → Use dummy node, return dummy.next
□ Full reversal? → prev=null, curr=head, 3-pointer loop
□ Partial reversal? → dummy + head insertion trick
□ K-group? → count k, reverse segment, advance groupPrev
□ Build result list? → dummy node + curr pointer

3-POINTER REVERSAL (MEMORIZE)
==============================
prev = null; curr = head
while (curr != null):
    next = curr.next
    curr.next = prev
    prev = curr
    curr = next
return prev  ← new head

DUMMY NODE PATTERN
==================
dummy = new ListNode(0); dummy.next = head
// ... build/modify list using dummy.next as anchor
return dummy.next

TRICKS
======
- Head insertion for partial reverse: take node from curr.next, put at prev.next
- Reorder = find middle (fast/slow) + reverse second half + interleave
- Two dummy lists for partition problems (one for each condition)
- Sort list: merge sort with find-middle + recursive merge
```

---

## 10. Practice Roadmap

**Beginner:**
- LC 206 — Reverse Linked List
- LC 21 — Merge Two Sorted Lists
- LC 83 — Remove Duplicates from Sorted List

**Intermediate:**
- LC 92 — Reverse Linked List II
- LC 143 — Reorder List
- LC 86 — Partition List
- LC 2 — Add Two Numbers

**Taking Hard:**
- LC 25 — Reverse Nodes in k-Group
- LC 23 — Merge K Sorted Lists
- LC 148 — Sort List

# Pattern 09 — Fast and Slow Pointers (Floyd's Algorithm)

---

## 1. Pattern Recognition Guide

**Keywords to look for:**
- "detect cycle in linked list"
- "find middle of linked list"
- "find start of cycle"
- "palindrome linked list"
- "nth node from end" (also two pointers, different gap)
- "happy number" (implied cycle in sequence)

**When to reach for Fast/Slow:**
- Cycle detection in any sequence (linked list, array, number sequences)
- Finding the middle of a linked list in one pass
- Problems where you need a pointer at 1/2 speed

**Signal phrase:** _"Does this linked list have a cycle?"_ → Fast/Slow immediately
**Signal phrase:** _"Find the middle"_ → Slow stops at middle when fast reaches end

---

## 2. Core Intuition

**The key observation:**
If two pointers move at different speeds in a circular loop, the faster one MUST eventually lap the slower one (like runners on a circular track).

**Why it works:**
- No cycle: fast pointer reaches null, no meeting
- With cycle: fast enters cycle, slow enters cycle. Relative speed = 1 step/iteration. Eventually fast catches slow.

**Finding the middle:**
When fast (2 steps) reaches end, slow (1 step) is at the middle.

```
List: 1 → 2 → 3 → 4 → 5 → null

Slow: 1  →  2  →  3
Fast: 1  →  3  →  5 → null

When fast hits null or fast.next is null → slow is at middle (3)
```

**Cycle detection:**
```
1 → 2 → 3 → 4 → 5
            ↑       ↓
            8  ← 6 ← (cycle)

Slow and fast will meet inside the cycle. Then:
Move one pointer to head, keep other at meeting point.
Advance both one step at a time → they meet at cycle start!
```

---

## 3. Generic Java Templates

### ListNode Definition
```java
public class ListNode {
    int val;
    ListNode next;
    ListNode(int val) { this.val = val; }
}
```

### Template A — Detect Cycle
```java
public boolean hasCycle(ListNode head) {
    ListNode slow = head;
    ListNode fast = head;

    while (fast != null && fast.next != null) {
        slow = slow.next;
        fast = fast.next.next;

        if (slow == fast) return true; // they met → cycle exists
    }

    return false; // fast hit null → no cycle
}
```

### Template B — Find Start of Cycle
```java
public ListNode detectCycle(ListNode head) {
    ListNode slow = head;
    ListNode fast = head;

    // Phase 1: Detect meeting point
    while (fast != null && fast.next != null) {
        slow = slow.next;
        fast = fast.next.next;
        if (slow == fast) break;
    }

    // No cycle
    if (fast == null || fast.next == null) return null;

    // Phase 2: Find cycle start
    // Move one pointer to head, advance both one step at a time
    slow = head;
    while (slow != fast) {
        slow = slow.next;
        fast = fast.next;
    }

    return slow; // cycle start
}
```

### Template C — Find Middle of Linked List
```java
public ListNode findMiddle(ListNode head) {
    ListNode slow = head;
    ListNode fast = head;

    // When fast reaches end, slow is at middle
    while (fast != null && fast.next != null) {
        slow = slow.next;
        fast = fast.next.next;
    }

    return slow; // middle node
    // For even length: returns second middle (e.g., [1,2,3,4] → 3)
    // Use fast.next != null only to get first middle → 2
}
```

### Template D — Nth From End (Two Pointers with Gap)
```java
public ListNode removeNthFromEnd(ListNode head, int n) {
    ListNode dummy = new ListNode(0);
    dummy.next = head;

    ListNode fast = dummy;
    ListNode slow = dummy;

    // Advance fast by n+1 steps (creates gap of n)
    for (int i = 0; i <= n; i++) {
        fast = fast.next;
    }

    // Move both until fast hits null
    while (fast != null) {
        slow = slow.next;
        fast = fast.next;
    }

    // slow.next is the nth from end — remove it
    slow.next = slow.next.next;

    return dummy.next;
}
```

### Template E — Palindrome Linked List
```java
public boolean isPalindrome(ListNode head) {
    // Step 1: Find middle
    ListNode slow = head, fast = head;
    while (fast != null && fast.next != null) {
        slow = slow.next;
        fast = fast.next.next;
    }

    // Step 2: Reverse second half
    ListNode prev = null, curr = slow;
    while (curr != null) {
        ListNode next = curr.next;
        curr.next = prev;
        prev = curr;
        curr = next;
    }

    // Step 3: Compare first half and reversed second half
    ListNode left = head, right = prev;
    while (right != null) {
        if (left.val != right.val) return false;
        left = left.next;
        right = right.next;
    }

    return true;
}
```

---

## 4. Complexity Cheatsheet

| Problem | Time | Space |
|---|---|---|
| Detect cycle | O(n) | O(1) |
| Find cycle start | O(n) | O(1) |
| Find middle | O(n) | O(1) |
| Nth from end | O(n) | O(1) |
| Palindrome check | O(n) | O(1) |

All O(1) space — that's the beauty of fast/slow pointers.

---

## 5. Canonical Code Problems

### Core (Must Solve)

| LC # | Problem | Difficulty | Why Important |
|---|---|---|---|
| 141 | Linked List Cycle | Easy | Core template |
| 142 | Linked List Cycle II | Medium | Find cycle start |
| 876 | Middle of the Linked List | Easy | Middle template |
| 234 | Palindrome Linked List | Easy | Middle + reverse + compare |
| 19 | Remove Nth Node From End | Medium | Gap technique |

### Advanced Variations

| LC # | Problem | Difficulty | Why Important |
|---|---|---|---|
| 202 | Happy Number | Easy | Cycle in number sequence |
| 287 | Find the Duplicate Number | Medium | Array as linked list |
| 457 | Circular Array Loop | Medium | Cycle in circular array |
| 160 | Intersection of Two Linked Lists | Easy | Equalize lengths trick |
| 61 | Rotate List | Medium | Find tail + reconnect |

---

## 6. Solve Step-by-Step — LC 142: Linked List Cycle II

**Problem:** Find the node where the cycle begins.

### The Math (Why Phase 2 Works)

Let:
- F = distance from head to cycle start
- C = cycle length
- K = distance from cycle start to meeting point

When slow and fast meet:
- slow traveled: F + K
- fast traveled: F + K + C (one full cycle extra)
- fast = 2 × slow → F + K + C = 2(F + K) → C = F + K → **F = C - K**

So distance from head to cycle start (F) = distance from meeting point to cycle start (C - K).
Moving one pointer from head, one from meeting point, both one step → they meet at cycle start!

```java
public ListNode detectCycle(ListNode head) {
    ListNode slow = head, fast = head;

    while (fast != null && fast.next != null) {
        slow = slow.next;
        fast = fast.next.next;
        if (slow == fast) {
            // Phase 2: find cycle start
            slow = head;
            while (slow != fast) {
                slow = slow.next;
                fast = fast.next;
            }
            return slow;
        }
    }
    return null;
}
```

### Dry Run
```
List: 3 → 1 → 2 → 0 → (back to 2)
Indices: 0    1    2    3

Phase 1:
Start: slow=3, fast=3
Step1: slow=1, fast=2
Step2: slow=2, fast=0
Step3: slow=0, fast=0 ← MEET at index 3

Phase 2: slow=head(3), fast=0(index 3)
Step1: slow=1, fast=2 (index 2)
Step2: slow=2, fast=2 ← MEET at index 2 = cycle start ✓
```

---

## 7. Pattern Variations

| Problem | Variation |
|---|---|
| Cycle detection | fast/slow, meet = cycle exists |
| Cycle start | Phase 2: head pointer + meeting point |
| Middle | fast reaches end → slow at middle |
| Nth from end | Gap of n between fast and slow |
| Palindrome | Middle + reverse second half |
| Happy number | Treat sequence as linked list, detect cycle |
| Find duplicate | Array indices as next pointers (LC 287) |

---

## 8. Common Interview Mistakes

1. **`fast.next != null` check missing** — `fast.next.next` will NPE without it
2. **Meeting check inside loop vs. before advancing** — check AFTER advancing both
3. **Cycle start: moving slow to head but keeping fast at meeting point** — correct; many forget to do this
4. **Middle for even length**: `[1,2,3,4]` → returns 3 (second middle). If you need first middle, use `fast.next != null` instead of `fast != null && fast.next != null`
5. **Palindrome: not restoring the list** — some interviewers expect you to restore the reversed half

---

## 9. Interview Cheat Sheet

```
FAST/SLOW POINTERS — MENTAL CHECKLIST
=======================================
□ Cycle detection? → fast 2 steps, slow 1 step; meet = cycle
□ Cycle start?     → Phase 2: reset slow to head, both move 1 step
□ Middle?          → fast reaches null → slow is at middle
□ Nth from end?    → gap of n between fast and slow using dummy node
□ Always check: fast != null && fast.next != null

TEMPLATES
=========
Cycle detect: while(fast!=null && fast.next!=null) { slow++; fast+=2; if(==) cycle }
Middle: same loop, when exits: slow = middle
Nth from end: fast leads by n+1, then move together

TRICKS
======
- Dummy node for nth-from-end removes edge case (removing head)
- Happy number: model as linked list, fast/slow on next() function
- LC 287 (find duplicate): treat array as linked list, indices as values
- Palindrome = find middle + reverse second half + compare
```

---

## 10. Practice Roadmap

**Beginner:**
- LC 141 — Linked List Cycle
- LC 876 — Middle of Linked List
- LC 202 — Happy Number

**Intermediate:**
- LC 142 — Linked List Cycle II
- LC 19 — Remove Nth from End
- LC 234 — Palindrome Linked List
- LC 160 — Intersection of Two Lists

**Taking Hard:**
- LC 287 — Find the Duplicate Number
- LC 457 — Circular Array Loop
- LC 61 — Rotate List

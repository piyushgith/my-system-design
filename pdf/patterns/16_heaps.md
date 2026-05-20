# Pattern 16 — Heaps: Top K, Two Heaps, Merge K Sorted

---

## 1. Pattern Recognition Guide

**Keywords to look for:**
- "top k largest/smallest/frequent elements"
- "kth largest/smallest element"
- "median of a stream"
- "merge k sorted lists/arrays"
- "task scheduler", "reorganize string"
- "find median from data stream"

**When to reach for a Heap:**
- Need the k-th order statistic efficiently
- Need continuous access to the min or max
- Streaming/online problem — process as data arrives
- K-way merge

**Signal phrase:** _"K largest elements"_ → Min-heap of size k
**Signal phrase:** _"Median of a stream"_ → Two heaps (max-heap left + min-heap right)

---

## 2. Core Intuition

### Min-Heap for Top K Largest
Maintain a min-heap of size k. For each new element:
- If heap has fewer than k elements, add it
- If new element > heap's min (root), remove min, add new
- After processing all: heap contains the k largest

**Why min-heap?** The root is the smallest of our "top k" — if a new element beats it, it belongs in the top k.

### Two Heaps for Median
Keep elements in two halves:
- **Max-heap**: lower half (root = median candidate)
- **Min-heap**: upper half (root = median candidate)

Invariant: max-heap size == min-heap size OR max-heap has one more element.
- Odd count: median = max-heap root
- Even count: median = avg(max-heap root, min-heap root)

---

## 3. Generic Java Templates

### Template A — Top K Largest Elements
```java
public int[] topKLargest(int[] nums, int k) {
    // Min-heap of size k: smallest of the k-largest sits at top
    PriorityQueue<Integer> minHeap = new PriorityQueue<>();

    for (int num : nums) {
        minHeap.offer(num);
        if (minHeap.size() > k) {
            minHeap.poll(); // remove smallest — doesn't belong in top k
        }
    }

    int[] result = new int[k];
    int i = k - 1;
    while (!minHeap.isEmpty()) result[i--] = minHeap.poll();
    return result;
}

// Kth Largest only
public int findKthLargest(int[] nums, int k) {
    PriorityQueue<Integer> minHeap = new PriorityQueue<>();
    for (int num : nums) {
        minHeap.offer(num);
        if (minHeap.size() > k) minHeap.poll();
    }
    return minHeap.peek(); // root = kth largest
}
```

### Template B — Top K Frequent Elements
```java
public int[] topKFrequent(int[] nums, int k) {
    // Count frequencies
    Map<Integer, Integer> freq = new HashMap<>();
    for (int num : nums) freq.merge(num, 1, Integer::sum);

    // Min-heap by frequency
    PriorityQueue<int[]> minHeap = new PriorityQueue<>((a, b) -> a[1] - b[1]);

    for (Map.Entry<Integer, Integer> entry : freq.entrySet()) {
        minHeap.offer(new int[]{entry.getKey(), entry.getValue()});
        if (minHeap.size() > k) minHeap.poll();
    }

    int[] result = new int[k];
    for (int i = 0; i < k; i++) result[i] = minHeap.poll()[0];
    return result;
}
```

### Template C — Two Heaps (Median Finder)
```java
class MedianFinder {
    private PriorityQueue<Integer> maxHeap; // lower half
    private PriorityQueue<Integer> minHeap; // upper half

    public MedianFinder() {
        maxHeap = new PriorityQueue<>(Collections.reverseOrder()); // max at top
        minHeap = new PriorityQueue<>(); // min at top
    }

    public void addNum(int num) {
        // Always add to maxHeap first
        maxHeap.offer(num);

        // Balance: maxHeap root must be <= minHeap root
        if (!minHeap.isEmpty() && maxHeap.peek() > minHeap.peek()) {
            minHeap.offer(maxHeap.poll());
        }

        // Maintain size invariant: maxHeap has same or one more element
        if (maxHeap.size() > minHeap.size() + 1) {
            minHeap.offer(maxHeap.poll());
        } else if (minHeap.size() > maxHeap.size()) {
            maxHeap.offer(minHeap.poll());
        }
    }

    public double findMedian() {
        if (maxHeap.size() == minHeap.size()) {
            return (maxHeap.peek() + minHeap.peek()) / 2.0;
        }
        return maxHeap.peek(); // maxHeap has one extra
    }
}
```

### Template D — Merge K Sorted Lists
```java
public ListNode mergeKLists(ListNode[] lists) {
    // Min-heap ordered by node value
    PriorityQueue<ListNode> pq = new PriorityQueue<>((a, b) -> a.val - b.val);

    // Add head of each list
    for (ListNode list : lists) {
        if (list != null) pq.offer(list);
    }

    ListNode dummy = new ListNode(0);
    ListNode curr = dummy;

    while (!pq.isEmpty()) {
        ListNode node = pq.poll(); // smallest current node
        curr.next = node;
        curr = curr.next;

        if (node.next != null) pq.offer(node.next); // add next from same list
    }

    return dummy.next;
}
```

### Template E — K Closest Points to Origin
```java
public int[][] kClosest(int[][] points, int k) {
    // Max-heap by distance — keep only k closest (remove farthest)
    PriorityQueue<int[]> maxHeap = new PriorityQueue<>(
        (a, b) -> (b[0]*b[0]+b[1]*b[1]) - (a[0]*a[0]+a[1]*a[1])
    );

    for (int[] point : points) {
        maxHeap.offer(point);
        if (maxHeap.size() > k) maxHeap.poll(); // remove farthest
    }

    return maxHeap.toArray(new int[k][]);
}
```

---

## 4. Complexity Cheatsheet

| Operation | Time | Notes |
|---|---|---|
| Heap push/pop | O(log n) | |
| Build heap from n elements | O(n) | heapify |
| Top K from n elements | O(n log k) | k-size heap |
| Merge K lists (total N nodes) | O(N log K) | K-size heap |
| Median add | O(log n) | two heaps |
| Median query | O(1) | peek tops |

---

## 5. Canonical Code Problems

### Core (Must Solve)

| LC # | Problem | Difficulty | Why Important |
|---|---|---|---|
| 215 | Kth Largest Element in Array | Medium | Min-heap of size k |
| 347 | Top K Frequent Elements | Medium | Freq map + min-heap |
| 295 | Find Median from Data Stream | Hard | Two heaps |
| 23 | Merge K Sorted Lists | Hard | K-way merge with heap |
| 973 | K Closest Points to Origin | Medium | Max-heap of size k |

### Advanced Variations

| LC # | Problem | Difficulty | Why Important |
|---|---|---|---|
| 767 | Reorganize String | Medium | Max-heap greedy |
| 621 | Task Scheduler | Medium | Max-heap + idle slots |
| 1046 | Last Stone Weight | Easy | Max-heap simulation |
| 871 | Minimum Number of Refueling Stops | Hard | Max-heap greedy |
| 502 | IPO | Hard | Two heaps for profit/capital |

---

## 6. Solve Step-by-Step — LC 295: Find Median from Data Stream

**Problem:** Implement MedianFinder with addNum() and findMedian().

### Key Insight
Two heaps create a balanced partition. The tops of both heaps give us the median in O(1).

(Full implementation already in Template C)

### Dry Run
```
addNum(1): maxHeap=[1], minHeap=[]
findMedian(): maxHeap.size > minHeap.size → return 1.0

addNum(2): add 2 to maxHeap=[2,1] → 2>no minHeap → ok
           sizes: max=2, min=0 → max too big → move max(2) to min
           maxHeap=[1], minHeap=[2]
findMedian(): equal sizes → (1+2)/2.0 = 1.5

addNum(3): add 3 to maxHeap=[3,1] → 3 > minHeap.peek(2) → move 3 to min
           maxHeap=[1], minHeap=[2,3]
           sizes: max=1, min=2 → min too big → move min(2) to max
           maxHeap=[2,1], minHeap=[3]
findMedian(): maxHeap.size > minHeap.size → return 2.0
```

---

## 7. Pattern Variations

| Problem | Heap Choice |
|---|---|
| Top K largest | Min-heap of size k |
| Top K smallest | Max-heap of size k |
| Kth largest | Min-heap of size k, peek root |
| K closest | Max-heap of size k (by distance) |
| K most frequent | Min-heap of size k (by frequency) |
| Median stream | Two heaps (max-heap + min-heap) |
| Merge K sorted | Min-heap with K elements |
| Greedy scheduling | Max-heap by frequency/priority |

---

## 8. Common Interview Mistakes

1. **Min-heap for top K smallest** → actually use max-heap of size k (remove max)
2. **Two heaps: forgetting to rebalance after adding** → median computation wrong
3. **Two heaps: value vs comparator direction** — Java's PriorityQueue is min by default; max needs `Collections.reverseOrder()`
4. **Merge K: not null-checking** before adding to heap
5. **Task scheduler: not understanding idle slots** — frequency-based, not just sorting

---

## 9. Interview Cheat Sheet

```
HEAP PATTERNS — MENTAL CHECKLIST
===================================
□ Top K LARGEST → min-heap size k (removes smallest → keeps largest)
□ Top K SMALLEST → max-heap size k (removes largest → keeps smallest)
□ Kth LARGEST → min-heap size k, peek()
□ Median stream → two heaps (max-heap left, min-heap right)
□ Merge K sorted → min-heap with one node from each list

JAVA HEAP SYNTAX
================
PriorityQueue<Integer> minHeap = new PriorityQueue<>();          // default min
PriorityQueue<Integer> maxHeap = new PriorityQueue<>(Collections.reverseOrder());
PriorityQueue<int[]>   custom  = new PriorityQueue<>((a,b) -> a[1]-b[1]); // by index 1

TWO HEAPS INVARIANT
====================
maxHeap (lower half) ≤ all elements in minHeap (upper half)
|maxHeap.size - minHeap.size| ≤ 1
median = maxHeap.peek() if odd, avg of both tops if even

TRICKS
======
- Always keep k-size heap → O(n log k) not O(n log n)
- For custom objects: sort by one field, break ties by another in comparator
- Greedy scheduling: always pick highest frequency remaining task
- K-way merge: only K elements in heap at once → O(N log K)
```

---

## 10. Practice Roadmap

**Beginner:**
- LC 1046 — Last Stone Weight
- LC 215 — Kth Largest Element in Array

**Intermediate:**
- LC 347 — Top K Frequent Elements
- LC 973 — K Closest Points to Origin
- LC 767 — Reorganize String
- LC 621 — Task Scheduler

**Taking Hard:**
- LC 295 — Find Median from Data Stream
- LC 23 — Merge K Sorted Lists
- LC 871 — Minimum Number of Refueling Stops
- LC 502 — IPO

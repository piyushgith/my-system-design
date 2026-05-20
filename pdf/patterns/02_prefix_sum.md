# Pattern 02 — Prefix Sum

---

## 1. Pattern Recognition Guide

**Keywords to look for:**
- "sum of subarray", "range sum query"
- "number of subarrays with sum equal to K"
- "running total", "cumulative sum"
- "between index i and j"

**When to reach for Prefix Sum:**
- Multiple range-sum queries on the same array
- Subarray sum equals a target
- Count of subarrays satisfying a sum condition
- 2D matrix rectangle sum queries

**Signal phrase:** _"Sum of elements between index i and j"_ → Prefix Sum
**Signal phrase:** _"Number of subarrays with sum = k"_ → Prefix Sum + HashMap

---

## 2. Core Intuition

**Why brute force fails:**
Recomputing the sum from scratch for each query = O(n) per query. For Q queries: O(n × Q).

**The key observation:**
If you precompute `prefix[i] = arr[0] + arr[1] + ... + arr[i]`, then:
```
sum(i, j) = prefix[j] - prefix[i - 1]
```
Answer any range sum in O(1) after O(n) preprocessing.

**Core invariant:**
```
prefix[0] = 0   (empty prefix — critical for edge cases)
prefix[i] = prefix[i-1] + arr[i-1]   (1-indexed prefix)
```

**Visual:**
```
arr    =  [3,  1,  4,  1,  5]
prefix =  [0,  3,  4,  8,  9, 14]
           ^
           sentinel zero

sum(2,4) = prefix[4] - prefix[1] = 9 - 3 = 6   ✓ (1+4+1=6)
```

---

## 3. Generic Java Templates

### Template A — Basic Prefix Sum (Range Query)

```java
// Preprocessing: O(n)
public int[] buildPrefix(int[] arr) {
    int n = arr.length;
    int[] prefix = new int[n + 1]; // size n+1; prefix[0] = 0 sentinel
    for (int i = 1; i <= n; i++) {
        prefix[i] = prefix[i - 1] + arr[i - 1];
    }
    return prefix;
}

// Query: O(1)  [l, r] are 0-indexed inclusive
public int rangeSum(int[] prefix, int l, int r) {
    return prefix[r + 1] - prefix[l];
}
```

### Template B — Subarray Sum Equals K (HashMap)

```java
// Count subarrays with sum exactly equal to k
public int subarraySum(int[] nums, int k) {
    // Map: prefixSum → count of times seen
    Map<Integer, Integer> prefixCount = new HashMap<>();
    prefixCount.put(0, 1); // empty subarray has sum 0

    int count = 0;
    int prefixSum = 0;

    for (int num : nums) {
        prefixSum += num;

        // If (prefixSum - k) was seen before, those subarrays end here with sum = k
        count += prefixCount.getOrDefault(prefixSum - k, 0);

        // Record current prefix sum
        prefixCount.merge(prefixSum, 1, Integer::sum);
    }
    return count;
}
```

### Template C — 2D Prefix Sum (Matrix Rectangle Query)

```java
// Build 2D prefix sum
public int[][] build2DPrefix(int[][] matrix) {
    int rows = matrix.length;
    int cols = matrix[0].length;
    int[][] prefix = new int[rows + 1][cols + 1];

    for (int r = 1; r <= rows; r++) {
        for (int c = 1; c <= cols; c++) {
            prefix[r][c] = matrix[r-1][c-1]
                + prefix[r-1][c]
                + prefix[r][c-1]
                - prefix[r-1][c-1]; // subtract double-counted corner
        }
    }
    return prefix;
}

// Query: sum of rectangle (r1,c1) to (r2,c2) — 0-indexed
public int rectSum(int[][] prefix, int r1, int c1, int r2, int c2) {
    return prefix[r2+1][c2+1]
         - prefix[r1][c2+1]
         - prefix[r2+1][c1]
         + prefix[r1][c1]; // add back double-subtracted corner
}
```

---

## 4. Complexity Cheatsheet

| Variant | Build Time | Query Time | Space |
|---|---|---|---|
| 1D prefix sum | O(n) | O(1) | O(n) |
| Subarray sum = k (HashMap) | O(n) | — | O(n) |
| 2D prefix sum | O(n×m) | O(1) | O(n×m) |

---

## 5. Canonical Code Problems

### Core (Must Solve)

| LC # | Problem | Difficulty | Why Important |
|---|---|---|---|
| 303 | Range Sum Query - Immutable | Easy | Pure prefix sum template |
| 724 | Find Pivot Index | Easy | Balance left/right sums |
| 525 | Contiguous Array (0s and 1s) | Medium | Clever prefix with transformation |
| 560 | Subarray Sum Equals K | Medium | HashMap + prefix sum combo |
| 304 | Range Sum Query 2D | Medium | 2D prefix template |

### Advanced Variations

| LC # | Problem | Difficulty | Why Important |
|---|---|---|---|
| 974 | Subarray Sums Divisible by K | Medium | Prefix sum + modulo arithmetic |
| 1248 | Count Number of Nice Subarrays | Medium | Transform odds to 1/0, apply prefix |
| 327 | Count of Range Sum | Hard | Merge sort + prefix sum |
| 1074 | Number of Submatrices that Sum to Target | Hard | 2D prefix + HashMap |
| 862 | Shortest Subarray with Sum at Least K | Hard | Deque + prefix sum |

---

## 6. Solve Step-by-Step — LC 560: Subarray Sum Equals K

**Problem:** Count subarrays with sum exactly equal to k.

### Step 1 — Brute Force O(n²)
```java
int count = 0;
for (int i = 0; i < nums.length; i++) {
    int sum = 0;
    for (int j = i; j < nums.length; j++) {
        sum += nums[j];
        if (sum == k) count++;
    }
}
```

### Step 2 — Optimal O(n) — Prefix Sum + HashMap

**Key insight:** `sum(i, j) = k` means `prefix[j] - prefix[i-1] = k`
So we need `prefix[i-1] = prefix[j] - k`
Count how many previous prefix sums equal `currentPrefix - k`.

```java
public int subarraySum(int[] nums, int k) {
    Map<Integer, Integer> map = new HashMap<>();
    map.put(0, 1); // handle subarrays starting at index 0

    int prefixSum = 0;
    int count = 0;

    for (int num : nums) {
        prefixSum += num;

        // How many previous positions had (prefixSum - k)?
        // Each such position marks the start of a valid subarray ending here
        count += map.getOrDefault(prefixSum - k, 0);

        map.merge(prefixSum, 1, Integer::sum);
    }
    return count;
}
```

### Dry Run
```
nums = [1, 2, 3], k = 3
map = {0:1}

i=0: num=1, prefix=1, lookup (1-3=-2)→0, map={0:1, 1:1}
i=1: num=2, prefix=3, lookup (3-3=0)→1 ✓, count=1, map={0:1,1:1,3:1}
i=2: num=3, prefix=6, lookup (6-3=3)→1 ✓, count=2, map={0:1,1:1,3:1,6:1}

Result: 2   ([1,2] and [3])  ✓
```

### Edge Cases
- Negative numbers: works! HashMap handles negative prefix sums
- k = 0: `map.put(0, 1)` sentinel handles subarrays of sum 0
- Single element equals k: caught by `prefix[j] - 0 = k` with sentinel

---

## 7. Pattern Variations

| Variation | Technique |
|---|---|
| Range sum (static array) | Build prefix, answer in O(1) |
| Range sum (mutable array) | Segment tree or BIT (Fenwick) |
| Subarray sum = k | Prefix + HashMap |
| Subarray sum divisible by k | Prefix mod k + HashMap |
| Count 0/1 subarrays | Transform 0→-1, apply sum=0 trick |
| 2D rectangle sum | 2D prefix |
| Minimum length subarray ≥ k (positive) | Sliding window is better |
| Minimum length subarray ≥ k (with negatives) | Deque + prefix sum |

---

## 8. Common Interview Mistakes

1. **Forgetting the sentinel `prefix[0] = 0`** — misses subarrays starting at index 0
2. **Off-by-one in indexing** — use `prefix[r+1] - prefix[l]` carefully
3. **Using prefix sum when array is mutable** — use BIT/segment tree instead
4. **Not handling negative numbers** — prefix sum still works, but sliding window won't
5. **2D formula error** — forgetting to add back the doubly-subtracted corner: `+prefix[r1][c1]`

---

## 9. Interview Cheat Sheet

```
PREFIX SUM — MENTAL CHECKLIST
==============================
□ Range sum query? → Build prefix array
□ Subarray sum = k? → prefix + HashMap, sentinel {0:1}
□ 2D rectangle sum? → 2D prefix, inclusion-exclusion
□ Has negative values? → Still works; sliding window won't
□ Array is mutable? → Need Fenwick/Segment Tree instead

FORMULAS
========
1D:  sum(l,r) = prefix[r+1] - prefix[l]
2D:  sum = P[r2+1][c2+1] - P[r1][c2+1] - P[r2+1][c1] + P[r1][c1]
KEY: count += map.get(prefixSum - k)  →  subarray sum = k trick

TRICKS
======
- Always put {0:1} in map before loop (subarrays from index 0)
- Transform 0→-1 to convert "equal count of 0s and 1s" → "sum=0"
- Prefix mod k for divisibility problems: handles negative remainder with +k
```

---

## 10. Practice Roadmap

**Beginner:**
- LC 303 — Range Sum Query
- LC 724 — Pivot Index
- LC 1480 — Running Sum of 1D Array

**Intermediate:**
- LC 560 — Subarray Sum Equals K
- LC 525 — Contiguous Array
- LC 974 — Subarray Sums Divisible by K
- LC 304 — Range Sum Query 2D

**Taking Hard:**
- LC 862 — Shortest Subarray with Sum ≥ K
- LC 1074 — Number of Submatrices that Sum to Target
- LC 327 — Count of Range Sum

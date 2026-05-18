# Pattern 04 — Kadane's Algorithm

---

## 1. Pattern Recognition Guide

**Keywords to look for:**
- "maximum sum subarray"
- "contiguous subarray with largest sum"
- "maximum product subarray"
- "maximum circular subarray sum"

**When to reach for Kadane's:**
- Maximum/minimum of a contiguous subarray
- Can contain negative numbers
- O(n) is required (brute force is O(n²) or O(n³))

**Signal phrase:** _"Find the subarray with the maximum sum"_ → Kadane's
**Extended signal:** _"Maximum product"_ or _"Circular subarray"_ → Kadane's variant

---

## 2. Core Intuition

**Why brute force fails:**
All O(n²) pairs of (i, j) = 10¹⁰ for n=10⁵.

**The key observation:**
At each position, you make one decision:
1. **Extend** the previous subarray (add current element to it)
2. **Start fresh** (begin a new subarray at current element)

Pick whichever gives a larger sum.

**Core invariant:**
`currentMax = max(arr[i], currentMax + arr[i])`

If `currentMax` ever drops below `arr[i]`, it means the previous subarray hurts more than it helps — restart.

```
arr = [-2, 1, -3, 4, -1, 2, 1, -5, 4]

i=0: cur=max(-2,      -2) = -2    globalMax=-2
i=1: cur=max( 1,  -2+1) = 1      globalMax=1
i=2: cur=max(-3,   1-3) = -2     globalMax=1
i=3: cur=max( 4,  -2+4) = 4      globalMax=4
i=4: cur=max(-1,   4-1) = 3      globalMax=4
i=5: cur=max( 2,   3+2) = 5      globalMax=5
i=6: cur=max( 1,   5+1) = 6      globalMax=6  ← answer
i=7: cur=max(-5,   6-5) = 1      globalMax=6
i=8: cur=max( 4,   1+4) = 5      globalMax=6

Max subarray = [4,-1,2,1], sum = 6
```

---

## 3. Generic Java Templates

### Template A — Basic Kadane (Maximum Subarray Sum)

```java
public int maxSubArray(int[] nums) {
    int currentMax = nums[0]; // must start with first element (not 0!)
    int globalMax = nums[0];

    for (int i = 1; i < nums.length; i++) {
        // Either extend or restart
        currentMax = Math.max(nums[i], currentMax + nums[i]);
        globalMax = Math.max(globalMax, currentMax);
    }

    return globalMax;
}
```

### Template B — With Subarray Indices Tracking

```java
public int[] maxSubArrayWithIndices(int[] nums) {
    int currentMax = nums[0];
    int globalMax = nums[0];
    int start = 0, end = 0, tempStart = 0;

    for (int i = 1; i < nums.length; i++) {
        if (nums[i] > currentMax + nums[i]) {
            currentMax = nums[i];
            tempStart = i;     // potential new start
        } else {
            currentMax += nums[i];
        }

        if (currentMax > globalMax) {
            globalMax = currentMax;
            start = tempStart;
            end = i;
        }
    }

    return new int[]{globalMax, start, end};
}
```

### Template C — Maximum Product Subarray

```java
// Key difference: negatives can become positive when multiplied by another negative
// Track BOTH max and min at each step
public int maxProduct(int[] nums) {
    int maxProd = nums[0];
    int minProd = nums[0]; // tracks most negative product
    int result = nums[0];

    for (int i = 1; i < nums.length; i++) {
        // Multiplying by negative swaps max and min
        if (nums[i] < 0) {
            int temp = maxProd;
            maxProd = minProd;
            minProd = temp;
        }

        maxProd = Math.max(nums[i], maxProd * nums[i]);
        minProd = Math.min(nums[i], minProd * nums[i]);

        result = Math.max(result, maxProd);
    }

    return result;
}
```

### Template D — Maximum Circular Subarray Sum

```java
// Circular = either a normal subarray OR total_sum - minimum_subarray
public int maxSubarraySumCircular(int[] nums) {
    int totalSum = 0;
    int currentMax = 0, globalMax = nums[0];
    int currentMin = 0, globalMin = nums[0];

    for (int num : nums) {
        totalSum += num;

        currentMax = Math.max(num, currentMax + num);
        globalMax = Math.max(globalMax, currentMax);

        currentMin = Math.min(num, currentMin + num);
        globalMin = Math.min(globalMin, currentMin);
    }

    // If all numbers negative, globalMin == totalSum, return globalMax
    if (globalMax < 0) return globalMax;

    // Max of: non-circular case, circular case
    return Math.max(globalMax, totalSum - globalMin);
}
```

---

## 4. Complexity Cheatsheet

| Variant | Time | Space |
|---|---|---|
| Basic Kadane | O(n) | O(1) |
| With indices | O(n) | O(1) |
| Max product | O(n) | O(1) |
| Circular subarray | O(n) | O(1) |

---

## 5. Canonical Code Problems

### Core (Must Solve)

| LC # | Problem | Difficulty | Why Important |
|---|---|---|---|
| 53 | Maximum Subarray | Medium | Pure Kadane's |
| 918 | Maximum Sum Circular Subarray | Medium | Circular variant |
| 152 | Maximum Product Subarray | Medium | Track min and max |
| 1749 | Maximum Absolute Sum of Any Subarray | Medium | Max + min subarrays |
| 121 | Best Time to Buy and Sell Stock | Easy | Kadane's in disguise |

### Advanced Variations

| LC # | Problem | Difficulty | Why Important |
|---|---|---|---|
| 123 | Best Time to Buy and Sell Stock III | Hard | Two transactions |
| 135 | Maximum Sum of Two Non-Overlapping Subarrays | Medium | Two Kadane passes |
| 2272 | Substring with Largest Variance | Hard | Kadane on character diff |
| 363 | Max Sum of Rectangle No Larger Than K | Hard | Kadane + BST |
| 1191 | K-Concatenation Maximum Sum | Medium | Kadane with repetition |

---

## 6. Solve Step-by-Step — LC 53: Maximum Subarray

**Problem:** Find the contiguous subarray with the largest sum.

### Step 1 — Brute Force O(n²)
```java
int maxSum = Integer.MIN_VALUE;
for (int i = 0; i < n; i++) {
    int sum = 0;
    for (int j = i; j < n; j++) {
        sum += nums[j];
        maxSum = Math.max(maxSum, sum);
    }
}
```

### Step 2 — Divide and Conquer O(n log n)
Split array in half, find max crossing the midpoint. Recursively solve.

### Step 3 — Optimal O(n) — Kadane's

```java
public int maxSubArray(int[] nums) {
    int currentMax = nums[0];
    int globalMax = nums[0];

    for (int i = 1; i < nums.length; i++) {
        currentMax = Math.max(nums[i], currentMax + nums[i]);
        globalMax = Math.max(globalMax, currentMax);
    }

    return globalMax;
}
```

### Dry Run (already shown above in intuition section)

### Edge Cases
- Single element: `[-5]` → `-5` (initializing with `Integer.MIN_VALUE` would be wrong if you start loop at 0)
- All negatives: `[-3,-1,-2]` → `-1` (largest single element)
- All positive: sum of entire array
- Mixed: classic sliding decision

---

## 7. Pattern Variations

| Variation | Key Change |
|---|---|
| Max sum subarray | Basic Kadane |
| Min sum subarray | Flip max/min in Kadane |
| Max product subarray | Track both max and min (negative × negative) |
| Circular max subarray | total - min_subarray |
| At least k elements | Track prefix sums with extra constraint |
| Stock buy/sell | Kadane on price differences |

---

## 8. Common Interview Mistakes

1. **Initializing `currentMax = 0`** — wrong when all numbers are negative; use `nums[0]`
2. **Not handling single element case** — always initialize with `nums[0]`
3. **Max product: forgetting to track minimum** — a large negative × negative = large positive
4. **Circular: missing `if (globalMax < 0) return globalMax`** — infinite loop / wrong answer
5. **Confusing `currentMax` with `globalMax`** — `currentMax` resets, `globalMax` never goes down

---

## 9. Interview Cheat Sheet

```
KADANE'S — MENTAL CHECKLIST
============================
□ Maximum/minimum CONTIGUOUS subarray?
□ All negative values possible? → init with nums[0], not 0
□ Product instead of sum? → track min AND max
□ Circular array? → max(normal, totalSum - minSubarray)
□ Stock prices? → Kadane on differences arr[i]-arr[i-1]

FORMULA
=======
currentMax = max(nums[i], currentMax + nums[i])
globalMax  = max(globalMax, currentMax)

TRICKS
======
- Kadane = DP where dp[i] = max subarray ending at i
- Product variant: swap max/min when current num < 0
- "Best time to buy/sell" → convert to diff array → Kadane
- Circular: total_sum - min_subarray (complement trick)
```

---

## 10. Practice Roadmap

**Beginner:**
- LC 53 — Maximum Subarray
- LC 121 — Best Time to Buy and Sell Stock

**Intermediate:**
- LC 152 — Maximum Product Subarray
- LC 918 — Maximum Sum Circular Subarray
- LC 1749 — Maximum Absolute Sum of Any Subarray

**Taking Hard:**
- LC 123 — Best Time to Buy and Sell Stock III
- LC 363 — Max Sum of Rectangle No Larger Than K
- LC 2272 — Substring with Largest Variance

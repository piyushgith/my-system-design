# Pattern 01 — Two Pointers

---

## 1. Pattern Recognition Guide

**Keywords to look for:**
- "pair", "triplet", "two elements that sum to X"
- "sorted array", "palindrome", "reverse"
- "remove duplicates", "merge sorted arrays"
- "container with most water", "minimum difference between pairs"

**When to reach for Two Pointers:**
- Array/string is **sorted** (or can be sorted)
- You need to find **pairs/triplets** with a condition
- You need to **avoid O(n²)** brute force
- Problem involves comparing elements from **both ends** or **two sequences**

**Signal phrase:** _"Find two elements such that..."_ → Two Pointers

---

## 2. Core Intuition

**Why brute force fails:**
Checking every pair = O(n²). For n=10⁵ that's 10¹⁰ operations — TLE.

**The key observation:**
In a sorted array, if `arr[left] + arr[right] > target`, moving `right` left gives a smaller sum.
If sum is too small, move `left` right. You **converge** in O(n).

**Core invariant:**
One pointer starts at the beginning, one at the end. Each step eliminates a possibility, so you never revisit the same pair.

```
[1, 2, 3, 4, 6]   target = 6
 L              R  → 1+6=7 > 6 → move R left
 L           R     → 1+4=5 < 6 → move L right
    L        R     → 2+4=6 ✓ FOUND
```

---

## 3. Generic Java Templates

### Template A — Opposite Ends (Pair Sum / Palindrome)

```java
public int[] twoSumSorted(int[] arr, int target) {
    int left = 0;
    int right = arr.length - 1;

    while (left < right) {
        int sum = arr[left] + arr[right];

        if (sum == target) {
            return new int[]{left, right};   // found
        } else if (sum < target) {
            left++;   // need larger sum
        } else {
            right--;  // need smaller sum
        }
    }
    return new int[]{-1, -1}; // not found
}
```

### Template B — Same Direction (Remove Duplicates / Fast-Slow)

```java
public int removeDuplicates(int[] arr) {
    if (arr.length == 0) return 0;

    int slow = 0; // boundary of unique elements

    for (int fast = 1; fast < arr.length; fast++) {
        if (arr[fast] != arr[slow]) {
            slow++;
            arr[slow] = arr[fast];
        }
    }
    return slow + 1; // length of unique portion
}
```

### Template C — Two Arrays (Merge Sorted)

```java
public int[] mergeSorted(int[] a, int[] b) {
    int i = 0, j = 0, k = 0;
    int[] result = new int[a.length + b.length];

    while (i < a.length && j < b.length) {
        if (a[i] <= b[j]) result[k++] = a[i++];
        else              result[k++] = b[j++];
    }
    while (i < a.length) result[k++] = a[i++];
    while (j < b.length) result[k++] = b[j++];

    return result;
}
```

---

## 4. Complexity Cheatsheet

| Variant | Time | Space | Notes |
|---|---|---|---|
| Opposite ends | O(n) | O(1) | Requires sorted input |
| Same direction | O(n) | O(1) | In-place modification |
| Two arrays | O(n + m) | O(n + m) | Extra array for result |
| With sort | O(n log n) | O(1) | Sort cost dominates |

---

## 5. Canonical Code Problems

### Core (Must Solve)

| LC # | Problem | Difficulty | Why Important |
|---|---|---|---|
| 167 | Two Sum II (sorted array) | Easy | Classic template starter |
| 26 | Remove Duplicates from Sorted Array | Easy | Same-direction template |
| 125 | Valid Palindrome | Easy | Char comparison + skip |
| 15 | 3Sum | Medium | Two pointers inside a loop |
| 11 | Container With Most Water | Medium | Greedy pointer movement |

### Advanced Variations

| LC # | Problem | Difficulty | Why Important |
|---|---|---|---|
| 16 | 3Sum Closest | Medium | Track closest sum |
| 18 | 4Sum | Medium | Nested two pointers |
| 42 | Trapping Rain Water | Hard | Two-pointer with max tracking |
| 75 | Sort Colors (Dutch Flag) | Medium | Three pointers |
| 977 | Squares of a Sorted Array | Easy | Merge from outside in |

---

## 6. Solve Step-by-Step — LC 15: 3Sum

**Problem:** Find all unique triplets in array that sum to zero.

### Step 1 — Brute Force O(n³)
```java
// Check every combination of 3 elements → TLE for large input
for (int i = 0; i < n; i++)
  for (int j = i+1; j < n; j++)
    for (int k = j+1; k < n; k++)
      if (arr[i]+arr[j]+arr[k]==0) add triplet
```

### Step 2 — Better O(n²) with HashSet
Use a set for the third element. Still O(n²) time but O(n) space.

### Step 3 — Optimal O(n²) Two Pointers

```java
public List<List<Integer>> threeSum(int[] nums) {
    List<List<Integer>> result = new ArrayList<>();
    Arrays.sort(nums); // CRITICAL: sort first

    for (int i = 0; i < nums.length - 2; i++) {
        // Skip duplicates for i
        if (i > 0 && nums[i] == nums[i - 1]) continue;

        // Early exit: smallest triplet already > 0
        if (nums[i] > 0) break;

        int left = i + 1;
        int right = nums.length - 1;

        while (left < right) {
            int sum = nums[i] + nums[left] + nums[right];

            if (sum == 0) {
                result.add(Arrays.asList(nums[i], nums[left], nums[right]));
                // Skip duplicates for left and right
                while (left < right && nums[left] == nums[left + 1]) left++;
                while (left < right && nums[right] == nums[right - 1]) right--;
                left++;
                right--;
            } else if (sum < 0) {
                left++;
            } else {
                right--;
            }
        }
    }
    return result;
}
```

### Dry Run
```
Input: [-4, -1, -1, 0, 1, 2]
i=0: nums[i]=-4, L=-1, R=2 → sum=-3 < 0 → L++
     L=0, R=2 → sum=-2 < 0 → L++
     L=1, R=2 → sum=-1 < 0 → L++
     L=R → stop
i=1: nums[i]=-1, L=0, R=2 → sum=1 > 0 → R--
     L=0, R=1 → sum=0 ✓ → add [-1,0,1], skip dupes
i=2: skip (nums[2]==nums[1])
...
Result: [[-1,0,1],[-1,-1,2]]
```

### Edge Cases
- All zeros: `[0,0,0,0]` → `[[0,0,0]]` (must deduplicate)
- All positive: `[1,2,3]` → `[]`
- Length < 3: return empty

---

## 7. Pattern Variations

| Variation | Approach |
|---|---|
| Pair sum (sorted) | Classic opposite ends |
| Pair sum (unsorted) | Sort first, then two pointers |
| 3Sum / 4Sum | Outer loop(s) + inner two pointers |
| Remove elements | Slow/fast same-direction |
| Palindrome check | Compare chars from both ends |
| Merge two arrays | Two pointers on both arrays |
| Partition (Dutch Flag) | Three pointers (low/mid/high) |

---

## 8. Common Interview Mistakes

1. **Forgetting to sort** before applying opposite-ends template — results are wrong
2. **Not skipping duplicates** in 3Sum — duplicate triplets in output
3. **`left < right` vs `left <= right`** — use `<` for pairs (need 2 distinct elements)
4. **Infinite loop** when not advancing both pointers after finding a match
5. **Integer overflow** in sum — use `long` when values are large
6. **Modifying array** when problem says not to — check constraints

---

## 9. Interview Cheat Sheet

```
TWO POINTERS — MENTAL CHECKLIST
================================
□ Is array sorted? (or should I sort it?)
□ Am I looking for a pair/triplet satisfying a condition?
□ Can I eliminate possibilities with each pointer move?
□ Opposite ends OR same direction?
□ Handle duplicates (sort + skip)
□ Watch for i < j vs i <= j boundary

FORMULAS
========
Opposite ends:  sum < target → left++   | sum > target → right--
Same direction: arr[fast] != arr[slow]  → slow++, copy

TRICKS
======
- Sort first: O(n log n) but enables O(n) scan
- Nested loop + two pointers = O(n²) for kSum
- Three pointers for Dutch Flag / partition problems
```

---

## 10. Practice Roadmap

**Beginner (warm up):**
- LC 167 — Two Sum II
- LC 26 — Remove Duplicates
- LC 125 — Valid Palindrome
- LC 977 — Squares of Sorted Array

**Intermediate (pattern fluency):**
- LC 15 — 3Sum
- LC 11 — Container With Most Water
- LC 16 — 3Sum Closest
- LC 75 — Sort Colors

**Taking Hard:**
- LC 42 — Trapping Rain Water
- LC 18 — 4Sum
- LC 259 — 3Sum Smaller (premium)
- LC 632 — Smallest Range Covering Elements from K Lists

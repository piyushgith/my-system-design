# Pattern 17 — Binary Search (All Variants)

---

## 1. Pattern Recognition Guide

**Keywords to look for:**
- "sorted array", "rotated sorted array"
- "find minimum / maximum that satisfies condition"
- "search in log n", "find target"
- "first/last occurrence", "leftmost/rightmost"
- "capacity", "speed", "days" — binary search on answer space

**Binary Search on Values (Answer Space):**
- "Minimum speed to arrive on time"
- "Minimum capacity to ship in D days"
- "Koko eating bananas" — any "find minimum X such that condition holds"

**Signal phrase:** _"Sorted array, find target"_ → Classic Binary Search
**Signal phrase:** _"Find minimum X such that f(X) is true"_ → Binary Search on range

---

## 2. Core Intuition

**Core idea:** Eliminate half the search space with each comparison.

The critical insight for all variants: **identify the invariant** — what is always true about where `left` and `right` point?

**Template trap:** Off-by-one errors kill binary search solutions. Pick ONE template and memorize it perfectly.

```
Classic: find target in [1,3,5,7,9]
                         0 1 2 3 4

left=0, right=4
mid=2, arr[2]=5 > 3 → right = mid-1 = 1
left=0, right=1
mid=0, arr[0]=1 < 3 → left = mid+1 = 1
left=1, right=1
mid=1, arr[1]=3 == target → return 1 ✓
```

---

## 3. Generic Java Templates

### Template A — Classic Binary Search (Find Exact Target)
```java
public int binarySearch(int[] nums, int target) {
    int left = 0, right = nums.length - 1;

    while (left <= right) {
        int mid = left + (right - left) / 2; // avoid overflow vs (left+right)/2

        if (nums[mid] == target) {
            return mid;
        } else if (nums[mid] < target) {
            left = mid + 1;
        } else {
            right = mid - 1;
        }
    }

    return -1; // not found
}
```

### Template B — Left-Most Occurrence (First True Position)
```java
// Returns index of first element >= target (lower bound)
// Returns nums.length if all elements < target
public int lowerBound(int[] nums, int target) {
    int left = 0, right = nums.length; // right = nums.length (open end)

    while (left < right) { // NOTE: < not <=
        int mid = left + (right - left) / 2;

        if (nums[mid] < target) {
            left = mid + 1;
        } else {
            right = mid; // mid could be the answer, don't exclude it
        }
    }

    return left; // left == right = first position where nums[i] >= target
}

// First occurrence of exact target
public int firstOccurrence(int[] nums, int target) {
    int idx = lowerBound(nums, target);
    if (idx < nums.length && nums[idx] == target) return idx;
    return -1;
}
```

### Template C — Right-Most Occurrence (Last True Position)
```java
// Returns index of last element <= target (upper bound - 1)
public int lastOccurrence(int[] nums, int target) {
    int left = 0, right = nums.length;

    while (left < right) {
        int mid = left + (right - left) / 2;

        if (nums[mid] <= target) {
            left = mid + 1; // mid is valid but maybe not the last
        } else {
            right = mid;
        }
    }

    // left-1 = last index where nums[i] <= target
    int idx = left - 1;
    if (idx >= 0 && nums[idx] == target) return idx;
    return -1;
}
```

### Template D — Binary Search on Answer Space
```java
// Find minimum value in [lo, hi] such that condition(mid) is true
// Requires: once condition is true, it stays true (monotonic)
public int binarySearchOnRange(int lo, int hi) {
    while (lo < hi) {
        int mid = lo + (hi - lo) / 2;

        if (condition(mid)) {
            hi = mid; // mid could be the answer, search left half
        } else {
            lo = mid + 1; // mid is too small, search right half
        }
    }

    return lo; // lo == hi = minimum value satisfying condition
}

// Example: Koko eating bananas (LC 875)
private boolean canEat(int[] piles, int speed, int h) {
    int hours = 0;
    for (int pile : piles) hours += (pile + speed - 1) / speed; // ceil division
    return hours <= h;
}

public int minEatingSpeed(int[] piles, int h) {
    int lo = 1, hi = Arrays.stream(piles).max().getAsInt();

    while (lo < hi) {
        int mid = lo + (hi - lo) / 2;
        if (canEat(piles, mid, h)) hi = mid;
        else lo = mid + 1;
    }

    return lo;
}
```

### Template E — Search in Rotated Sorted Array
```java
public int searchRotated(int[] nums, int target) {
    int left = 0, right = nums.length - 1;

    while (left <= right) {
        int mid = left + (right - left) / 2;

        if (nums[mid] == target) return mid;

        // Determine which half is sorted
        if (nums[left] <= nums[mid]) { // left half is sorted
            if (nums[left] <= target && target < nums[mid]) {
                right = mid - 1; // target in left sorted half
            } else {
                left = mid + 1;
            }
        } else { // right half is sorted
            if (nums[mid] < target && target <= nums[right]) {
                left = mid + 1; // target in right sorted half
            } else {
                right = mid - 1;
            }
        }
    }

    return -1;
}
```

### Template F — Binary Search on 2D Matrix
```java
// Matrix where each row and col is sorted
public boolean searchMatrix(int[][] matrix, int target) {
    int rows = matrix.length, cols = matrix[0].length;
    int left = 0, right = rows * cols - 1;

    while (left <= right) {
        int mid = left + (right - left) / 2;
        int val = matrix[mid / cols][mid % cols]; // convert 1D index to 2D

        if (val == target) return true;
        else if (val < target) left = mid + 1;
        else right = mid - 1;
    }

    return false;
}
```

---

## 4. Complexity Cheatsheet

| Variant | Time | Space |
|---|---|---|
| Classic binary search | O(log n) | O(1) |
| Binary search on range [lo,hi] | O(log(hi-lo) × f) | O(1) |
| 2D matrix binary search | O(log(m×n)) | O(1) |

f = cost of condition check per iteration.

---

## 5. Canonical Code Problems

### Core (Must Solve)

| LC # | Problem | Difficulty | Why Important |
|---|---|---|---|
| 704 | Binary Search | Easy | Classic template |
| 34 | Find First and Last Position | Medium | Left + right bound |
| 33 | Search in Rotated Sorted Array | Medium | Rotated variant |
| 74 | Search a 2D Matrix | Medium | 2D flattening |
| 875 | Koko Eating Bananas | Medium | Binary search on answer |

### Advanced Variations

| LC # | Problem | Difficulty | Why Important |
|---|---|---|---|
| 153 | Find Minimum in Rotated Sorted Array | Medium | Rotated min |
| 162 | Find Peak Element | Medium | Converge to peak |
| 1011 | Capacity to Ship Packages in D Days | Medium | Binary search on capacity |
| 410 | Split Array Largest Sum | Hard | Binary search on answer |
| 4 | Median of Two Sorted Arrays | Hard | Binary search on partition |

---

## 6. Solve Step-by-Step — LC 34: Find First and Last Position

**Problem:** Find `[first occurrence, last occurrence]` of target in sorted array. O(log n).

```java
public int[] searchRange(int[] nums, int target) {
    return new int[]{firstOccurrence(nums, target), lastOccurrence(nums, target)};
}

private int firstOccurrence(int[] nums, int target) {
    int left = 0, right = nums.length;
    while (left < right) {
        int mid = left + (right - left) / 2;
        if (nums[mid] < target) left = mid + 1;
        else right = mid;
    }
    return (left < nums.length && nums[left] == target) ? left : -1;
}

private int lastOccurrence(int[] nums, int target) {
    int left = 0, right = nums.length;
    while (left < right) {
        int mid = left + (right - left) / 2;
        if (nums[mid] <= target) left = mid + 1;
        else right = mid;
    }
    int idx = left - 1;
    return (idx >= 0 && nums[idx] == target) ? idx : -1;
}
```

### Dry Run: `[5,7,7,8,8,10]`, target=8
```
First occurrence:
left=0, right=6
mid=3, nums[3]=8 >= 8 → right=3
mid=1, nums[1]=7 < 8  → left=2
mid=2, nums[2]=7 < 8  → left=3
left=right=3 → nums[3]=8 ✓ → return 3

Last occurrence:
left=0, right=6
mid=3, nums[3]=8 <= 8 → left=4
mid=5, nums[5]=10 > 8 → right=5
mid=4, nums[4]=8 <= 8 → left=5
left=right=5 → idx=4, nums[4]=8 ✓ → return 4

Result: [3, 4]  ✓
```

---

## 7. Pattern Variations

| Problem | Binary Search On | Condition |
|---|---|---|
| Classic find | Array index | arr[mid] == target |
| First occurrence | Array index | arr[mid] >= target → go left |
| Last occurrence | Array index | arr[mid] <= target → go right |
| Rotated array | Array index | determine which half is sorted |
| 2D matrix | 1D index (mid/cols, mid%cols) | standard |
| Koko / ship capacity | Answer range [1, max] | can complete in time? |
| Split array | Answer range [max, sum] | can split within limit? |
| Peak finding | Array index | go toward higher neighbor |

---

## 8. Common Interview Mistakes

1. **`left + right) / 2` overflow** — always use `left + (right - left) / 2`
2. **`while (left <= right)` vs `while (left < right)`** — mixing templates causes infinite loops
3. **`right = mid` vs `right = mid - 1`** — depends on template; don't mix
4. **Rotated array: not handling `nums[left] == nums[mid]`** — with duplicates, need `left++`
5. **Binary search on answer: wrong lo/hi initialization** — lo must be minimum valid answer, hi must be maximum
6. **Forgetting to validate result** after lower bound binary search — `nums[left]` might not equal target

---

## 9. Interview Cheat Sheet

```
BINARY SEARCH — MENTAL CHECKLIST
===================================
□ Sorted (or monotonic) property? → Binary Search
□ Find exact target? → Template A (left <= right)
□ First/last occurrence? → Template B/C (left < right, right = nums.length)
□ Min/max satisfying condition? → Template D on answer range
□ Rotated array? → Template E (check which half is sorted)
□ 2D matrix? → Flatten to 1D: val = matrix[mid/cols][mid%cols]

TWO TEMPLATES TO MEMORIZE
============================
1. Exact search (left <= right):
   mid = left + (right-left)/2
   if found: return mid
   if too small: left = mid+1
   if too large: right = mid-1
   return -1

2. Left bound / answer range (left < right):
   mid = left + (right-left)/2
   if condition(mid): right = mid      ← mid could be answer
   else: left = mid+1
   return left

ANSWER SPACE BINARY SEARCH
============================
lo = minimum possible answer
hi = maximum possible answer
condition: is mid a valid answer?
→ find minimum valid answer

TRICKS
======
- Ceil division: (n + d - 1) / d   used in shipping/banana problems
- Peak finding: move toward higher neighbor (both directions valid)
- Count of elements < target = lowerBound(target) index
- Count of target occurrences = upperBound - lowerBound
```

---

## 10. Practice Roadmap

**Beginner:**
- LC 704 — Binary Search
- LC 35 — Search Insert Position
- LC 278 — First Bad Version

**Intermediate:**
- LC 34 — Find First and Last Position
- LC 33 — Search in Rotated Sorted Array
- LC 875 — Koko Eating Bananas
- LC 1011 — Capacity to Ship Packages
- LC 74 — Search a 2D Matrix

**Taking Hard:**
- LC 4 — Median of Two Sorted Arrays
- LC 410 — Split Array Largest Sum
- LC 154 — Find Minimum in Rotated Sorted Array II (duplicates)
- LC 668 — Kth Smallest Number in Multiplication Table

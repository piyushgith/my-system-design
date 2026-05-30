# Pattern 03 — Sliding Window

---

## 1. Pattern Recognition Guide

**Keywords to look for:**
- "subarray / substring of length k"
- "longest / shortest subarray with condition"
- "contiguous elements"
- "maximum/minimum sum in window"
- "at most k distinct", "exactly k"

**When to reach for Sliding Window:**
- Contiguous subarray/substring (not subsequence)
- Fixed or dynamic window over a sequence
- Optimizing over all subarrays — O(n) instead of O(n²)

**Signal phrase:** _"Longest substring without repeating characters"_ → Dynamic Sliding Window
**Signal phrase:** _"Maximum sum subarray of size k"_ → Fixed Sliding Window

---

## 2. Core Intuition

**Why brute force fails:**
Checking every subarray: O(n²) for 2 nested loops — TLE.

**The key observation:**
Instead of recomputing the entire window each time, slide it:
- Add the new element entering the window (right pointer advances)
- Remove the old element leaving the window (left pointer advances when invalid)

**Core invariant:**
The window `[left, right]` always satisfies the problem's constraint.
When the constraint is violated, shrink from the left.

```
Fixed Window (k=3):
[1, 3, -1, -3, 5, 3, 6]
 L      R         → add right, remove left when window > k

Dynamic Window:
expand right until invalid → shrink left until valid again
track max/min at each valid state
```

---

## 3. Generic Java Templates

### Template A — Fixed Window Size k

```java
public int maxSumFixed(int[] arr, int k) {
    int n = arr.length;
    if (n < k) return -1;

    // Build initial window
    int windowSum = 0;
    for (int i = 0; i < k; i++) {
        windowSum += arr[i];
    }

    int maxSum = windowSum;

    // Slide: add right element, remove left element
    for (int right = k; right < n; right++) {
        windowSum += arr[right];
        windowSum -= arr[right - k]; // element leaving window
        maxSum = Math.max(maxSum, windowSum);
    }

    return maxSum;
}
```

### Template B — Dynamic Window (Longest Valid Subarray)

```java
// Longest subarray/substring satisfying a condition
public int longestValid(int[] arr) {
    int left = 0;
    int maxLen = 0;
    // state: whatever you need to track the window's validity

    for (int right = 0; right < arr.length; right++) {
        // 1. Expand: include arr[right] in window state

        // 2. Shrink: while window is invalid, move left
        while (/* window is INVALID */) {
            // remove arr[left] from window state
            left++;
        }

        // 3. Window [left, right] is now valid — update answer
        maxLen = Math.max(maxLen, right - left + 1);
    }

    return maxLen;
}
```

### Template C — Dynamic Window (Shortest Valid Subarray)

```java
public int shortestValid(int[] arr, int target) {
    int left = 0;
    int minLen = Integer.MAX_VALUE;
    int windowSum = 0;

    for (int right = 0; right < arr.length; right++) {
        windowSum += arr[right]; // expand

        // Shrink as long as window remains valid
        while (windowSum >= target) {
            minLen = Math.min(minLen, right - left + 1);
            windowSum -= arr[left];
            left++;
        }
    }

    return minLen == Integer.MAX_VALUE ? 0 : minLen;
}
```

### Template D — At Most K (Count subarrays with at most k distinct)

```java
// Trick: "exactly k" = atMost(k) - atMost(k-1)
public int atMostK(int[] arr, int k) {
    Map<Integer, Integer> freq = new HashMap<>();
    int left = 0, count = 0;

    for (int right = 0; right < arr.length; right++) {
        freq.merge(arr[right], 1, Integer::sum);

        while (freq.size() > k) {
            freq.merge(arr[left], -1, Integer::sum);
            if (freq.get(arr[left]) == 0) freq.remove(arr[left]);
            left++;
        }

        count += right - left + 1; // all subarrays ending at right
    }
    return count;
}
```

---

## 4. Complexity Cheatsheet

| Variant | Time | Space |
|---|---|---|
| Fixed window | O(n) | O(1) |
| Dynamic window (array tracking) | O(n) | O(1) |
| Dynamic window (HashMap tracking) | O(n) | O(k) |
| At most K distinct | O(n) | O(k) |

The `left` pointer never goes backward — each element enters/leaves the window once → O(n).

---

## 5. Canonical Code Problems

### Core (Must Solve)

| LC # | Problem | Difficulty | Why Important |
|---|---|---|---|
| 643 | Maximum Average Subarray I | Easy | Fixed window intro |
| 3 | Longest Substring Without Repeating Characters | Medium | Dynamic window with set |
| 76 | Minimum Window Substring | Hard | Classic shrink template |
| 209 | Minimum Size Subarray Sum | Medium | Shortest valid window |
| 424 | Longest Repeating Character Replacement | Medium | Window with constraint |

### Advanced Variations

| LC # | Problem | Difficulty | Why Important |
|---|---|---|---|
| 567 | Permutation in String | Medium | Fixed window + char freq |
| 438 | Find All Anagrams in a String | Medium | Fixed window with map |
| 1004 | Max Consecutive Ones III | Medium | At most k flips |
| 992 | Subarrays with K Different Integers | Hard | AtMost trick |
| 480 | Sliding Window Median | Hard | Window + sorted structure |

---

## 6. Solve Step-by-Step — LC 3: Longest Substring Without Repeating Characters

**Problem:** Find length of longest substring with all unique characters.

### Step 1 — Brute Force O(n³)
```java
// Generate all substrings, check each for uniqueness
// O(n²) substrings × O(n) uniqueness check = O(n³)
```

### Step 2 — O(n²) with HashSet
Start each position, extend until duplicate found.

### Step 3 — Optimal O(n) — Dynamic Sliding Window

```java
public int lengthOfLongestSubstring(String s) {
    Map<Character, Integer> lastSeen = new HashMap<>(); // char → last index
    int left = 0;
    int maxLen = 0;

    for (int right = 0; right < s.length(); right++) {
        char c = s.charAt(right);

        // If char was seen within current window, jump left past it
        if (lastSeen.containsKey(c) && lastSeen.get(c) >= left) {
            left = lastSeen.get(c) + 1;
        }

        lastSeen.put(c, right);
        maxLen = Math.max(maxLen, right - left + 1);
    }

    return maxLen;
}
```

### Dry Run
```
s = "abcabcbb"
     0123456 7

right=0 c=a: map={a:0}, left=0, len=1
right=1 c=b: map={a:0,b:1}, left=0, len=2
right=2 c=c: map={a:0,b:1,c:2}, left=0, len=3
right=3 c=a: a seen at 0 ≥ left(0) → left=1, map={a:3,b:1,c:2}, len=3
right=4 c=b: b seen at 1 ≥ left(1) → left=2, map={a:3,b:4,c:2}, len=3
right=5 c=c: c seen at 2 ≥ left(2) → left=3, map={a:3,b:4,c:5}, len=3
right=6 c=b: b seen at 4 ≥ left(3) → left=5, map={a:3,b:6,c:5}, len=3  (6-5+1=2, max=3)
right=7 c=b: b seen at 6 ≥ left(5) → left=7, len=1

Answer: 3  ("abc")
```

### Edge Cases
- Empty string → 0
- All same characters `"aaaa"` → 1
- All unique `"abcd"` → 4
- Unicode / spaces → use HashMap (not array of 128)

---

## 7. Pattern Variations

| Variation | Template | Key State |
|---|---|---|
| Fixed window sum | Template A | running sum |
| Longest unique chars | Template B | HashMap lastSeen |
| Shortest subarray ≥ sum | Template C | running sum |
| At most k distinct chars | Template D | freq map |
| Exactly k distinct | D(k) - D(k-1) | two passes |
| Max ones with k flips | Template B | count of zeros |
| Anagram/permutation detection | Fixed + char freq | freq array[26] |

---

## 8. Common Interview Mistakes

1. **Moving `left` by 1 each time** when you can jump it directly (use `lastSeen` map)
2. **Checking `lastSeen.get(c) >= left`** — without this, you jump `left` backward!
3. **Forgetting to update answer inside the loop** (not just at the end)
4. **Using shrink-while for longest** and expand-while for shortest — they're opposite!
5. **Fixed window: forgetting `arr[right - k]`** — the element leaving the window
6. **AtMost trick confusion**: exactly(k) = atMost(k) - atMost(k-1) — remember which side

---

## 9. Interview Cheat Sheet

```
SLIDING WINDOW — MENTAL CHECKLIST
==================================
□ Contiguous subarray/substring? (not subsequence)
□ Fixed size k? → Template A
□ Longest valid window? → Template B (shrink while invalid)
□ Shortest valid window? → Template C (shrink while valid)
□ Count subarrays with exactly k? → atMost(k) - atMost(k-1)
□ Tracking what in window? (sum / freq map / set)

FORMULAS
========
Window length = right - left + 1
Fixed window remove = arr[right - k]
Longest: update answer AFTER shrinking
Shortest: update answer BEFORE shrinking

TRICKS
======
- HashMap<Char, lastIndex> for O(1) jump on duplicate
- Char freq with int[26] when only lowercase letters
- "Exactly K" always decomposes into two "at most" calls
- Each element enters window once, leaves once → O(n) guaranteed
```

---

## 10. Practice Roadmap

**Beginner:**
- LC 643 — Maximum Average Subarray I
- LC 1876 — Substrings of Size Three with Distinct Characters
- LC 219 — Contains Duplicate II

**Intermediate:**
- LC 3 — Longest Substring Without Repeating Characters
- LC 209 — Minimum Size Subarray Sum
- LC 567 — Permutation in String
- LC 1004 — Max Consecutive Ones III
- LC 424 — Longest Repeating Character Replacement

**Taking Hard:**
- LC 76 — Minimum Window Substring
- LC 992 — Subarrays with K Different Integers
- LC 480 — Sliding Window Median
- LC 239 — Sliding Window Maximum (monotonic deque!)

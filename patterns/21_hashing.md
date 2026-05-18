# Pattern 21 — Hashing (Existence, Counting, Duplicates, Pair Sum)

---

## 1. Pattern Recognition Guide

**Keywords to look for:**
- "two sum", "pair with sum k"
- "contains duplicate", "find duplicate"
- "frequency of elements", "most frequent"
- "group anagrams", "isomorphic strings"
- "subarray with sum k" (with prefix sum)
- "longest consecutive sequence"

**When to reach for Hashing:**
- O(1) lookup needed (replace nested loops)
- Counting/frequency of elements
- Checking membership quickly
- Grouping elements by some key

**Signal phrase:** _"Two elements that sum to target"_ → HashMap (complement lookup)
**Signal phrase:** _"Group strings by their character frequency"_ → HashMap with sorted-string key

---

## 2. Core Intuition

**HashSet**: O(1) existence check. Replaces the inner loop in O(n²) → O(n).
**HashMap**: O(1) key-value storage. Replaces recomputation with cached results.

**The Two Sum insight:**
Instead of: "for each pair, check if sum = target" → O(n²)
Do: "for each element x, look up (target - x) in a map" → O(n)

```
nums = [2, 7, 11, 15], target = 9

Without hash: check (2,7), (2,11), (2,15), (7,11)... → O(n²)

With hash:
i=0: need 9-2=7, map={}, 7 not found → map={2:0}
i=1: need 9-7=2, 2 is in map at idx 0 → return [0,1]  ✓
```

---

## 3. Generic Java Templates

### Template A — Check for Existence (Two Sum)
```java
public int[] twoSum(int[] nums, int target) {
    Map<Integer, Integer> seen = new HashMap<>(); // value → index

    for (int i = 0; i < nums.length; i++) {
        int complement = target - nums[i];

        if (seen.containsKey(complement)) {
            return new int[]{seen.get(complement), i};
        }

        seen.put(nums[i], i);
    }

    return new int[]{-1, -1}; // no solution
}
```

### Template B — Counting Elements (Frequency Map)
```java
public Map<Integer, Integer> frequencyMap(int[] nums) {
    Map<Integer, Integer> freq = new HashMap<>();
    for (int num : nums) {
        freq.merge(num, 1, Integer::sum); // freq.put(num, freq.getOrDefault(num,0)+1)
    }
    return freq;
}

// Top K Frequent
public int[] topKFrequent(int[] nums, int k) {
    Map<Integer, Integer> freq = new HashMap<>();
    for (int num : nums) freq.merge(num, 1, Integer::sum);

    // Bucket sort by frequency
    List<Integer>[] buckets = new List[nums.length + 1];
    for (Map.Entry<Integer, Integer> e : freq.entrySet()) {
        int f = e.getValue();
        if (buckets[f] == null) buckets[f] = new ArrayList<>();
        buckets[f].add(e.getKey());
    }

    int[] result = new int[k];
    int idx = 0;
    for (int i = buckets.length - 1; i >= 0 && idx < k; i--) {
        if (buckets[i] != null) {
            for (int num : buckets[i]) {
                result[idx++] = num;
                if (idx == k) break;
            }
        }
    }
    return result;
}
```

### Template C — Duplicate Detection
```java
// Contains Duplicate
public boolean containsDuplicate(int[] nums) {
    Set<Integer> seen = new HashSet<>();
    for (int num : nums) {
        if (!seen.add(num)) return true; // add returns false if already present
    }
    return false;
}

// Contains Duplicate Within K Distance
public boolean containsNearbyDuplicate(int[] nums, int k) {
    Map<Integer, Integer> lastIndex = new HashMap<>();
    for (int i = 0; i < nums.length; i++) {
        if (lastIndex.containsKey(nums[i]) && i - lastIndex.get(nums[i]) <= k) {
            return true;
        }
        lastIndex.put(nums[i], i);
    }
    return false;
}
```

### Template D — Grouping by Key (Anagrams)
```java
public List<List<String>> groupAnagrams(String[] strs) {
    Map<String, List<String>> groups = new HashMap<>();

    for (String str : strs) {
        // Canonical key: sorted characters
        char[] chars = str.toCharArray();
        Arrays.sort(chars);
        String key = new String(chars);

        groups.computeIfAbsent(key, k -> new ArrayList<>()).add(str);
    }

    return new ArrayList<>(groups.values());
}

// Alternative key: char count array as string
private String charCountKey(String s) {
    int[] count = new int[26];
    for (char c : s.toCharArray()) count[c - 'a']++;
    return Arrays.toString(count); // e.g., "[1,0,1,0,...,0]"
}
```

### Template E — Longest Consecutive Sequence
```java
public int longestConsecutive(int[] nums) {
    Set<Integer> numSet = new HashSet<>();
    for (int num : nums) numSet.add(num);

    int maxLen = 0;

    for (int num : numSet) {
        // Only start a new sequence at the beginning (no num-1 in set)
        if (!numSet.contains(num - 1)) {
            int curr = num;
            int len = 1;

            while (numSet.contains(curr + 1)) {
                curr++;
                len++;
            }

            maxLen = Math.max(maxLen, len);
        }
    }

    return maxLen;
}
```

### Template F — Pair Sum Variants
```java
// Count pairs with sum = k
public int countPairsWithSum(int[] nums, int k) {
    Map<Integer, Integer> freq = new HashMap<>();
    int count = 0;

    for (int num : nums) {
        int complement = k - num;
        count += freq.getOrDefault(complement, 0);
        freq.merge(num, 1, Integer::sum);
    }

    return count;
}

// 4Sum II: count tuples (a,b,c,d) where a+b+c+d = 0
public int fourSumCount(int[] a, int[] b, int[] c, int[] d) {
    Map<Integer, Integer> sumAB = new HashMap<>();
    for (int x : a) for (int y : b) sumAB.merge(x + y, 1, Integer::sum);

    int count = 0;
    for (int x : c) for (int y : d) count += sumAB.getOrDefault(-(x + y), 0);
    return count;
}
```

### Template G — Isomorphic / Pattern Matching
```java
public boolean isIsomorphic(String s, String t) {
    Map<Character, Character> sToT = new HashMap<>();
    Map<Character, Character> tToS = new HashMap<>();

    for (int i = 0; i < s.length(); i++) {
        char cs = s.charAt(i), ct = t.charAt(i);

        if (sToT.containsKey(cs) && sToT.get(cs) != ct) return false;
        if (tToS.containsKey(ct) && tToS.get(ct) != cs) return false;

        sToT.put(cs, ct);
        tToS.put(ct, cs);
    }
    return true;
}
```

---

## 4. Complexity Cheatsheet

| Operation | Time | Space |
|---|---|---|
| HashMap get/put | O(1) avg, O(n) worst | O(n) |
| HashSet add/contains | O(1) avg | O(n) |
| Group anagrams (sort key) | O(n × k log k) | O(n × k) |
| Longest consecutive | O(n) | O(n) |
| Two Sum | O(n) | O(n) |

HashMap worst case O(n) per operation is theoretical (hash collision). In practice, treated as O(1).

---

## 5. Canonical Code Problems

### Core (Must Solve)

| LC # | Problem | Difficulty | Why Important |
|---|---|---|---|
| 1 | Two Sum | Easy | Core complement lookup |
| 217 | Contains Duplicate | Easy | HashSet existence |
| 49 | Group Anagrams | Medium | HashMap grouping |
| 128 | Longest Consecutive Sequence | Medium | HashSet + sequence start |
| 347 | Top K Frequent Elements | Medium | Freq map + bucket sort |

### Advanced Variations

| LC # | Problem | Difficulty | Why Important |
|---|---|---|---|
| 454 | 4Sum II | Medium | Two-pass HashMap |
| 560 | Subarray Sum Equals K | Medium | Prefix sum + HashMap |
| 205 | Isomorphic Strings | Easy | Bidirectional mapping |
| 290 | Word Pattern | Easy | Pattern matching |
| 76 | Minimum Window Substring | Hard | Sliding window + freq map |

---

## 6. Solve Step-by-Step — LC 128: Longest Consecutive Sequence

**Problem:** Find length of the longest consecutive sequence. O(n) required.

### Why Naive O(n²) Fails
For each number, check if num+1, num+2... exist — repeated HashSet lookups per number.

### O(n) Insight: Only Start at Sequence Beginnings
A number `n` is a sequence start if and only if `n-1` is NOT in the set.
This ensures each number is only part of one sequence walk.

```java
public int longestConsecutive(int[] nums) {
    Set<Integer> set = new HashSet<>();
    for (int num : nums) set.add(num);

    int maxLen = 0;

    for (int num : set) {
        if (!set.contains(num - 1)) {   // sequence start
            int curr = num, len = 1;
            while (set.contains(curr + 1)) { curr++; len++; }
            maxLen = Math.max(maxLen, len);
        }
    }
    return maxLen;
}
```

### Dry Run: `[100,4,200,1,3,2]`
```
set = {100,4,200,1,3,2}

num=100: 99 not in set → start sequence
  101 not in set → len=1, maxLen=1

num=4: 3 is in set → NOT a start, skip

num=200: 199 not in set → start sequence
  201 not in set → len=1

num=1: 0 not in set → start sequence
  2 in set → len=2
  3 in set → len=3
  4 in set → len=4
  5 not in set → done, maxLen=4

Answer: 4  ([1,2,3,4])  ✓
```

---

## 7. Pattern Variations

| Problem | HashMap Key | HashMap Value |
|---|---|---|
| Two Sum | number | index |
| Subarray sum = k | prefix sum | count of occurrences |
| Group anagrams | sorted string | list of anagrams |
| Contains near duplicate | number | last seen index |
| Isomorphic strings | char (bidirectional) | mapped char |
| Top K frequent | number | frequency |
| Longest consecutive | — | just HashSet membership |

---

## 8. Common Interview Mistakes

1. **Two Sum: putting current element before checking** — would match element with itself; check first, then put
2. **Group anagrams: using char array as key** — arrays don't have value-based `.equals()`; use `String` or `Arrays.toString`
3. **Longest consecutive: iterating over array** instead of set — duplicates cause redundant work; iterate over set
4. **Not handling null in `getOrDefault`** — use `getOrDefault(key, 0)` not `get(key)` without null check
5. **Freq map with `merge`** — cleaner than `getOrDefault + put`; know both styles

---

## 9. Interview Cheat Sheet

```
HASHING — MENTAL CHECKLIST
=============================
□ Pair/complement lookup? → HashMap: value→index
□ Existence check (no index)? → HashSet
□ Frequency/count? → HashMap with merge(key, 1, Integer::sum)
□ Group by property? → HashMap<Key, List<Value>>
□ Deduplicate? → HashSet
□ Subarray sum = k? → Prefix sum + HashMap (see Pattern 02)

COMMON PATTERNS
===============
Two Sum:        map.get(target - nums[i]) → check, then put
Freq count:     map.merge(num, 1, Integer::sum)
Anagram key:    new String(sorted char array)
Near duplicate: map.get(num) and check distance

JAVA QUICK REF
==============
map.getOrDefault(key, 0)
map.merge(key, 1, Integer::sum)
map.computeIfAbsent(key, k -> new ArrayList<>()).add(val)
set.add(x)  // returns false if already present
!set.add(x) // true = duplicate found

TRICKS
======
- Longest consecutive: only start at sequence beginnings → O(n)
- Group anagrams: sort key OR character count array as string key
- 4Sum II: split into two pairs, hash first pair sums
- Bucket sort by frequency → O(n) top-k without heap
```

---

## 10. Practice Roadmap

**Beginner:**
- LC 1 — Two Sum
- LC 217 — Contains Duplicate
- LC 242 — Valid Anagram
- LC 205 — Isomorphic Strings

**Intermediate:**
- LC 49 — Group Anagrams
- LC 128 — Longest Consecutive Sequence
- LC 347 — Top K Frequent Elements
- LC 219 — Contains Duplicate II

**Taking Hard:**
- LC 560 — Subarray Sum Equals K
- LC 454 — 4Sum II
- LC 76 — Minimum Window Substring
- LC 30 — Substring with Concatenation of All Words

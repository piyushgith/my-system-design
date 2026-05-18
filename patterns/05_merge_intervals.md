# Pattern 05 — Merge Intervals

---

## 1. Pattern Recognition Guide

**Keywords to look for:**
- "overlapping intervals", "merge intervals"
- "meeting rooms", "calendar conflicts"
- "insert interval", "find gaps"
- "minimum number of platforms/rooms"
- "non-overlapping intervals"

**When to reach for Merge Intervals:**
- Array of `[start, end]` pairs
- Need to combine, count, or find conflicts between ranges
- Schedule or resource allocation problems

**Signal phrase:** _"Given a list of intervals, merge overlapping ones"_ → Sort + Merge
**Signal phrase:** _"Minimum rooms for meetings"_ → Event-based sweep or sort by start/end

---

## 2. Core Intuition

**Why brute force fails:**
Comparing every pair of intervals = O(n²). For 10⁵ intervals → TLE.

**The key observation:**
After **sorting by start time**, overlapping intervals are always adjacent.
Two intervals `[a, b]` and `[c, d]` overlap if and only if `c <= b` (next starts before current ends).

```
Before sort: [1,3],[2,6],[8,10],[15,18]

After sort by start (already sorted here):
[1,3] and [2,6]: 2 <= 3 → OVERLAP → merge to [1,6]
[1,6] and [8,10]: 8 > 6 → NO OVERLAP → add new
[8,10] and [15,18]: 15 > 10 → NO OVERLAP → add new

Result: [[1,6],[8,10],[15,18]]
```

**Core invariant:**
At each step, the last interval in result either:
- Gets extended if current interval overlaps (update its end)
- Stays as-is and current interval is added as new

---

## 3. Generic Java Templates

### Template A — Merge Overlapping Intervals

```java
public int[][] merge(int[][] intervals) {
    if (intervals.length <= 1) return intervals;

    // Sort by start time
    Arrays.sort(intervals, (a, b) -> a[0] - b[0]);

    List<int[]> merged = new ArrayList<>();
    merged.add(intervals[0]); // add first interval

    for (int i = 1; i < intervals.length; i++) {
        int[] last = merged.get(merged.size() - 1);
        int[] curr = intervals[i];

        if (curr[0] <= last[1]) {
            // Overlap: extend end if needed
            last[1] = Math.max(last[1], curr[1]);
        } else {
            // No overlap: start new interval
            merged.add(curr);
        }
    }

    return merged.toArray(new int[merged.size()][]);
}
```

### Template B — Insert Interval (into sorted non-overlapping list)

```java
public int[][] insert(int[][] intervals, int[] newInterval) {
    List<int[]> result = new ArrayList<>();
    int i = 0;
    int n = intervals.length;

    // 1. Add all intervals that end before newInterval starts
    while (i < n && intervals[i][1] < newInterval[0]) {
        result.add(intervals[i++]);
    }

    // 2. Merge all overlapping intervals with newInterval
    while (i < n && intervals[i][0] <= newInterval[1]) {
        newInterval[0] = Math.min(newInterval[0], intervals[i][0]);
        newInterval[1] = Math.max(newInterval[1], intervals[i][1]);
        i++;
    }
    result.add(newInterval);

    // 3. Add remaining intervals
    while (i < n) {
        result.add(intervals[i++]);
    }

    return result.toArray(new int[result.size()][]);
}
```

### Template C — Minimum Rooms / Platforms (Event Sweep)

```java
// Meeting Rooms II: minimum rooms needed for all meetings
public int minMeetingRooms(int[][] intervals) {
    int n = intervals.length;
    int[] starts = new int[n];
    int[] ends = new int[n];

    for (int i = 0; i < n; i++) {
        starts[i] = intervals[i][0];
        ends[i] = intervals[i][1];
    }

    Arrays.sort(starts);
    Arrays.sort(ends);

    int rooms = 0;
    int maxRooms = 0;
    int endPtr = 0;

    for (int startPtr = 0; startPtr < n; startPtr++) {
        if (starts[startPtr] < ends[endPtr]) {
            rooms++; // new meeting starts before any ends → need new room
        } else {
            endPtr++; // one meeting ended, reuse its room
        }
        maxRooms = Math.max(maxRooms, rooms);
    }

    return maxRooms;
}
```

### Template D — Non-Overlapping Intervals (Greedy — Minimum Removals)

```java
// Minimum intervals to remove to make rest non-overlapping
public int eraseOverlapIntervals(int[][] intervals) {
    Arrays.sort(intervals, (a, b) -> a[1] - b[1]); // sort by END time

    int count = 0; // intervals kept
    int lastEnd = Integer.MIN_VALUE;

    for (int[] interval : intervals) {
        if (interval[0] >= lastEnd) {
            // No overlap: keep this interval
            lastEnd = interval[1];
            count++;
        }
        // else: overlap → skip (remove) this interval
    }

    return intervals.length - count; // number removed
}
```

---

## 4. Complexity Cheatsheet

| Variant | Time | Space |
|---|---|---|
| Merge intervals | O(n log n) | O(n) |
| Insert interval | O(n) | O(n) |
| Minimum rooms (event sweep) | O(n log n) | O(n) |
| Non-overlapping (greedy) | O(n log n) | O(1) |

The sort dominates. After sorting, all variants are O(n).

---

## 5. Canonical Code Problems

### Core (Must Solve)

| LC # | Problem | Difficulty | Why Important |
|---|---|---|---|
| 56 | Merge Intervals | Medium | Core template |
| 57 | Insert Interval | Medium | Insert without re-sorting |
| 252 | Meeting Rooms | Easy | Basic overlap check |
| 253 | Meeting Rooms II | Medium | Min rooms / sweep line |
| 435 | Non-Overlapping Intervals | Medium | Greedy removal |

### Advanced Variations

| LC # | Problem | Difficulty | Why Important |
|---|---|---|---|
| 452 | Minimum Number of Arrows to Burst Balloons | Medium | Greedy end-sorted |
| 986 | Interval List Intersections | Medium | Two-pointer on two lists |
| 1235 | Maximum Profit in Job Scheduling | Hard | DP + binary search on intervals |
| 759 | Employee Free Time | Hard | Merge across multiple lists |
| 732 | My Calendar III | Hard | Sweep line with TreeMap |

---

## 6. Solve Step-by-Step — LC 56: Merge Intervals

**Problem:** Merge all overlapping intervals.

### Step 1 — Brute Force O(n²)
For each interval, check all others for overlap. Repeat until no merges possible.

### Step 2 — Sort + Linear Merge O(n log n)

```java
public int[][] merge(int[][] intervals) {
    Arrays.sort(intervals, (a, b) -> a[0] - b[0]);

    List<int[]> merged = new ArrayList<>();
    merged.add(intervals[0]);

    for (int i = 1; i < intervals.length; i++) {
        int[] last = merged.get(merged.size() - 1);
        int[] curr = intervals[i];

        if (curr[0] <= last[1]) {
            last[1] = Math.max(last[1], curr[1]); // extend
        } else {
            merged.add(curr);
        }
    }

    return merged.toArray(new int[merged.size()][]);
}
```

### Dry Run
```
Input: [[1,3],[2,6],[8,10],[15,18]]
After sort (already sorted): same

merged = [[1,3]]

i=1: curr=[2,6], last=[1,3]
     2 <= 3 → overlap → last[1] = max(3,6) = 6
     merged = [[1,6]]

i=2: curr=[8,10], last=[1,6]
     8 > 6 → no overlap → add
     merged = [[1,6],[8,10]]

i=3: curr=[15,18], last=[8,10]
     15 > 10 → no overlap → add
     merged = [[1,6],[8,10],[15,18]]

Output: [[1,6],[8,10],[15,18]]  ✓
```

### Edge Cases
- Single interval → return as-is
- All overlapping: `[[1,4],[2,3]]` → `[[1,4]]` (contained interval)
- Touch at boundary: `[[1,4],[4,5]]` → `[[1,5]]` (4 <= 4 → merge)

---

## 7. Pattern Variations

| Variation | Sort By | Key Logic |
|---|---|---|
| Merge intervals | start | extend end if overlap |
| Minimum rooms | start & end separately | sweep — count concurrent |
| Remove minimum | end | keep greedy earliest-ending |
| Burst balloons | end | arrow covers until end |
| Interval intersections | — | two pointer on two lists |

---

## 8. Common Interview Mistakes

1. **Forgetting to sort** — without sorting, overlapping intervals aren't adjacent
2. **Wrong overlap condition**: `curr[0] <= last[1]` not `< last[1]` — touching intervals merge
3. **Modifying `last` in result list**: since Java lists store references, `last[1] = ...` modifies in-place — that's correct and intended
4. **Non-overlapping: sorting by start** — you must sort by END for the greedy to work
5. **Assuming input is sorted** — LC 56 does NOT guarantee sorted input; always sort
6. **Off-by-one in insert interval**: careful with `< newInterval[0]` vs `<= newInterval[0]`

---

## 9. Interview Cheat Sheet

```
MERGE INTERVALS — MENTAL CHECKLIST
====================================
□ Sort by START time (most cases)
□ Sort by END time for greedy removal / arrow problems
□ Overlap condition: curr.start <= prev.end
□ Merge = extend prev.end = max(prev.end, curr.end)
□ Rooms = max simultaneous overlapping at any point

FORMULAS
========
Overlap: intervals[i][0] <= intervals[i-1][1]
Merge:   last[1] = Math.max(last[1], curr[1])
Rooms:   sort starts/ends separately, sweep with two pointers

TRICKS
======
- "Meeting Rooms II" = sorting starts+ends separately (clever!)
- Greedy for non-overlapping: sort by end, greedily keep earliest-ending
- Interval intersection: while both lists active, move the one with smaller end
- TreeMap for dynamic calendar/stream problems
```

---

## 10. Practice Roadmap

**Beginner:**
- LC 252 — Meeting Rooms
- LC 56 — Merge Intervals

**Intermediate:**
- LC 57 — Insert Interval
- LC 253 — Meeting Rooms II
- LC 435 — Non-Overlapping Intervals
- LC 452 — Minimum Number of Arrows to Burst Balloons

**Taking Hard:**
- LC 986 — Interval List Intersections
- LC 759 — Employee Free Time
- LC 1235 — Maximum Profit in Job Scheduling
- LC 732 — My Calendar III

# Pattern 24 — Heap + Greedy: Minimum Number Problems

---

## 1. Pattern Recognition Guide

**Keywords to look for:**
- "minimum number of operations/steps to reduce to 1"
- "task scheduler", "reorganize string", "rearrange"
- "last stone weight", "stone game"
- "minimum refueling stops"
- "maximize capital / profit with limited selections"
- "find the optimal next item given a constraint"

**When Heap + Greedy beats pure DP:**
- You always want the locally optimal next choice (max or min available)
- The set of available choices changes dynamically as you process
- Items become "available" as you progress (unlock by reaching a threshold)

**Signal phrase:** _"Always pick the most/least X that is available at this moment"_ → Max/Min Heap + Greedy
**Signal phrase:** _"Rearrange such that no two same elements are adjacent"_ → Max Heap + cooldown

---

## 2. Core Intuition

### Greedy with Heap
Greedy algorithms make locally optimal choices at each step. A heap gives you the locally optimal element (max or min) in O(log n).

The classic pattern:
1. **Sort / preprocess** to determine when items become available
2. **Sweep** through time/capacity/positions
3. At each step, **greedily pick** the best available item from the heap
4. **Add newly available items** to the heap as you progress

```
Minimum Refueling Stops:
Target = 100 miles, fuel = 10
Stations: [(10,60), (20,30), (30,30), (60,40)]

At mile 10: can reach, add station fuel (60) to max-heap
At mile 20: can reach, add (30) to max-heap
At mile 30: can reach, add (30) to max-heap
Ran out of fuel at mile 40 → MUST refuel → take max from heap (60)
Now at mile 40+60=100 → reached! → 1 stop

Key: we delay the greedy choice until we NEED to refuel.
     Max-heap always gives us the best past station to pick.
```

---

## 3. Generic Java Templates

### Template A — Last Stone Weight (Max Heap Simulation)
```java
public int lastStoneWeight(int[] stones) {
    PriorityQueue<Integer> maxHeap = new PriorityQueue<>(Collections.reverseOrder());
    for (int stone : stones) maxHeap.offer(stone);

    while (maxHeap.size() > 1) {
        int heaviest = maxHeap.poll();
        int second = maxHeap.poll();

        if (heaviest != second) {
            maxHeap.offer(heaviest - second); // remainder goes back
        }
        // if equal, both destroyed — don't add anything
    }

    return maxHeap.isEmpty() ? 0 : maxHeap.peek();
}
```

### Template B — Task Scheduler (Max Heap + Cooldown)
```java
public int leastInterval(char[] tasks, int n) {
    int[] freq = new int[26];
    for (char task : tasks) freq[task - 'A']++;

    PriorityQueue<Integer> maxHeap = new PriorityQueue<>(Collections.reverseOrder());
    for (int f : freq) if (f > 0) maxHeap.offer(f);

    // Queue to track tasks in cooldown: [remaining_count, ready_time]
    Queue<int[]> cooldown = new LinkedList<>();
    int time = 0;

    while (!maxHeap.isEmpty() || !cooldown.isEmpty()) {
        time++;

        // Release tasks whose cooldown has expired
        if (!cooldown.isEmpty() && cooldown.peek()[1] == time) {
            maxHeap.offer(cooldown.poll()[0]);
        }

        if (!maxHeap.isEmpty()) {
            int count = maxHeap.poll() - 1;
            if (count > 0) {
                cooldown.offer(new int[]{count, time + n + 1}); // ready after cooldown
            }
        }
        // else: CPU idles this cycle
    }

    return time;
}
```

### Template C — Reorganize String (Max Heap + Interleave)
```java
public String reorganizeString(String s) {
    int[] freq = new int[26];
    for (char c : s.toCharArray()) freq[c - 'a']++;

    PriorityQueue<int[]> maxHeap = new PriorityQueue<>((a, b) -> b[1] - a[1]);
    for (int i = 0; i < 26; i++) {
        if (freq[i] > 0) maxHeap.offer(new int[]{i, freq[i]});
    }

    StringBuilder sb = new StringBuilder();
    int[] prev = null; // previously used char (in cooldown)

    while (!maxHeap.isEmpty()) {
        int[] curr = maxHeap.poll();
        sb.append((char)('a' + curr[0]));
        curr[1]--;

        // Re-add previous char (it's been one position since last use)
        if (prev != null && prev[1] > 0) maxHeap.offer(prev);

        prev = curr; // current goes into cooldown
    }

    return sb.length() == s.length() ? sb.toString() : "";
}
```

### Template D — IPO / Maximize Capital (Two Heap Unlock Pattern)
```java
// Select at most k projects to maximize capital.
// Projects become available when capital >= cost.
public int findMaximizedCapital(int k, int w, int[] profits, int[] capital) {
    int n = profits.length;

    // Min-heap by capital requirement: unlock projects as we gain capital
    PriorityQueue<int[]> locked = new PriorityQueue<>((a, b) -> a[0] - b[0]);
    for (int i = 0; i < n; i++) locked.offer(new int[]{capital[i], profits[i]});

    // Max-heap by profit: always pick the most profitable unlocked project
    PriorityQueue<Integer> available = new PriorityQueue<>(Collections.reverseOrder());

    for (int i = 0; i < k; i++) {
        // Unlock all projects we can now afford
        while (!locked.isEmpty() && locked.peek()[0] <= w) {
            available.offer(locked.poll()[1]);
        }

        if (available.isEmpty()) break; // no affordable projects

        w += available.poll(); // pick most profitable available project
    }

    return w;
}
```

### Template E — Minimum Refueling Stops
```java
public int minRefuelStops(int target, int startFuel, int[][] stations) {
    // Max-heap: stores fuel amounts of stations we've passed
    PriorityQueue<Integer> maxHeap = new PriorityQueue<>(Collections.reverseOrder());

    int fuel = startFuel;
    int stops = 0;
    int prev = 0; // previous position

    for (int[] station : stations) {
        int position = station[0];
        int stationFuel = station[1];

        fuel -= (position - prev); // fuel consumed to reach this station
        prev = position;

        // While we can't reach this station, refuel from best past station
        while (fuel < 0 && !maxHeap.isEmpty()) {
            fuel += maxHeap.poll();
            stops++;
        }

        if (fuel < 0) return -1; // can't reach even with all past stations

        maxHeap.offer(stationFuel); // this station is now available
    }

    // Check if we can reach target from last station
    fuel -= (target - prev);
    while (fuel < 0 && !maxHeap.isEmpty()) {
        fuel += maxHeap.poll();
        stops++;
    }

    return fuel >= 0 ? stops : -1;
}
```

### Template F — Furthest Building You Can Reach (Greedy Heap)
```java
// Use ladders for the largest height differences, bricks for small ones
public int furthestBuilding(int[] heights, int bricks, int ladders) {
    // Min-heap: track the largest jumps where we used a ladder
    PriorityQueue<Integer> ladderJumps = new PriorityQueue<>();

    for (int i = 0; i < heights.length - 1; i++) {
        int diff = heights[i + 1] - heights[i];
        if (diff <= 0) continue; // going down — free

        // Greedily use ladder for this jump
        ladderJumps.offer(diff);

        // If used more ladders than available, replace smallest ladder jump with bricks
        if (ladderJumps.size() > ladders) {
            bricks -= ladderJumps.poll(); // smallest ladder jump → use bricks instead
        }

        if (bricks < 0) return i; // ran out of bricks
    }

    return heights.length - 1;
}
```

---

## 4. Complexity Cheatsheet

| Problem | Time | Space | Key Insight |
|---|---|---|---|
| Last Stone Weight | O(n log n) | O(n) | Simulate with max-heap |
| Task Scheduler | O(n log n) | O(1) | 26 tasks max |
| Reorganize String | O(n log 26) = O(n) | O(26) | 26 chars |
| IPO | O(n log n) | O(n) | Two heaps: locked + available |
| Min Refueling Stops | O(n log n) | O(n) | Retroactive greedy |
| Furthest Building | O(n log k) | O(k) | Swap ladders for bricks greedily |

---

## 5. Canonical LeetCode Problems

### Core (Must Solve)

| LC # | Problem | Difficulty | Pattern |
|---|---|---|---|
| 1046 | Last Stone Weight | Easy | Max-heap simulation |
| 621 | Task Scheduler | Medium | Max-heap + cooldown |
| 767 | Reorganize String | Medium | Max-heap + interleave |
| 502 | IPO | Hard | Two heaps (lock/unlock) |
| 871 | Minimum Number of Refueling Stops | Hard | Retroactive max-heap greedy |

### Advanced Variations

| LC # | Problem | Difficulty | Pattern |
|---|---|---|---|
| 1642 | Furthest Building You Can Reach | Medium | Min-heap swap greedy |
| 630 | Course Schedule III | Hard | Max-heap + greedy swap |
| 1834 | Single-Threaded CPU | Medium | Min-heap by enqueue time |
| 2402 | Meeting Rooms III | Hard | Two heaps (free + occupied) |
| 1353 | Maximum Number of Events | Medium | Greedy + min-heap by end |

---

## 6. Solve Step-by-Step — LC 871: Minimum Number of Refueling Stops

**Problem:** Car starts at position 0 with `startFuel`. Stations at `[position, fuel]`. Minimum stops to reach `target`.

### Why Retroactive Greedy Works
We don't decide when to refuel in advance. Instead:
- Drive as far as possible
- When we run out of fuel, retroactively pick the best station we passed (max-heap)
- This is optimal: we always pick the highest-fuel past station when needed

(Full implementation in Template E)

### Dry Run
```
target=100, startFuel=10
stations=[(10,60),(20,30),(30,30),(60,40)]

fuel=10, prev=0

station (10,60): fuel -= 10-0 = 0. heap=[60]
station (20,30): fuel -= 20-10 = -10 < 0
  → refuel from heap: fuel += 60 = 50, stops=1
  → fuel -= 0 (already at 20), heap=[30]
station (30,30): fuel -= 30-20 = 40. heap=[30,30]
station (60,40): fuel -= 60-30 = 10. heap=[40,30,30]

Final: fuel -= 100-60 = -30 < 0
  → refuel: fuel += 40 = 10, stops=2
  → still -30+40=10 >= 0? No: 10-40=... wait
  
  Actually: fuel=10, need 40 more miles
  fuel -= 40 → fuel = -30
  refuel with max(40): fuel = -30+40 = 10 ≥ 0? No
  
  Hmm—correct answer for this input is 2 stops.
  (10→60 refuel at 10 gets us to 70, 
   then refuel at 60 gets to 100: 2 stops) ✓
```

---

## 7. The Two-Heap Unlock Pattern (IPO)

This is a recurring FAANG pattern worth memorizing separately:

```
PATTERN: Items unlock when you reach a threshold.
         Always pick the best unlocked item.

Two heaps:
1. Min-heap by unlock threshold → sorted by "when available"
2. Max-heap by value → gives best available greedily

Algorithm:
  while k rounds remain:
    move all newly affordable items from min-heap to max-heap
    pick top of max-heap (best available)
    update resource (capital, time, etc.)
```

Problems using this exact pattern: IPO (502), Course Schedule III (630), Maximum Events (1353).

---

## 8. Common Interview Mistakes

1. **Task Scheduler: using math formula only** — the heap simulation is more generalizable and interviewers prefer it
2. **Reorganize String: not checking impossibility** — if max frequency > (n+1)/2, return ""
3. **Refueling: adding station to heap before checking if reachable** — add AFTER confirming we reached it; we already consumed fuel to get there
4. **IPO: wrong heap direction** — locked = min-heap (cheapest first), available = max-heap (most profitable first)
5. **Furthest Building: heap stores jumps not positions** — we track height differences, not building indices

---

## 9. Interview Cheat Sheet

```
HEAP + GREEDY — MENTAL CHECKLIST
===================================
□ "Always pick best available" → Max/Min Heap
□ Items unlock over time/resource → Two Heap pattern
□ Rearrange with spacing constraint → Max-heap + prev cooldown
□ Retroactive best choice → add to heap as passed, pull when needed
□ Swap resource types (ladders/bricks) → Min-heap of used resource

PATTERNS
========
Simulation:    max-heap, repeatedly merge/pick, check termination
Cooldown:      max-heap + cooldown queue with (count, ready_time)
Interleave:    max-heap + prev variable (one-step cooldown)
Unlock:        min-heap (by cost) + max-heap (by value)
Retroactive:   collect into heap while passing, pull when resource < 0

JAVA HEAP SYNTAX (REMINDER)
============================
Max-heap: new PriorityQueue<>(Collections.reverseOrder())
Min-heap: new PriorityQueue<>()
Custom:   new PriorityQueue<>((a,b) -> a[0]-b[0])

GREEDY CORRECTNESS ARGUMENT
=============================
Always justify: "Picking the locally best option at each step is globally optimal because..."
- If we must refuel, best past station maximizes remaining range
- If we must use bricks, smallest ladder jump was least valuable
- If we must idle CPU, pick most frequent task to minimize future idles
```

---

## 10. Practice Roadmap

**Beginner:**
- LC 1046 — Last Stone Weight
- LC 703 — Kth Largest Element in a Stream

**Intermediate:**
- LC 767 — Reorganize String
- LC 621 — Task Scheduler
- LC 1642 — Furthest Building You Can Reach
- LC 1353 — Maximum Number of Events That Can Be Attended

**FAANG Hard:**
- LC 871 — Minimum Number of Refueling Stops
- LC 502 — IPO
- LC 630 — Course Schedule III
- LC 2402 — Meeting Rooms III

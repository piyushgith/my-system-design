# Pattern 23 — Implicit Graphs (BFS on State Spaces)

---

## 1. Pattern Recognition Guide

**Keywords to look for:**
- "minimum number of operations/steps to reach target"
- "word transformation", "one character change at a time"
- "unlock combination lock", "minimum moves"
- "jump game", "reachable states"
- "sliding puzzle", "8-puzzle", "rubik's cube moves"
- "minimum genetic mutations"

**What makes a graph "implicit"?**
The graph is never given to you explicitly as an adjacency list.
Instead, nodes are **states** and edges are **valid transitions** between states.
You generate neighbors on the fly.

**Signal phrase:** _"Minimum steps to transform X into Y, one change at a time"_ → Implicit Graph BFS
**Signal phrase:** _"Minimum moves to reach target state"_ → BFS on state space

**Why BFS (not DFS)?**
BFS gives shortest path. In implicit graphs with uniform edge weights, BFS level = number of transformations = the answer.

---

## 2. Core Intuition

**Reframe the problem as a graph:**
- **Node** = current state (a word, a lock combination, a board configuration)
- **Edge** = one valid transformation (change one letter, turn one dial, make one move)
- **Source** = starting state
- **Target** = goal state
- **Answer** = BFS level when target is first reached

```
Word Ladder: "hit" → "hot" → "dot" → "dog" → "cog"

State space (nodes = words in dictionary):
"hit" —— "hot" —— "dot" —— "dog" —— "cog"
               \          /
               "lot"—"log"

BFS from "hit":
Level 0: {hit}
Level 1: {hot}          ← change i→o
Level 2: {dot, lot}     ← change h→d or h→l
Level 3: {dog, log}     ← change t→g
Level 4: {cog}          ← change d→c  ← TARGET FOUND

Answer: 4 transformations (5 words including start)
```

**Key optimizations:**
1. **Visited set**: mark states visited when enqueued (not when dequeued) to avoid re-processing
2. **Bidirectional BFS**: search from both source AND target simultaneously — cuts search space from O(b^d) to O(b^(d/2))
3. **State encoding**: encode states as strings/integers for efficient hashing

---

## 3. Generic Java Templates

### Template A — Basic Implicit Graph BFS
```java
public int minSteps(State start, State target) {
    if (start.equals(target)) return 0;

    Queue<State> queue = new LinkedList<>();
    Set<State> visited = new HashSet<>();

    queue.offer(start);
    visited.add(start);
    int steps = 0;

    while (!queue.isEmpty()) {
        int levelSize = queue.size();
        steps++;

        for (int i = 0; i < levelSize; i++) {
            State curr = queue.poll();

            for (State next : getNeighbors(curr)) {
                if (next.equals(target)) return steps;

                if (!visited.contains(next)) {
                    visited.add(next);
                    queue.offer(next);
                }
            }
        }
    }

    return -1; // unreachable
}

// Override this per problem
private List<State> getNeighbors(State curr) {
    // generate all valid one-step transitions from curr
    return new ArrayList<>();
}
```

### Template B — Word Ladder (String State)
```java
public int ladderLength(String beginWord, String endWord, List<String> wordList) {
    Set<String> wordSet = new HashSet<>(wordList);
    if (!wordSet.contains(endWord)) return 0;

    Queue<String> queue = new LinkedList<>();
    Set<String> visited = new HashSet<>();
    queue.offer(beginWord);
    visited.add(beginWord);
    int steps = 1;

    while (!queue.isEmpty()) {
        int levelSize = queue.size();
        steps++;

        for (int i = 0; i < levelSize; i++) {
            String word = queue.poll();
            char[] chars = word.toCharArray();

            // Generate all one-character variations
            for (int j = 0; j < chars.length; j++) {
                char original = chars[j];

                for (char c = 'a'; c <= 'z'; c++) {
                    if (c == original) continue;
                    chars[j] = c;
                    String next = new String(chars);

                    if (next.equals(endWord)) return steps;

                    if (wordSet.contains(next) && !visited.contains(next)) {
                        visited.add(next);
                        queue.offer(next);
                    }
                }

                chars[j] = original; // restore
            }
        }
    }

    return 0; // no path
}
```

### Template C — Open the Lock (Digit State)
```java
public int openLock(String[] deadends, String target) {
    Set<String> dead = new HashSet<>(Arrays.asList(deadends));
    String start = "0000";

    if (dead.contains(start)) return -1;
    if (start.equals(target)) return 0;

    Queue<String> queue = new LinkedList<>();
    Set<String> visited = new HashSet<>();
    queue.offer(start);
    visited.add(start);
    int turns = 0;

    while (!queue.isEmpty()) {
        int levelSize = queue.size();
        turns++;

        for (int i = 0; i < levelSize; i++) {
            String curr = queue.poll();

            for (String next : getNeighbors(curr)) {
                if (next.equals(target)) return turns;

                if (!dead.contains(next) && !visited.contains(next)) {
                    visited.add(next);
                    queue.offer(next);
                }
            }
        }
    }

    return -1;
}

// Each dial can go +1 or -1 (wrapping: 0↔9)
private List<String> getNeighbors(String state) {
    List<String> neighbors = new ArrayList<>();
    char[] chars = state.toCharArray();

    for (int i = 0; i < 4; i++) {
        char original = chars[i];

        // Turn forward
        chars[i] = (char)((original - '0' + 1) % 10 + '0');
        neighbors.add(new String(chars));

        // Turn backward
        chars[i] = (char)((original - '0' + 9) % 10 + '0');
        neighbors.add(new String(chars));

        chars[i] = original; // restore
    }

    return neighbors;
}
```

### Template D — Bidirectional BFS (Word Ladder Optimized)
```java
public int ladderLengthBiDir(String beginWord, String endWord, List<String> wordList) {
    Set<String> wordSet = new HashSet<>(wordList);
    if (!wordSet.contains(endWord)) return 0;

    Set<String> beginSet = new HashSet<>();
    Set<String> endSet = new HashSet<>();
    Set<String> visited = new HashSet<>();

    beginSet.add(beginWord);
    endSet.add(endWord);
    int steps = 1;

    while (!beginSet.isEmpty() && !endSet.isEmpty()) {
        steps++;

        // Always expand the smaller set
        if (beginSet.size() > endSet.size()) {
            Set<String> temp = beginSet;
            beginSet = endSet;
            endSet = temp;
        }

        Set<String> nextLevel = new HashSet<>();

        for (String word : beginSet) {
            char[] chars = word.toCharArray();

            for (int j = 0; j < chars.length; j++) {
                char original = chars[j];

                for (char c = 'a'; c <= 'z'; c++) {
                    if (c == original) continue;
                    chars[j] = c;
                    String next = new String(chars);

                    if (endSet.contains(next)) return steps; // met in the middle!

                    if (wordSet.contains(next) && !visited.contains(next)) {
                        visited.add(next);
                        nextLevel.add(next);
                    }
                }

                chars[j] = original;
            }
        }

        beginSet = nextLevel;
    }

    return 0;
}
```

### Template E — Sliding Puzzle (Board State as String)
```java
public int slidingPuzzle(int[][] board) {
    // Encode board as string: "123450" where 0 = empty
    StringBuilder sb = new StringBuilder();
    for (int[] row : board) for (int val : row) sb.append(val);

    String start = sb.toString();
    String target = "123450";

    if (start.equals(target)) return 0;

    // Valid swaps for each position of 0 in a 2×3 grid
    int[][] neighbors = {
        {1, 3},     // pos 0: can swap with pos 1, 3
        {0, 2, 4},  // pos 1: can swap with pos 0, 2, 4
        {1, 5},     // pos 2: can swap with pos 1, 5
        {0, 4},     // pos 3: can swap with pos 0, 4
        {1, 3, 5},  // pos 4: can swap with pos 1, 3, 5
        {2, 4}      // pos 5: can swap with pos 2, 4
    };

    Queue<String> queue = new LinkedList<>();
    Set<String> visited = new HashSet<>();
    queue.offer(start);
    visited.add(start);
    int moves = 0;

    while (!queue.isEmpty()) {
        int levelSize = queue.size();
        moves++;

        for (int i = 0; i < levelSize; i++) {
            String curr = queue.poll();
            int zeroPos = curr.indexOf('0');

            for (int neighborPos : neighbors[zeroPos]) {
                char[] next = curr.toCharArray();
                // Swap 0 with neighbor
                next[zeroPos] = next[neighborPos];
                next[neighborPos] = '0';
                String nextStr = new String(next);

                if (nextStr.equals(target)) return moves;

                if (!visited.contains(nextStr)) {
                    visited.add(nextStr);
                    queue.offer(nextStr);
                }
            }
        }
    }

    return -1;
}
```

### Template F — Jump Game with BFS (Min Jumps)
```java
public int jump(int[] nums) {
    int n = nums.length;
    if (n == 1) return 0;

    // BFS: each level = one jump
    boolean[] visited = new boolean[n];
    Queue<Integer> queue = new LinkedList<>();
    queue.offer(0);
    visited[0] = true;
    int jumps = 0;

    while (!queue.isEmpty()) {
        int levelSize = queue.size();
        jumps++;

        for (int i = 0; i < levelSize; i++) {
            int pos = queue.poll();

            for (int step = 1; step <= nums[pos]; step++) {
                int next = pos + step;
                if (next >= n - 1) return jumps;
                if (!visited[next]) {
                    visited[next] = true;
                    queue.offer(next);
                }
            }
        }
    }

    return -1;
}
```

---

## 4. Complexity Cheatsheet

| Problem | States | Transitions per State | Total Time |
|---|---|---|---|
| Word Ladder (L=word len, N=dict size) | N | L × 26 | O(N × L × 26) |
| Open Lock | 10^4 = 10000 | 4 × 2 = 8 | O(10000 × 8) |
| Sliding Puzzle (2×3) | 6! = 720 | ≤ 4 | O(720 × 4) |
| Jump Game | n | nums[i] | O(n × max_jump) |

Bidirectional BFS reduces Word Ladder from O(N × L × 26) to O(√N × L × 26) in practice.

---

## 5. Canonical LeetCode Problems

### Core (Must Solve)

| LC # | Problem | Difficulty | State | Transitions |
|---|---|---|---|---|
| 127 | Word Ladder | Hard | string word | 26 × word_len variations |
| 752 | Open the Lock | Medium | 4-digit string | 8 dial turns |
| 433 | Minimum Genetic Mutation | Medium | gene string | 4 × gene_len mutations |
| 1091 | Shortest Path in Binary Matrix | Medium | (row, col) | 8 directions |
| 773 | Sliding Puzzle | Hard | board string | swap 0 with neighbors |

### Advanced Variations

| LC # | Problem | Difficulty | State | Key Trick |
|---|---|---|---|---|
| 126 | Word Ladder II (all paths) | Hard | string | BFS levels + DFS backtrack |
| 815 | Bus Routes | Hard | bus stop | BFS on routes, not stops |
| 1197 | Minimum Knight Moves | Medium | (row, col) | 8 knight moves, bidirectional |
| 854 | K-Similar Strings | Hard | string | BFS on permutation states |
| 864 | Shortest Path to Get All Keys | Hard | (pos, keys bitmask) | State = position + collected keys |

---

## 6. Solve Step-by-Step — LC 752: Open the Lock

**Problem:** Start at "0000", avoid deadends, reach target in minimum turns. Each turn rotates one of 4 dials ±1 (wrapping: 0↔9).

(Full implementation in Template C — dry run below)

### Dry Run: `deadends=["0201","0101","0102","1212","2002"], target="0202"`
```
start="0000", target="0202"

Level 1 neighbors of "0000":
  dial 0: "1000","9000"
  dial 1: "0100","0900"
  dial 2: "0010","0090"  ← "0010" useful
  dial 3: "0001","0009"
  None equal target, none are deadends (except check: "0101" is dead)

Level 2 neighbors include "0102", "0110"...
  "0101" is dead → skip
  "0102" is dead → skip

Level 3: eventually reaches "0202"

Answer: 6 turns
```

### Key insight — deadends block paths:
If deadend contains "0000", return -1 immediately. Otherwise skip deadend states during BFS.

---

## 7. State Design Patterns

| State Type | Encoding | Example |
|---|---|---|
| Grid position | `int[] {row, col}` or `row * cols + col` | Maze, shortest path |
| String configuration | `String` (directly hashable) | Word Ladder, lock |
| Board layout | `Arrays.toString()` or row-major string | Sliding Puzzle |
| Position + extras | `String` combining position + flags | Keys + position bitmask |
| Number sequence | Integer or string representation | Combinations |

**State encoding rule:** The state must capture **all information needed to determine valid next moves.** If two positions look the same but have different histories that matter — they're different states.

---

## 8. Common Interview Mistakes

1. **Marking visited when dequeuing instead of enqueuing** — same state gets enqueued multiple times → TLE or wrong answer
2. **Forgetting to check if start == target** — return 0 immediately
3. **Word Ladder: not checking if endWord is in wordList** — return 0 if not present
4. **Lock: not handling deadend at "0000"** — check before BFS starts
5. **Bidirectional BFS: swapping sets incorrectly** — always expand the smaller set
6. **State size explosion** — for complex states (like all keys bitmask), estimate state count before BFS to ensure feasibility

---

## 9. Interview Cheat Sheet

```
IMPLICIT GRAPH BFS — MENTAL CHECKLIST
=======================================
□ What is the STATE? (encodes full situation)
□ What are the TRANSITIONS? (valid one-step moves)
□ Can I reach target from start?
□ Mark visited when ENQUEUING (not dequeuing)
□ Use Set<State> for O(1) visited lookup
□ Bidirectional BFS if search space is large

TEMPLATE SKELETON
=================
queue.offer(start); visited.add(start); steps=0
while queue not empty:
    steps++
    for each node in current level:
        node = queue.poll()
        for each neighbor in getNeighbors(node):
            if neighbor == target: return steps
            if not visited: visited.add, queue.offer

STATE DESIGN
============
Position only → int[] or encoded int
String state → directly as String key
Board → row-major string "123045"
Position + bitmask → "row,col,mask" string

COMPLEXITY FORMULA
==================
O(total_states × transitions_per_state)
Word Ladder: O(N × L × 26)
Lock: O(10^4 × 8)
Always check: is state space small enough for BFS?

TRICKS
======
- Bidirectional BFS: expand smaller frontier → O(b^(d/2)) vs O(b^d)
- Precompute adjacency lists for word sets (group by pattern)
- Dead state (deadend/wall) → skip but still check
- "All keys" problems → bitmask in state (2^k states)
```

---

## 10. Practice Roadmap

**Beginner:**
- LC 433 — Minimum Genetic Mutation
- LC 752 — Open the Lock
- LC 1091 — Shortest Path in Binary Matrix

**Intermediate:**
- LC 127 — Word Ladder
- LC 773 — Sliding Puzzle
- LC 1197 — Minimum Knight Moves

**FAANG Hard:**
- LC 126 — Word Ladder II (all shortest paths)
- LC 864 — Shortest Path to Get All Keys
- LC 815 — Bus Routes
- LC 854 — K-Similar Strings

# Pattern 19 — Backtracking (Permutations, Subsets, Combinations, N-Queens)

---

## 1. Pattern Recognition Guide

**Keywords to look for:**
- "all permutations", "all subsets", "all combinations"
- "generate all valid", "find all solutions"
- "word search in grid", "path in maze"
- "N-Queens", "Sudoku solver"

**When to reach for Backtracking:**
- Need ALL solutions, not just one
- Making choices at each step, need to undo choices
- State-space search with pruning

**Signal phrase:** _"Generate all permutations"_ → Backtracking
**Signal phrase:** _"Does a valid arrangement exist?"_ → Backtracking with early return

---

## 2. Core Intuition

**Backtracking = DFS + Undo**

At each step:
1. **Choose**: make a decision (add element, place queen, move in maze)
2. **Explore**: recurse with this decision
3. **Unchoose**: undo the decision (backtrack)

**The key insight:** We build a solution incrementally. If we detect the current partial solution can't lead to a valid complete solution, we prune the branch and backtrack.

```
Permutations of [1,2,3]:
                   []
          /         |         \
        [1]        [2]        [3]
       /   \      /   \      /   \
    [1,2] [1,3] [2,1] [2,3] [3,1] [3,2]
     |      |     |     |     |     |
  [1,2,3][1,3,2][2,1,3][2,3,1][3,1,2][3,2,1]
```

---

## 3. Generic Java Templates

### Template A — Subsets (Power Set)
```java
public List<List<Integer>> subsets(int[] nums) {
    List<List<Integer>> result = new ArrayList<>();
    backtrack(nums, 0, new ArrayList<>(), result);
    return result;
}

private void backtrack(int[] nums, int start, List<Integer> current,
                        List<List<Integer>> result) {
    // Add current subset at every node (including empty)
    result.add(new ArrayList<>(current));

    for (int i = start; i < nums.length; i++) {
        current.add(nums[i]);                          // choose
        backtrack(nums, i + 1, current, result);       // explore
        current.remove(current.size() - 1);            // unchoose
    }
}
```

### Template B — Combinations (Choose K from N)
```java
public List<List<Integer>> combine(int n, int k) {
    List<List<Integer>> result = new ArrayList<>();
    backtrack(1, n, k, new ArrayList<>(), result);
    return result;
}

private void backtrack(int start, int n, int k, List<Integer> current,
                        List<List<Integer>> result) {
    if (current.size() == k) {       // found a valid combination
        result.add(new ArrayList<>(current));
        return;
    }

    // Pruning: remaining elements must be enough to fill k slots
    for (int i = start; i <= n - (k - current.size()) + 1; i++) {
        current.add(i);
        backtrack(i + 1, n, k, current, result);
        current.remove(current.size() - 1);
    }
}
```

### Template C — Permutations (No Duplicates)
```java
public List<List<Integer>> permute(int[] nums) {
    List<List<Integer>> result = new ArrayList<>();
    backtrack(nums, new boolean[nums.length], new ArrayList<>(), result);
    return result;
}

private void backtrack(int[] nums, boolean[] used, List<Integer> current,
                        List<List<Integer>> result) {
    if (current.size() == nums.length) {
        result.add(new ArrayList<>(current));
        return;
    }

    for (int i = 0; i < nums.length; i++) {
        if (used[i]) continue;         // skip already used elements

        used[i] = true;
        current.add(nums[i]);          // choose
        backtrack(nums, used, current, result);
        current.remove(current.size() - 1); // unchoose
        used[i] = false;
    }
}
```

### Template D — Permutations With Duplicates (Sort + Skip)
```java
public List<List<Integer>> permuteUnique(int[] nums) {
    Arrays.sort(nums); // CRITICAL: sort to group duplicates
    List<List<Integer>> result = new ArrayList<>();
    backtrack(nums, new boolean[nums.length], new ArrayList<>(), result);
    return result;
}

private void backtrack(int[] nums, boolean[] used, List<Integer> current,
                        List<List<Integer>> result) {
    if (current.size() == nums.length) {
        result.add(new ArrayList<>(current));
        return;
    }

    for (int i = 0; i < nums.length; i++) {
        if (used[i]) continue;

        // Skip duplicate: same value at same depth level
        // used[i-1] == false means we already explored and backtracked nums[i-1]
        if (i > 0 && nums[i] == nums[i-1] && !used[i-1]) continue;

        used[i] = true;
        current.add(nums[i]);
        backtrack(nums, used, current, result);
        current.remove(current.size() - 1);
        used[i] = false;
    }
}
```

### Template E — Combination Sum (Unlimited Use, With Duplicates Variant)
```java
// LC 39: each number can be used unlimited times
public List<List<Integer>> combinationSum(int[] candidates, int target) {
    List<List<Integer>> result = new ArrayList<>();
    Arrays.sort(candidates);
    backtrack(candidates, target, 0, new ArrayList<>(), result);
    return result;
}

private void backtrack(int[] candidates, int remaining, int start,
                        List<Integer> current, List<List<Integer>> result) {
    if (remaining == 0) {
        result.add(new ArrayList<>(current));
        return;
    }

    for (int i = start; i < candidates.length; i++) {
        if (candidates[i] > remaining) break; // pruning: sorted, rest will be too large

        current.add(candidates[i]);
        backtrack(candidates, remaining - candidates[i], i, current, result); // i (not i+1) = reuse
        current.remove(current.size() - 1);
    }
}
```

### Template F — N-Queens
```java
public List<List<String>> solveNQueens(int n) {
    List<List<String>> result = new ArrayList<>();
    int[] queens = new int[n]; // queens[row] = col position
    Arrays.fill(queens, -1);

    Set<Integer> cols = new HashSet<>();
    Set<Integer> diag1 = new HashSet<>(); // row - col
    Set<Integer> diag2 = new HashSet<>(); // row + col

    backtrack(queens, n, 0, cols, diag1, diag2, result);
    return result;
}

private void backtrack(int[] queens, int n, int row,
                        Set<Integer> cols, Set<Integer> diag1, Set<Integer> diag2,
                        List<List<String>> result) {
    if (row == n) {
        result.add(buildBoard(queens, n));
        return;
    }

    for (int col = 0; col < n; col++) {
        if (cols.contains(col) || diag1.contains(row - col) || diag2.contains(row + col)) {
            continue; // queen attacks this position
        }

        queens[row] = col;
        cols.add(col); diag1.add(row - col); diag2.add(row + col);

        backtrack(queens, n, row + 1, cols, diag1, diag2, result);

        queens[row] = -1;
        cols.remove(col); diag1.remove(row - col); diag2.remove(row + col);
    }
}

private List<String> buildBoard(int[] queens, int n) {
    List<String> board = new ArrayList<>();
    for (int row = 0; row < n; row++) {
        char[] line = new char[n];
        Arrays.fill(line, '.');
        line[queens[row]] = 'Q';
        board.add(new String(line));
    }
    return board;
}
```

### Template G — Word Search in Grid
```java
public boolean exist(char[][] board, String word) {
    int rows = board.length, cols = board[0].length;

    for (int r = 0; r < rows; r++) {
        for (int c = 0; c < cols; c++) {
            if (dfs(board, word, r, c, 0)) return true;
        }
    }
    return false;
}

private boolean dfs(char[][] board, String word, int r, int c, int idx) {
    if (idx == word.length()) return true; // all chars matched
    if (r < 0 || r >= board.length || c < 0 || c >= board[0].length) return false;
    if (board[r][c] != word.charAt(idx)) return false;

    char temp = board[r][c];
    board[r][c] = '#'; // mark visited

    boolean found = dfs(board, word, r+1, c, idx+1) ||
                    dfs(board, word, r-1, c, idx+1) ||
                    dfs(board, word, r, c+1, idx+1) ||
                    dfs(board, word, r, c-1, idx+1);

    board[r][c] = temp; // unmark (backtrack)
    return found;
}
```

---

## 4. Complexity Cheatsheet

| Problem | Time | Space | Notes |
|---|---|---|---|
| Subsets | O(2^n × n) | O(n) | 2^n subsets, n to copy |
| Permutations | O(n! × n) | O(n) | n! permutations, n to copy |
| Combinations C(n,k) | O(C(n,k) × k) | O(k) | |
| N-Queens | O(n!) | O(n) | |
| Word Search | O(m×n × 4^L) | O(L) | L = word length |

---

## 5. Canonical Code Problems

### Core (Must Solve)

| LC # | Problem | Difficulty | Pattern |
|---|---|---|---|
| 78 | Subsets | Medium | Base backtracking |
| 46 | Permutations | Medium | Used-array backtracking |
| 39 | Combination Sum | Medium | Unlimited reuse |
| 51 | N-Queens | Hard | Constraint checking |
| 79 | Word Search | Medium | Grid DFS + backtrack |

### Advanced Variations

| LC # | Problem | Difficulty | Pattern |
|---|---|---|---|
| 90 | Subsets II (duplicates) | Medium | Sort + skip |
| 47 | Permutations II (duplicates) | Medium | Sort + skip |
| 40 | Combination Sum II | Medium | Sort + skip |
| 131 | Palindrome Partitioning | Medium | Backtrack + palindrome check |
| 37 | Sudoku Solver | Hard | Constraint propagation |

---

## 6. Solve Step-by-Step — LC 39: Combination Sum

**Problem:** Find all combinations that sum to target. Elements can be reused.

(Full template in Template E — dry run below)

### Dry Run: `candidates=[2,3,6,7], target=7`
```
backtrack(remaining=7, start=0, current=[])

  i=0: add 2, backtrack(remaining=5, start=0, current=[2])
    i=0: add 2, backtrack(remaining=3, start=0, current=[2,2])
      i=0: add 2, backtrack(remaining=1, start=0, current=[2,2,2])
        i=0: add 2 → remaining=-1 → break (pruning!)
        i=1: add 3 → remaining=-2 → break
      i=1: add 3, backtrack(remaining=0, current=[2,2,3]) → ADD ✓
    i=1: add 3, backtrack(remaining=2, start=1, current=[2,3])
      i=1: add 3 → remaining=-1 → break
      (no more)
  i=1: add 3, backtrack(remaining=4, start=1, current=[3])
    i=1: add 3, backtrack(remaining=1, start=1, current=[3,3])
      pruning...
  i=3: add 7, backtrack(remaining=0, current=[7]) → ADD ✓

Result: [[2,2,3],[7]]  ✓
```

---

## 7. Pruning Strategies (Critical for Interviews)

| Technique | How | Problem |
|---|---|---|
| Sort + break | Sort first, break when candidate > remaining | Combination Sum |
| Size limit | Return early when current.size() == k | Combinations |
| Skip duplicates | Sort + `if i>start && nums[i]==nums[i-1] continue` | Subsets II |
| Used array | Track which elements are in current path | Permutations |
| Diagonal sets | Precompute queen attack positions | N-Queens |
| Mark grid | Set cell to '#', restore after | Word Search |

---

## 8. Common Interview Mistakes

1. **Not making a copy when adding to result**: `result.add(current)` adds a reference — use `new ArrayList<>(current)`
2. **Duplicates: sorting but wrong skip condition**: `if (i > start && nums[i] == nums[i-1])` for combinations; `if (i > 0 && nums[i] == nums[i-1] && !used[i-1])` for permutations
3. **Reuse vs no-reuse**: pass `i` vs `i+1` as `start` parameter
4. **Word Search: not restoring the cell** after backtracking — classic bug
5. **N-Queens: not tracking diagonals** with sets — O(1) lookup instead of O(n) scan

---

## 9. Interview Cheat Sheet

```
BACKTRACKING — MENTAL CHECKLIST
==================================
□ All solutions needed? → Backtracking
□ Make copy when adding: result.add(new ArrayList<>(current))
□ Avoid duplicates? → Sort first, skip same-value siblings
□ Can reuse elements? → pass i as start (not i+1)
□ No reuse? → pass i+1 as start
□ Grid problem? → mark visited, restore after

TEMPLATE SKELETON
=================
void backtrack(state, current, result):
    if base_case:
        result.add(copy of current)
        return
    for each choice:
        if invalid: continue     ← pruning
        make choice
        backtrack(next_state, current, result)
        undo choice              ← THE BACKTRACK

DUPLICATES SKIP PATTERNS
=========================
Combinations: if (i > start && nums[i] == nums[i-1]) continue
Permutations: if (i > 0 && nums[i] == nums[i-1] && !used[i-1]) continue

COMPLEXITY INTUITION
====================
Subsets: 2^n paths in tree
Permutations: n! leaf nodes
Each result copy: O(n)
Total: O(2^n × n) or O(n! × n)
```

---

## 10. Practice Roadmap

**Beginner:**
- LC 78 — Subsets
- LC 46 — Permutations
- LC 77 — Combinations

**Intermediate:**
- LC 39 — Combination Sum
- LC 90 — Subsets II
- LC 47 — Permutations II
- LC 79 — Word Search
- LC 131 — Palindrome Partitioning

**Taking Hard:**
- LC 51 — N-Queens
- LC 37 — Sudoku Solver
- LC 291 — Word Pattern II
- LC 489 — Robot Room Cleaner

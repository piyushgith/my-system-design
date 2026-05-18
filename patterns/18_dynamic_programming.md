# Pattern 18 — Dynamic Programming (Complete Guide)

---

## 1. Pattern Recognition Guide

**Keywords to look for:**
- "maximum/minimum" with choices
- "number of ways", "count paths"
- "longest subsequence/substring"
- "can you achieve X", "is it possible"
- "overlapping subproblems" — same calculation needed multiple times

**When NOT to use DP:**
- Greedy works (no "choices" to make — always one obvious next step)
- The problem has no overlapping subproblems (divide and conquer)

**The two DP questions:**
1. What is my **state**? (What info do I need to define a subproblem?)
2. What is my **recurrence**? (How do I compute state from smaller states?)

---

## 2. Core Intuition

### Memoization (Top-Down) vs Tabulation (Bottom-Up)

**Memoization**: Write the recursive solution, cache results.
- Easier to think about
- Only computes needed states
- Recursion overhead; stack overflow risk

**Tabulation**: Fill a table iteratively from base cases to answer.
- Faster in practice (no recursion overhead)
- Must compute all states (some may be unnecessary)
- No stack overflow risk

---

## 3. DP Patterns and Templates

### Pattern 1 — Fibonacci / Linear DP

```java
// Climbing Stairs: n steps, climb 1 or 2 at a time — how many ways?
public int climbStairs(int n) {
    if (n <= 2) return n;
    int prev2 = 1, prev1 = 2;
    for (int i = 3; i <= n; i++) {
        int curr = prev1 + prev2;
        prev2 = prev1;
        prev1 = curr;
    }
    return prev1; // O(1) space, O(n) time
}
```

### Pattern 2 — 0/1 Knapsack

**State**: `dp[i][w]` = max value using first i items with capacity w
**Recurrence**:
- Don't take item i: `dp[i-1][w]`
- Take item i (if w >= weight[i]): `dp[i-1][w-weight[i]] + value[i]`
- `dp[i][w] = max(above two)`

```java
public int knapsack(int[] weights, int[] values, int capacity) {
    int n = weights.length;
    int[] dp = new int[capacity + 1]; // space-optimized (1D)

    for (int i = 0; i < n; i++) {
        // Traverse right-to-left to avoid using item i twice
        for (int w = capacity; w >= weights[i]; w--) {
            dp[w] = Math.max(dp[w], dp[w - weights[i]] + values[i]);
        }
    }

    return dp[capacity];
}
```

### Pattern 3 — Unbounded Knapsack (Coin Change)

**State**: `dp[amount]` = min coins to make amount
**Recurrence**: for each coin c: `dp[amount] = min(dp[amount], dp[amount-c] + 1)`

```java
public int coinChange(int[] coins, int amount) {
    int[] dp = new int[amount + 1];
    Arrays.fill(dp, amount + 1); // sentinel "infinity"
    dp[0] = 0;

    for (int a = 1; a <= amount; a++) {
        for (int coin : coins) {
            if (coin <= a) {
                dp[a] = Math.min(dp[a], dp[a - coin] + 1);
            }
        }
    }

    return dp[amount] > amount ? -1 : dp[amount];
}
```

### Pattern 4 — Longest Common Subsequence (LCS)

**State**: `dp[i][j]` = LCS length of s1[0..i-1] and s2[0..j-1]
**Recurrence**:
- If `s1[i-1] == s2[j-1]`: `dp[i][j] = dp[i-1][j-1] + 1`
- Else: `dp[i][j] = max(dp[i-1][j], dp[i][j-1])`

```java
public int longestCommonSubsequence(String s1, String s2) {
    int m = s1.length(), n = s2.length();
    int[][] dp = new int[m + 1][n + 1];

    for (int i = 1; i <= m; i++) {
        for (int j = 1; j <= n; j++) {
            if (s1.charAt(i-1) == s2.charAt(j-1)) {
                dp[i][j] = dp[i-1][j-1] + 1;
            } else {
                dp[i][j] = Math.max(dp[i-1][j], dp[i][j-1]);
            }
        }
    }

    return dp[m][n];
}
```

### Pattern 5 — Longest Increasing Subsequence (LIS)

**State**: `dp[i]` = length of LIS ending at index i
**Recurrence**: for j < i: if `nums[j] < nums[i]`: `dp[i] = max(dp[i], dp[j] + 1)`

```java
// O(n²) approach
public int lengthOfLIS(int[] nums) {
    int n = nums.length;
    int[] dp = new int[n];
    Arrays.fill(dp, 1); // each element is a subsequence of length 1
    int maxLen = 1;

    for (int i = 1; i < n; i++) {
        for (int j = 0; j < i; j++) {
            if (nums[j] < nums[i]) {
                dp[i] = Math.max(dp[i], dp[j] + 1);
            }
        }
        maxLen = Math.max(maxLen, dp[i]);
    }
    return maxLen;
}

// O(n log n) with patience sorting + binary search
public int lengthOfLISOptimal(int[] nums) {
    List<Integer> tails = new ArrayList<>();
    for (int num : nums) {
        int pos = Collections.binarySearch(tails, num);
        if (pos < 0) pos = -(pos + 1); // insertion point
        if (pos == tails.size()) tails.add(num);
        else tails.set(pos, num);
    }
    return tails.size();
}
```

### Pattern 6 — Palindrome DP

```java
// Longest Palindromic Subsequence
public int longestPalindromeSubseq(String s) {
    int n = s.length();
    int[][] dp = new int[n][n];

    // Base: single chars are palindromes of length 1
    for (int i = 0; i < n; i++) dp[i][i] = 1;

    // Fill by increasing length
    for (int len = 2; len <= n; len++) {
        for (int i = 0; i <= n - len; i++) {
            int j = i + len - 1;
            if (s.charAt(i) == s.charAt(j)) {
                dp[i][j] = dp[i+1][j-1] + 2;
            } else {
                dp[i][j] = Math.max(dp[i+1][j], dp[i][j-1]);
            }
        }
    }

    return dp[0][n-1];
}
```

### Pattern 7 — 2D Grid DP

```java
// Unique paths in m×n grid (only right/down moves)
public int uniquePaths(int m, int n) {
    int[] dp = new int[n];
    Arrays.fill(dp, 1); // first row: all 1s

    for (int i = 1; i < m; i++) {
        for (int j = 1; j < n; j++) {
            dp[j] += dp[j-1]; // dp[j] = paths from above + paths from left
        }
    }

    return dp[n-1];
}
```

---

## 4. Complexity Cheatsheet

| Pattern | Time | Space | Optimized Space |
|---|---|---|---|
| Linear (Fibonacci) | O(n) | O(n) | O(1) — two vars |
| 0/1 Knapsack | O(n×W) | O(n×W) | O(W) — 1D array |
| Unbounded Knapsack | O(n×W) | O(W) | Already 1D |
| LCS | O(m×n) | O(m×n) | O(min(m,n)) |
| LIS (O(n²)) | O(n²) | O(n) | — |
| LIS (O(n log n)) | O(n log n) | O(n) | — |
| Palindrome DP | O(n²) | O(n²) | O(n) with rolling |
| Grid DP | O(m×n) | O(m×n) | O(n) — 1D rolling |

---

## 5. Canonical Code Problems

### Core (Must Solve)

| LC # | Problem | Difficulty | Pattern |
|---|---|---|---|
| 70 | Climbing Stairs | Easy | Linear |
| 322 | Coin Change | Medium | Unbounded Knapsack |
| 1143 | Longest Common Subsequence | Medium | LCS |
| 300 | Longest Increasing Subsequence | Medium | LIS |
| 416 | Partition Equal Subset Sum | Medium | 0/1 Knapsack |

### Advanced Variations

| LC # | Problem | Difficulty | Pattern |
|---|---|---|---|
| 72 | Edit Distance | Medium | 2D DP (LCS variant) |
| 516 | Longest Palindromic Subsequence | Medium | Palindrome DP |
| 312 | Burst Balloons | Hard | Interval DP |
| 1143 | Longest Common Subsequence | Medium | LCS |
| 188 | Best Time to Buy Sell Stock IV | Hard | State machine DP |

---

## 6. Solve Step-by-Step — LC 322: Coin Change

**Problem:** Minimum coins to make amount. Unlimited coins of each denomination.

### Brute Force — Exponential
Try all combinations of coins recursively.

### Better — Memoized DFS
Cache `memo[amount]` = min coins for each sub-amount.

### Optimal — Tabulation (Bottom-Up)
```java
public int coinChange(int[] coins, int amount) {
    int[] dp = new int[amount + 1];
    Arrays.fill(dp, amount + 1); // anything > amount = impossible sentinel
    dp[0] = 0; // base case: 0 coins to make amount 0

    for (int a = 1; a <= amount; a++) {
        for (int coin : coins) {
            if (coin <= a) {
                dp[a] = Math.min(dp[a], dp[a - coin] + 1);
            }
        }
    }

    return dp[amount] > amount ? -1 : dp[amount];
}
```

### Dry Run: `coins=[1,2,5], amount=11`
```
dp[0]=0
dp[1]=min(dp[0]+1)=1         (use coin 1)
dp[2]=min(dp[1]+1,dp[0]+1)=1 (use coin 2)
dp[3]=min(dp[2]+1,dp[1]+1)=2 (use 1+2)
...
dp[5]=min(dp[4]+1,dp[3]+1,dp[0]+1)=1  (use coin 5)
dp[6]=min(dp[5]+1,dp[4]+1,dp[1]+1)=2
...
dp[11]=min(dp[10]+1,dp[9]+1,dp[6]+1)=3  (5+5+1)

Answer: 3  ✓
```

---

## 7. DP Problem-Solving Framework

```
Step 1: Define the STATE clearly
  "dp[i] represents..."
  "dp[i][j] represents..."

Step 2: Write the RECURRENCE
  "dp[i] depends on dp[i-1] because..."

Step 3: Identify BASE CASES
  "dp[0] = ..., dp[1] = ..."

Step 4: Determine ITERATION ORDER
  "We need dp[i-1] before dp[i], so iterate left to right"

Step 5: EXTRACT ANSWER
  "The answer is dp[n] / dp[n-1] / max(dp)"

Step 6: OPTIMIZE SPACE if possible
  "We only need previous row → use 1D rolling array"
```

---

## 8. Common Interview Mistakes

1. **Wrong state definition** — most DP bugs come from an imprecise state
2. **Forgetting base cases** — off-by-one errors from uninitialized dp[0]
3. **0/1 knapsack: iterating left-to-right** — causes double-counting (use item multiple times); must go right-to-left
4. **Unbounded knapsack: iterating right-to-left** — prevents reusing coins; must go left-to-right
5. **LCS: using i, j without +1 offset** — `dp[i][j]` = LCS of first i chars, NOT index i
6. **Palindrome: wrong subproblem order** — must fill by length, not by i or j

---

## 9. Interview Cheat Sheet

```
DP — MENTAL CHECKLIST
=======================
□ "Maximum/minimum with choices" → DP (not greedy)
□ "Number of ways" → DP (count paths)
□ "Longest subsequence" → LCS or LIS template
□ "Can you make sum X" → Knapsack template
□ "Palindrome" → Expand from center or dp[i][j]

STATE DEFINITION TEMPLATES
============================
dp[i]     → best answer using first i elements
dp[i][j]  → best answer for substring s[i..j]
dp[i][j]  → best answer for first i items with constraint j
dp[i][j]  → LCS/edit distance of s1[0..i] and s2[0..j]

KNAPSACK DIRECTION
==================
0/1 Knapsack:    inner loop RIGHT TO LEFT (no reuse)
Unbounded:       inner loop LEFT TO RIGHT (allow reuse)

COMMON RECURRENCES
==================
LCS:  dp[i][j] = dp[i-1][j-1]+1 if match, else max(dp[i-1][j], dp[i][j-1])
LIS:  dp[i] = max(dp[j]+1) for all j < i where nums[j] < nums[i]
Edit: dp[i][j] = dp[i-1][j-1] if match, else 1+min(dp[i-1][j],dp[i][j-1],dp[i-1][j-1])
Coin: dp[a] = min(dp[a-coin]+1) for all valid coins
```

---

## 10. Practice Roadmap

**Beginner:**
- LC 70 — Climbing Stairs
- LC 509 — Fibonacci Number
- LC 198 — House Robber
- LC 746 — Min Cost Climbing Stairs

**Intermediate:**
- LC 322 — Coin Change
- LC 300 — LIS
- LC 1143 — LCS
- LC 416 — Partition Equal Subset Sum
- LC 62 — Unique Paths
- LC 72 — Edit Distance

**Taking Hard:**
- LC 312 — Burst Balloons (Interval DP)
- LC 188 — Best Time to Buy/Sell Stock IV
- LC 1335 — Minimum Difficulty of a Job Schedule
- LC 32 — Longest Valid Parentheses

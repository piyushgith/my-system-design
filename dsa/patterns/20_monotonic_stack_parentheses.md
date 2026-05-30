# Pattern 20 — Monotonic Stack + Matching Parentheses

---

## 1. Pattern Recognition Guide

**Monotonic Stack keywords:**
- "next greater element", "next smaller element"
- "previous greater/smaller"
- "largest rectangle in histogram"
- "daily temperatures", "stock span"
- "trapping rain water" (stack approach)

**Matching Parentheses keywords:**
- "valid parentheses", "balanced brackets"
- "minimum add to make valid", "score of parentheses"
- "longest valid parentheses"
- "remove invalid parentheses"

**Signal phrase:** _"For each element, find the next greater element"_ → Monotonic Stack
**Signal phrase:** _"Valid/balanced brackets"_ → Stack with push/pop

---

## 2. Core Intuition

### Monotonic Stack

A stack that maintains elements in either increasing or decreasing order.

**When an element enters, it pops all elements that violate the monotonic property.**
Those popped elements have found their "answer" — the current element is their "next greater" (or smaller).

```
Daily Temperatures: [73, 74, 75, 71, 69, 72, 76, 73]
Find: days until warmer temperature

Stack stores INDICES (not values) — we need indices to compute days

i=0: stack=[], push 0    → stack=[0]
i=1: 74>73(idx 0) → pop 0, ans[0]=1-0=1  → push 1  → stack=[1]
i=2: 75>74(idx 1) → pop 1, ans[1]=2-1=1  → push 2  → stack=[2]
i=3: 71<75 → push 3  → stack=[2,3]
i=4: 69<71 → push 4  → stack=[2,3,4]
i=5: 72>69(4)→pop,ans[4]=1; 72>71(3)→pop,ans[3]=2; 72<75→push5 → stack=[2,5]
i=6: 76>72(5)→pop,ans[5]=1; 76>75(2)→pop,ans[2]=4; push6 → stack=[6]
i=7: 73<76 → push 7  → stack=[6,7]

No next warmer for indices 6,7 → ans[6]=ans[7]=0

Result: [1,1,4,2,1,1,0,0]  ✓
```

### Matching Parentheses

Push open brackets. When a close bracket is seen, check if top of stack matches.

---

## 3. Generic Java Templates

### Template A — Next Greater Element (Monotonic Decreasing Stack)
```java
public int[] nextGreaterElement(int[] nums) {
    int n = nums.length;
    int[] result = new int[n];
    Arrays.fill(result, -1); // default: no greater element

    Deque<Integer> stack = new ArrayDeque<>(); // stores INDICES

    for (int i = 0; i < n; i++) {
        // Pop all indices whose value is less than current
        while (!stack.isEmpty() && nums[stack.peek()] < nums[i]) {
            int idx = stack.pop();
            result[idx] = nums[i]; // current element is idx's next greater
        }
        stack.push(i);
    }

    return result;
}
```

### Template B — Daily Temperatures (Next Greater, Return Distance)
```java
public int[] dailyTemperatures(int[] temps) {
    int n = temps.length;
    int[] result = new int[n];
    Deque<Integer> stack = new ArrayDeque<>();

    for (int i = 0; i < n; i++) {
        while (!stack.isEmpty() && temps[stack.peek()] < temps[i]) {
            int idx = stack.pop();
            result[idx] = i - idx; // days until warmer
        }
        stack.push(i);
    }

    return result;
}
```

### Template C — Previous Smaller Element
```java
// For each element, find the nearest smaller element to its LEFT
public int[] previousSmaller(int[] nums) {
    int n = nums.length;
    int[] result = new int[n];
    Deque<Integer> stack = new ArrayDeque<>(); // monotonic increasing

    for (int i = 0; i < n; i++) {
        // Maintain increasing stack — pop elements >= current
        while (!stack.isEmpty() && stack.peek() >= nums[i]) {
            stack.pop();
        }
        result[i] = stack.isEmpty() ? -1 : stack.peek(); // top = nearest smaller
        stack.push(nums[i]);
    }

    return result;
}
```

### Template D — Largest Rectangle in Histogram
```java
// For each bar, find the area of the largest rectangle using that bar's height
public int largestRectangleArea(int[] heights) {
    int n = heights.length;
    int maxArea = 0;

    // Monotonic increasing stack (stores indices)
    // When we pop, we found the "right boundary" (current) and "left boundary" (new top)
    Deque<Integer> stack = new ArrayDeque<>();

    for (int i = 0; i <= n; i++) {
        int currHeight = (i == n) ? 0 : heights[i]; // sentinel 0 at end to flush stack

        while (!stack.isEmpty() && heights[stack.peek()] > currHeight) {
            int height = heights[stack.pop()];
            int width = stack.isEmpty() ? i : i - stack.peek() - 1;
            maxArea = Math.max(maxArea, height * width);
        }

        stack.push(i);
    }

    return maxArea;
}
```

### Template E — Valid Parentheses (Matching Brackets)
```java
public boolean isValid(String s) {
    Deque<Character> stack = new ArrayDeque<>();

    for (char c : s.toCharArray()) {
        if (c == '(' || c == '[' || c == '{') {
            stack.push(c);
        } else {
            if (stack.isEmpty()) return false;
            char top = stack.pop();
            if (c == ')' && top != '(') return false;
            if (c == ']' && top != '[') return false;
            if (c == '}' && top != '{') return false;
        }
    }

    return stack.isEmpty(); // all matched
}
```

### Template F — Longest Valid Parentheses
```java
public int longestValidParentheses(String s) {
    Deque<Integer> stack = new ArrayDeque<>();
    stack.push(-1); // sentinel base index

    int maxLen = 0;

    for (int i = 0; i < s.length(); i++) {
        if (s.charAt(i) == '(') {
            stack.push(i);
        } else {
            stack.pop(); // match with top '('
            if (stack.isEmpty()) {
                stack.push(i); // new base: unmatched ')'
            } else {
                maxLen = Math.max(maxLen, i - stack.peek());
            }
        }
    }

    return maxLen;
}
```

### Template G — Next Greater in Circular Array (LC 503)
```java
public int[] nextGreaterElements(int[] nums) {
    int n = nums.length;
    int[] result = new int[n];
    Arrays.fill(result, -1);
    Deque<Integer> stack = new ArrayDeque<>();

    // Iterate twice to simulate circular behavior
    for (int i = 0; i < 2 * n; i++) {
        while (!stack.isEmpty() && nums[stack.peek()] < nums[i % n]) {
            result[stack.pop()] = nums[i % n];
        }
        if (i < n) stack.push(i); // only push in first pass
    }

    return result;
}
```

---

## 4. Complexity Cheatsheet

| Operation | Time | Space | Why O(n)? |
|---|---|---|---|
| Next Greater Element | O(n) | O(n) | Each element pushed/popped once |
| Largest Rectangle | O(n) | O(n) | Each bar pushed/popped once |
| Valid Parentheses | O(n) | O(n) | Single pass |
| Longest Valid Parens | O(n) | O(n) | Single pass |

Despite the nested while loop, total push+pop operations = O(n) — amortized O(1) per element.

---

## 5. Canonical Code Problems

### Core (Must Solve)

| LC # | Problem | Difficulty | Why Important |
|---|---|---|---|
| 739 | Daily Temperatures | Medium | Classic monotonic stack |
| 496 | Next Greater Element I | Easy | Basic NGE |
| 84 | Largest Rectangle in Histogram | Hard | Width calculation with stack |
| 20 | Valid Parentheses | Easy | Classic bracket matching |
| 32 | Longest Valid Parentheses | Hard | Stack with index tracking |

### Advanced Variations

| LC # | Problem | Difficulty | Why Important |
|---|---|---|---|
| 503 | Next Greater Element II (Circular) | Medium | Double iteration trick |
| 42 | Trapping Rain Water | Hard | Stack or two-pointer |
| 85 | Maximal Rectangle | Hard | Row-by-row histogram |
| 901 | Online Stock Span | Medium | Monotonic stack with counts |
| 1019 | Next Greater Node in Linked List | Medium | Stack on linked list |

---

## 6. Solve Step-by-Step — LC 84: Largest Rectangle in Histogram

**Problem:** Find the largest rectangular area that can be formed in a histogram.

### Key Insight
For each bar as the "shortest" bar in a rectangle:
- Extend left until a shorter bar is found
- Extend right until a shorter bar is found
- Area = height × (right boundary - left boundary - 1)

The monotonic stack gives us left and right boundaries in O(n) total.

```java
public int largestRectangleArea(int[] heights) {
    Deque<Integer> stack = new ArrayDeque<>();
    int maxArea = 0;

    for (int i = 0; i <= heights.length; i++) {
        int h = (i == heights.length) ? 0 : heights[i];

        while (!stack.isEmpty() && heights[stack.peek()] > h) {
            int height = heights[stack.pop()];
            // Left boundary: new stack top (exclusive)
            // Right boundary: i (exclusive)
            int width = stack.isEmpty() ? i : i - stack.peek() - 1;
            maxArea = Math.max(maxArea, height * width);
        }
        stack.push(i);
    }

    return maxArea;
}
```

### Dry Run: `[2,1,5,6,2,3]`
```
i=0: h=2, stack=[], push 0 → stack=[0]
i=1: h=1, 2>1 → pop 0, height=2, width=1(stack empty), area=2; push 1 → stack=[1]
i=2: h=5, 1<5 → push 2 → stack=[1,2]
i=3: h=6, 5<6 → push 3 → stack=[1,2,3]
i=4: h=2, 6>2 → pop 3, height=6, width=4-2-1=1, area=6
              5>2 → pop 2, height=5, width=4-1-1=2, area=10 ← max
              1<2 → stop; push 4 → stack=[1,4]
i=5: h=3, 2<3 → push 5 → stack=[1,4,5]
i=6: h=0(sentinel), 3>0 → pop 5, height=3, width=6-4-1=1, area=3
                   2>0 → pop 4, height=2, width=6-1-1=4, area=8
                   1>0 → pop 1, height=1, width=6(stack empty), area=6

maxArea = 10  ✓
```

---

## 7. Pattern Variations

| Problem | Stack Type | What Stack Stores |
|---|---|---|
| Next greater (right) | Monotonic decreasing | Indices waiting for NGE |
| Previous smaller (left) | Monotonic increasing | Values seen so far |
| Largest rectangle | Monotonic increasing | Indices, pop when smaller seen |
| Trapping rain water | Monotonic decreasing | Indices, compute water when pop |
| Valid brackets | N/A (just pairs) | Open brackets |
| Longest valid parens | N/A | Indices of unmatched chars |

---

## 8. Common Interview Mistakes

1. **Storing values instead of indices** — you need indices to compute widths/distances
2. **Largest rectangle: forgetting sentinel `0`** — stack never fully flushes without it
3. **Largest rectangle width formula** — `i - stack.peek() - 1`, not `i - stack.peek()`
4. **Valid parentheses: not checking `stack.isEmpty()`** before popping — NPE
5. **Circular NGE: pushing index in second pass** — only push `if (i < n)`
6. **Trapping rain water with stack: water = (min(leftMax, rightMax) - current) × width** — complex; two-pointer approach is cleaner

---

## 9. Interview Cheat Sheet

```
MONOTONIC STACK — MENTAL CHECKLIST
=====================================
□ "Next greater/smaller" for each element? → Monotonic stack
□ Decreasing stack → finds NEXT GREATER (pop when bigger arrives)
□ Increasing stack → finds NEXT SMALLER (pop when smaller arrives)
□ Store INDICES (not values) when distance/width matters
□ Sentinel value at end to flush remaining stack

DIRECTION GUIDE
===============
Next Greater Element:  iterate LEFT→RIGHT, pop when nums[i] > nums[stack.top]
Previous Smaller:      iterate LEFT→RIGHT, maintain increasing stack

MONOTONIC STACK TYPE
====================
Decreasing: each new element pops smaller ones → they found their NGE
Increasing: each new element pops larger ones → they found their previous smaller

PARENTHESES TRICKS
==================
- Valid: push open, pop+match on close, check empty at end
- Longest valid: stack stores indices; sentinel -1; update on each ')' match
- Score: push 0 for '(', on ')': pop + max(2×top, 1) or just count depth

LARGEST RECTANGLE
=================
pop when smaller height arrives → current idx = right boundary
new stack top = left boundary
width = i - stack.peek() - 1  (or i if stack empty)
```

---

## 10. Practice Roadmap

**Beginner:**
- LC 20 — Valid Parentheses
- LC 496 — Next Greater Element I
- LC 739 — Daily Temperatures

**Intermediate:**
- LC 503 — Next Greater Element II (Circular)
- LC 901 — Online Stock Span
- LC 32 — Longest Valid Parentheses
- LC 1019 — Next Greater Node in Linked List

**Taking Hard:**
- LC 84 — Largest Rectangle in Histogram
- LC 85 — Maximal Rectangle
- LC 42 — Trapping Rain Water
- LC 907 — Sum of Subarray Minimums

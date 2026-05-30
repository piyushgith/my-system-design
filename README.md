# my-system-design

Interview prep + system design learning repo.

## Structure

```text
my-system-design/
│
├── design-md-files/          ← 25 production-grade system designs (PROTECTED)
│   ├── 01-url-shortener/
│   ├── 02-pastebin/
│   ├── ...
│   └── 25-loan-origination-servicing/
│
├── dsa/                      ← DSA patterns + solved problems
│   ├── patterns/             ← 25 pattern templates (two-pointers → backtracking)
│   ├── problems/             ← topic-wise leetcode solutions
│   └── _index.md
│
├── interview-prep/           ← revision material for interviews
│   ├── system-design-quick-ref/  ← HLD cheatsheets, estimation formulas
│   ├── java-backend/             ← Java/Spring/JPA/Kafka Q&A
│   ├── behavioral/               ← STAR stories
│   └── _index.md
│
├── resources/                ← static reference material
│   ├── books/                ← PDFs (DSA, Java, System Design)
│   ├── diagrams/             ← architecture diagram images
│   ├── case-studies/         ← real-world system breakdowns
│   └── _index.md
│
├── archive/                  ← old/abandoned apps, not under active dev
│   ├── old-apps/
│   └── _index.md
│
├── CLAUDE.md
└── README.md
```

## Quick navigation

| Goal | Go to |
| --- | --- |
| Design a system | `design-md-files/NN-{topic}/docs/` |
| Revise DSA pattern | `dsa/patterns/NN-{pattern}.md` |
| Pre-interview HLD refresh | `interview-prep/system-design-quick-ref/00-revision-checklist.md` |
| Estimation math | `interview-prep/system-design-quick-ref/01-estimation-formulas.md` |
| Java interview Q&A | `interview-prep/java-backend/` |
| Reference book | `resources/books/` |
| Architecture diagram | `resources/diagrams/` |

## Naming conventions

| Type | Convention | Example |
| --- | --- | --- |
| Design topic | `NN-{kebab-name}/` | `01-url-shortener/` |
| DSA pattern | `NN_{snake_name}.md` | `17_binary_search.md` |
| Interview notes | `NN-{kebab-topic}.md` | `01-core-java-gotchas.md` |
| Problems | `{tier}-{problem-name}.md` | `200-two-sum.md` (100=easy, 200=med, 300=hard) |

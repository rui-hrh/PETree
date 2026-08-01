# PETree

**PETree** — **P**attern **E**valuation **Tree** — is a stream-based uncertain complex-event processing (CEP) engine. A query is compiled into a tree whose internal nodes are pattern operators (`SEQ`, `AND`, `NSEQ`) and whose leaves are event types; the engine then evaluates that tree incrementally over a stream of **interval-based probabilistic events**, returning matches with exact possible-world confidence values. PETree ships with static-bound filtering, deferred (matching-point triggered) evaluation, and multiple levels of dynamic pruning that together keep evaluation fast and memory-footprint small even for deeply nested patterns over long streams.

---

## Table of Contents

- [Overview](#overview)
- [Key Features](#key-features)
- [Pattern Language](#pattern-language)
- [Algorithm Overview](#algorithm-overview)
  - [1. Pattern Evaluation Tree (PETree) Structure](#1-pattern-evaluation-tree-petree-structure)
  - [2. Uncertain Event Model](#2-uncertain-event-model)
  - [3. Stream Processing with Deferred Matching](#3-stream-processing-with-deferred-matching)
  - [4. Static-Bound Filtering (Δ-min / Δ-max)](#4-static-bound-filtering-delta-min--delta-max)
  - [5. Dynamic Pruning](#5-dynamic-pruning)
  - [6. Confidence Computation (Possible-World Semantics)](#6-confidence-computation-possible-world-semantics)
- [Architecture](#architecture)
- [Getting Started](#getting-started)
  - [Prerequisites](#prerequisites)
  - [Build](#build)
  - [Quick Example](#quick-example)
  - [String-Pattern API](#string-pattern-api)
  - [Programmatic API](#programmatic-api)
  - [Streaming API](#streaming-api)
- [Ablation / Tuning Flags](#ablation--tuning-flags)
- [Performance Statistics](#performance-statistics)
- [Project Structure](#project-structure)
- [License](#license)

---

## Overview

Classic CEP systems assume every event carries a single, precise timestamp and evaluate patterns with an ad-hoc state machine. In real-world pipelines (IoT, sensor fusion, NLP, log parsing, financial feeds), however, event times are noisy, imputed, or explicitly reported as intervals with a probability mass function — and ad-hoc state machines no longer compose cleanly with probabilistic semantics.

PETree is designed for that world. Every query is compiled to a uniform **Pattern Evaluation Tree**: leaves correspond to event types, internal nodes correspond exactly to pattern operators (`SEQ`, `AND`, `NSEQ`), and evaluation proceeds by recursive partial-match composition with VLB/VUB bound propagation at every node. The tree-shaped IR is also what powers the engine's other optimisations — the static DAG used for Δ-min/Δ-max bounds and the topological order used for confidence enumeration are both derived directly from it.

| Feature | Description |
|---|---|
| **Uncertain events** | Each event is a `[lower, upper]` time-interval with either a uniform PMF or a custom per-timestamp probability array. |
| **Rich patterns** | Compose `SEQ`, `AND`, and `NSEQ` (negated-between) operators with arbitrarily deep nesting. |
| **Exact confidence** | Every match carries a probability computed under possible-world semantics (Kahn topological sort + window-aware enumeration with dynamic lower-bound tightening). |
| **Streaming** | Feed events one by one via `ingest()`; the engine auto-triggers matching when a reference event's time-window closes. |
| **Three-tiered pruning** | (i) static Δ-min / Δ-max bound filtering on every buffer, (ii) dynamic VLB/VUB tightening per partial match, (iii) early window-violation cut during confidence enumeration. |

Ablation switches are built in (`enableDeferred`, `enableStaticBounds`, `enableDynamicPruning`) so each optimisation can be toggled independently for experimentation.

---

## Key Features

- **Pattern Evaluation Tree IR**: every query compiles to a uniform tree (`SEQ`/`AND`/`NSEQ` operators → internal nodes; event types → leaves); one recursive evaluator drives everything from buffer fill to bound propagation.
- **Expressive pattern algebra**: `SEQ` (temporal order), `AND` (unordered conjunction with distinct-time semantics), `NSEQ(first, neg, third)` (negated between).
- **Probabilistic interval events**: uniform or custom PMF per event; `prob(t)` is the basic primitive.
- **Exact possible-world confidence** via topological enumeration with:
  - per-event lower/upper value bounds (VLB / VUB)
  - partial-order-based dynamic bound tightening
  - early window-violation pruning
  - negation constraints folded into a product term
- **Deferred stream processing** — a reference leaf's time-window triggers matching once it closes (matches are produced without scanning the whole stream).
- **Static-Bounds module** — off-line precomputation of Δ-min / Δ-max deltas from the pattern DAG (Floyd–Warshall reachability → pre / post / mid slot counts → per-event filtering).
- **Ablation-ready**: every major optimisation has a boolean flag for ablation studies.
- **Zero dependencies**: pure Java 8, no libraries required.
- **Compact API**: pattern-string parser (`Utils.parsePattern`) + programmatic tree construction + streaming `ingest()/flush()`.

---

## Pattern Language

Patterns are built from three operators and plain-type leaves. They can be constructed **programmatically** or parsed from a **string DSL**:

| Operator     | Syntax                        | Meaning                                                                                             |
|--------------|-------------------------------|-----------------------------------------------------------------------------------------------------|
| **SEQ**      | `SEQ(A, B, C, ...)`           | Strict temporal order on all pairs (a ≺ b for every a in A-subtree, b in B-subtree, …).             |
| **AND**      | `AND(A, B, C, ...)`           | Unordered conjunction; every matched event must receive a **distinct** timestamp within the window.  |
| **NSEQ**     | `NSEQ(first, neg, third)`     | `first ≺ third`, and **no** `neg` event exists strictly between them. All three must be leaf types. |
| **Leaf**     | `"A"`, `"Login"`, …           | Any bare identifier is a leaf event-type.                                                           |

Nesting is arbitrary, e.g.:

```
SEQ(AND(SEQ(A, B), C), NSEQ(D, X, E))
```

means "pattern = (A then B AND C occurring within the same time-window) followed by (D then E with **no** X in-between), all within `tw` timestamps".

> **Window semantics**: all timestamps assigned to events in a completed match must satisfy `max(t) − min(t) < tw`. The window size `tw` is a parameter of the engine, not embedded in the pattern string.

---

## Algorithm Overview

This section sketches how the engine works, at the level of the modules you actually see in `src/`.

### 1. Pattern Evaluation Tree (PETree) Structure

Every query compiles uniformly to a **Pattern Evaluation Tree** of `PETreeNode` subclasses — internal nodes = pattern operators, leaves = event types — so the same recursive evaluator and bound-propagation logic works for every pattern:

- **LeafNode** — one event-type; at matching time holds a filtered `buffer` of `EventEntry(lb, ub)` candidates (tightened by static bounds).
- **SeqNode** — iterates children left-to-right, merging partial matches, and adds cross-child `a ≺ b` order pairs.
- **AndNode** — forms the Cartesian product of its children; uses a Hall-style distinct-timestamp feasibility check before merging, then tightens all bounds with the window.
- **NseqNode** — joins the first and third leaves, detects whether the negation leaf is *forced* to appear in the gap (fast-reject), and records a `NegConstraint` for the confidence step.

Each node carries `slots()` and `leafTypes()` for static analysis, and two `evaluate(tw)` overloads — one with dynamic pruning on, one off.

### 2. Uncertain Event Model

An `Event` is a tuple `(id, type, lower, upper)` plus an optional PMF array. The primitive is:

```java
double prob(int t)  // P(event occurs at timestamp t)
```

Without an explicit PMF, probability distributes uniformly over `[lower, upper]`.

A completed match tracks, for every event, its **value lower/upper bound** (VLB/VUB) — these are *not* the same as `event.lower/upper`; they are progressively tightened by every pruning step along the way, so enumeration only ever visits a small rectangle of the timestamp space.

### 3. Stream Processing with Deferred Matching

`PETreeEngine.ingest(Event)` does four things:

1. Appends the event to the `rawBuffer` of every leaf whose `originalType` matches (indexed via `typeToLeafMap` so this is one map lookup, not a full leaf scan).
2. If the event is of the **reference leaf** type (the first non-negation leaf encountered during pre-order traversal of the pattern tree), wraps it in an `ActiveRef(matPoint = event.upper + tw − 1)` and pushes onto a priority queue ordered by `matPoint`.
3. Advances `currentTime` past `event.lower`.
4. While `activeRefs.peek().matPoint < currentTime` pops the ref and runs matching:

   ```
   for each leaf:
       leaf.buffer ← filter(leaf.rawBuffer, StaticBounds Δ-min/Δ-max around ref)
       if leaf is non-negation and buffer is empty → short-circuit this ref
   candidates ← root.evaluate(tw, enableDynamicPruning)
   for each candidate:
       conf = computeConfidence(pm)
       if conf > 0 → emit / count match
   ```

Finally, `flush()` must be called at stream end to drain any remaining `ActiveRef`s whose `matPoint` never crossed `currentTime`.

### 4. Static-Bound Filtering (Δ-min / Δ-max)

StaticBounds is computed once, in the engine constructor, from the pattern DAG:

1. The pattern's `buildEdges()` records every pair `(leaf_X, leaf_Y)` that must satisfy `X ≺ Y` due to an enclosing `SEQ` or `NSEQ`.
2. Floyd–Warshall closes this to a full reachability matrix.
3. For every ordered reachable pair `(x, y)` we compute `midSlots(x, y)` = number of leaves that must fit strictly between them in the partial order.
4. `preSlots(x)` = leaves reachable to `x`, `postSlots(x)` = leaves reachable from `x`.

Given a reference event `ref` at `(ref_lower, ref_upper)`, for any other vertex-leaf `v`:

```
deltaMin =  midSlots(ref, v) + 1             if ref ⇝ v
           -(tw−1) + postSlots(ref) + preSlots(v)   otherwise

deltaMax =  (tw−1) − preSlots(ref) − postSlots(v)   if ref ⇝ v or unordered
           −midSlots(v, ref) − 1              if v ⇝ ref
```

and the per-event filter is `[max(e.lower, ref.lower + deltaMin), min(e.upper, ref.upper + deltaMax)]`. If that interval is empty, `e` is dropped from the buffer *before* tree evaluation even starts. This is a huge win for deep patterns.

### 5. Dynamic Pruning

Tree evaluation itself does three kinds of pruning on partial matches:

- **SEQ cross-pruning** (`pruneSeqAcross`) — repeated closure of `u_a ≤ u_b − 1` and `l_b ≥ l_a + 1` across all (left-child-event, right-child-event) pairs until a fixpoint. Tightens VLB/VUB globally.
- **AND window pruning** (`pruneAnd`) — `∀e: vub(e) ≤ minVub + tw − 1` and `vlb(e) ≥ maxVlb − tw + 1`, simultaneously, once all sub-matches are joined.
- **Per-node window pruning** — same formula, applied at every `SeqNode` / `NseqNode` merge.

A `PartialMatch` whose `vlb(e) > vub(e)` for any event fails `isValid()` and is dropped instantly.

### 6. Confidence Computation (Possible-World Semantics)

Given a completed candidate, we want:

```
Σ  [window ok] · [∀OrderPair(a,b): t_a < t_b] · [all timestamps distinct]
t₁,…,tₙ
      · Π  P(e_i occurs at t_i) · Π  (1 − Σ  P(neg_event occurs at t))
         i                        neg      t in (t_first .. t_third)
```

The engine enumerates this exactly with three accelerations:

1. **Topological ordering** via Kahn's algorithm on the partial-order graph — events are assigned in an order guaranteed to respect `before ≺ after` constraints so they are never re-checked.
2. **Dynamic lower-bound tightening per assignment** — before enumerating the lower bound of event `e`, the code walks every `OrderPair(before, e)` whose `before` is already assigned and sets `lower = max(lower, assigned[before] + 1)`.
3. **Early window cut** — after each assignment, `newMax − newMin >= tw` prunes the entire subtree.
4. **Distinctness via occupied-set membership test**.

Negation constraints are a post-pass product over valid full assignments: for each `NegConstraint(first, third, negEvents)`, we sum `P(neg occurs at t)` for every `t ∈ (assigned[first], assigned[third])` and multiply `prob` by `1 − sum`. If any sum hits 1.0 the configuration contributes zero.

---

## Architecture

```
                  ┌─────────────────────────────────────────────┐
                  │              PETreeEngine                   │
                  │  ────────────────────────────────────────   │
                  │  ingest() / flush()  (stream entry points)  │
                  │  activeRefs: PriorityQueue<ActiveRef>       │
                  │  typeToLeafMap / leafMap                    │
                  │  setFlags(def, sb, dp)  (ablations)         │
                  │  computeConfidence()  ──► topo enumeration  │
                  │  runMatching()      (batch/offline API)     │
                  └─────┬──────────────────────┬────────────────┘
                        │                      │
         ┌──────────────▼──────────┐   ┌──────▼──────────────┐
         │     StaticBounds        │   │    PETreeNode       │
         │ ──────────────────────  │   │  (pattern tree)     │
         │  Δ-min(ref,v) / Δ-max   │   │                     │
         │  pre/post/midSlots      │   │  LeafNode           │
         │  (Floyd-Warshall DAG)   │   │  SeqNode            │
         └─────────────────────────┘   │  AndNode            │
                                       │  NseqNode           │
                                       └──────┬───────────────┘
                                              │
                                  ┌───────────▼─────────────┐
                                  │      PartialMatch       │
                                  │ ─────────────────────── │
                                  │ events, vlb, vub, conf  │
                                  │ orderPairs, negConsts   │
                                  │ isValid(), merge(), …   │
                                  └───────────┬─────────────┘
                                              │
                                  ┌───────────▼─────────────┐
                                  │         Event           │
                                  │ ─────────────────────── │
                                  │ id, type, lower, upper  │
                                  │ optional pmf[], prob(t) │
                                  └─────────────────────────┘
```

| Module | File | Responsibility |
|---|---|---|
| Engine | [`src/PETreeEngine.java`](src/PETreeEngine.java) | Stream ingestion, deferred-matching scheduler, driver for static-bound filter + tree eval + confidence. |
| Pattern nodes | [`src/PETreeNode.java`](src/PETreeNode.java), [`src/LeafNode.java`](src/LeafNode.java), [`src/SeqNode.java`](src/SeqNode.java), [`src/AndNode.java`](src/AndNode.java), [`src/NseqNode.java`](src/NseqNode.java) | Pattern constructors + recursive `evaluate(tw, enableDynamicPruning)` with per-operator pruning. |
| Match state | [`src/PartialMatch.java`](src/PartialMatch.java) | Per-match event list, VLB/VUB maps, `OrderPair`s, `NegConstraint`s, validity + merge helpers. |
| Data | [`src/Event.java`](src/Event.java) | Uncertain interval event and `prob(t)` primitive. |
| Static analysis | [`src/StaticBounds.java#L38-L52`](src/StaticBounds.java#L38-L52) | Pattern DAG reachability → pre/post/mid slot counts → Δ-min/Δ-max filter. |
| Parser | [`src/Utils.java#L6-L41`](src/Utils.java#L6-L41) | `Utils.parsePattern("SEQ(A,AND(B,C))")` → tree. |

---

## Getting Started

### Prerequisites

- Java 8 or newer (no third-party libraries). The codebase is pure JDK and has been compiled successfully on OpenJDK 1.8.

### Build

No build tooling is bundled. Compile directly with `javac`:

```bash
# from the repository root
mkdir -p out
javac -encoding UTF-8 -d out src/*.java
```

Or drop the 10 `.java` files straight into any Java 8+ Maven / Gradle / IDE project.

### Quick Example

```java
import java.util.*;

public class Demo {
    public static void main(String[] args) {
        // 1. Build a pattern:  SEQ( AND( SEQ(A,B), C ), NSEQ(D, X, E) )
        PETreeNode pattern = Utils.parsePattern(
            "SEQ(AND(SEQ(A,B),C), NSEQ(D,X,E))"
        );

        int tw = 10;                                  // time window size
        PETreeEngine engine = new PETreeEngine(pattern, tw);

        // 2. Stream in some uncertain events
        engine.ingest(new Event("e1", "A", 1, 3));
        engine.ingest(new Event("e2", "B", 2, 4));
        engine.ingest(new Event("e3", "C", 1, 5));
        engine.ingest(new Event("e4", "D", 3, 6));
        engine.ingest(new Event("e5", "E", 7, 10));
        engine.flush();                              // drain remaining refs

        // 3. Read results
        System.out.println("Total matches : " + engine.totalMatches);
        System.out.println("Total events  : " + engine.totalEvents);
        System.out.println("Σ confidence  : " + engine.totalConfidence);
        System.out.println("Peak memory   : " + engine.peakMemoryBytes + " B");
    }
}
```

Compile and run (after you've built `out/` as above):

```bash
javac -encoding UTF-8 -cp out -d out Demo.java
java -cp out Demo
```

### String-Pattern API

```java
PETreeNode pattern = Utils.parsePattern("SEQ(A, AND(B, C), NSEQ(D, X, E))");
```

Grammar (whitespace-insensitive, commas separate siblings, arbitrary nesting):

```
pattern  →  SEQ ( list )
         |  AND ( list )
         |  NSEQ ( leaf , leaf , leaf )
         |  leaf
list     →  pattern ( , pattern )*
leaf     →  identifier without '(' , ')' , ','
```

Exceptions:
- `NSEQ(...)` with arity ≠ 3 → `IllegalArgumentException`
- `NSEQ(...)` wrapping non-leaf sub-patterns → `IllegalArgumentException`

### Programmatic API

If you prefer, construct the tree directly:

```java
LeafNode A = new LeafNode("A");
LeafNode B = new LeafNode("B");
LeafNode C = new LeafNode("C");
LeafNode D = new LeafNode("D");
LeafNode X = new LeafNode("X");
LeafNode E = new LeafNode("E");

PETreeNode seqAB   = new SeqNode(Arrays.asList(A, B));
PETreeNode andAC   = new AndNode(Arrays.asList(seqAB, C));
PETreeNode nseqDE  = new NseqNode(D, X, E);
PETreeNode pattern = new SeqNode(Arrays.asList(andAC, nseqDE));

PETreeEngine engine = new PETreeEngine(pattern, 10);
```

> **Note**: When you build programmatically with `NseqNode(D, X, E)`, the constructor *does not* automatically set `X.isNegation = true` (unlike the string parser, which does). Either pass the negation leaf through the constructor as `neg`, or set `X.isNegation = true` yourself before constructing `NseqNode` — results are equivalent because only `StaticBounds` cares about the flag, and it skips negation leaves from the DAG anyway.

### Streaming API

| Method | What it does |
|---|---|
| `new PETreeEngine(root, tw)` | Builds engine, collects leaves, computes `StaticBounds`, picks a reference leaf. |
| `new PETreeEngine(root, tw, refLeafUniqueName, refOriginalType)` | Same, with a **user-chosen reference leaf** (useful for reproducibility or when the pattern has many candidate ref types). |
| `engine.setFlags(def, sb, dp)` | Toggle three ablations at any time. |
| `engine.ingest(e)` | Stream an uncertain event in; runs all matches whose reference window has closed. |
| `engine.flush()` | Run matching on every remaining `ActiveRef` (call at end-of-stream). |
| `engine.runMatching()` | One-shot **offline/batch** match over the whole buffered data (no deferred scheduling). Useful for tests or small streams. |

---

## Ablation / Tuning Flags

All three flags live on `PETreeEngine` and default to `true`. They are exposed because they make the engine useful as a **research artifact** — turn them off to measure how much each optimisation contributes.

```java
engine.setFlags(
    enableDeferred,        // deferred (reference-event-triggered) vs. eager per-ingest re-match
    enableStaticBounds,    // StaticBounds Δ-min/Δ-max pre-filtering on every leaf buffer
    enableDynamicPruning   // per-merge VLB/VUB tightening + window pruning during evaluate()
);
```

### Typical effect

| Mode | What it measures |
|---|---|
| All on | Production performance. |
| `sb = false` | Baseline cost of static bounds (shows how much Δ-filtering cuts buffer sizes). |
| `dp = false` | Baseline cost of bound-tightening (shows how many partial matches would otherwise be invalid). |
| `def = false` | Switches to eager per-event matching with a signature-based de-dup + global `totalMatches` counter. |

---

## Performance Statistics

`PETreeEngine` exposes a small but useful stats surface — populated after each `ingest()/flush()`/`runMatching()` call:

| Field / Method | Meaning |
|---|---|
| `totalEvents` | Events ingested so far. |
| `totalMatches` | Completed matches with confidence > 0. |
| `totalConfidence` | Sum of confidence values over all emitted matches. |
| `totalPartialMatches` | Total `PartialMatch` objects ever created (census of search-tree size). |
| `matchTimeNs` | Cumulative nanoseconds inside `ingest`/`flush` (whole pipeline). |
| `getPartMatchTimeNs()` | Nanoseconds spent in `root.evaluate(…)` (pure tree search, pre-confidence). |
| `getConfTimeNs()` | Nanoseconds spent inside `computeConfidence(…)`. |
| `peakMemoryBytes` | Peak `Runtime.totalMemory − freeMemory` sample (after each ingest; note: GC is not forced). |
| `curMemoryBytes` | Most recent memory sample. |

These are intentionally plain fields — wrap them in a report method of your choice for experiments.

---

## Project Structure

```
PETree/
├── src/
│   ├── AndNode.java         # AND operator (+ cartesian helper, Hall-style distinct-feasibility)
│   ├── Event.java           # Uncertain interval event; prob(t) primitive
│   ├── LeafNode.java        # Leaf operator + static-bounds filter + EventEntry buffer
│   ├── NseqNode.java        # NSEQ(first, neg, third) operator
│   ├── PartialMatch.java    # Per-match VLB/VUB, OrderPair, NegConstraint, validity
│   ├── PETreeEngine.java    # Streaming engine, scheduler, confidence, flags, stats
│   ├── PETreeNode.java      # Abstract base; applyWindowPruning shared helper
│   ├── SeqNode.java         # SEQ operator (+ fixpoint seq-across pruning)
│   ├── StaticBounds.java    # DAG reachability → Δ-min/Δ-max filter
│   └── Utils.java           # parsePattern(string) → tree
└── README.md
```

**Quick links to source files**

- [src/SeqNode.java](src/SeqNode.java) · [src/AndNode.java](src/AndNode.java) · [src/NseqNode.java](src/NseqNode.java) · [src/PETreeNode.java](src/PETreeNode.java)
- [src/LeafNode.java](src/LeafNode.java) · [src/PartialMatch.java](src/PartialMatch.java) · [src/Event.java](src/Event.java)
- [src/PETreeEngine.java](src/PETreeEngine.java) · [src/StaticBounds.java](src/StaticBounds.java) · [src/Utils.java](src/Utils.java)

---

## License

This project is released under the **MIT License** by default — adjust the badge / copyright line below before publishing if you prefer something else.

> Replace the placeholder block with your actual licence text. Add a `LICENSE` file alongside this README.

```
MIT License

Copyright (c) 2026

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

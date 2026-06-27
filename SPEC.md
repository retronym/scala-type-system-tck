# The "Missing Spec": Conformance and Base Type Sequences

This document specifies the two type-system operations the TCK exercises:

1. **Conformance** — `A <:< B` (is `A` a subtype of `B`?).
2. **Base type sequence** — `baseTypeSeq(A)`, the linearized sequence of base
   types of `A`, in the order the Scala compiler computes it.

Conformance is (mostly) specified by the [Scala Language Specification (SLS)
§3.5.2](https://scala-lang.org/files/archive/spec/2.13/03-types.html#conformance).
The base type sequence is **not** fully specified by the SLS: only its *ordering*
(class linearization, SLS §5.1.2) is written down. The *construction* of the
sequence — how same-symbol entries are merged, how prefixes and type arguments
are combined, and how depth is bounded — lives only in the compiler source. This
document reconstructs that missing part from `scala/scala`'s
`scala.reflect.internal` and validates it against the running compiler (the TCK's
reference engine).

The goal is a specification precise enough that a *second* implementation — the
IntelliJ Scala plugin's type system — can be checked against it, query by query,
with the Scala compiler as the oracle.

---

## 1. Sources of truth

| Concern | Canonical source |
| --- | --- |
| Conformance relation | SLS §3.5.2; `Types#TypeRef.<:<`, `isSubType` in `Types.scala` |
| Linearization order | SLS §5.1.2 (Class Linearization); `Symbol#baseClasses` |
| Base type *sequence* construction | `BaseTypeSeqs.scala` (`BaseTypeSeq`, `CompoundType#baseTypeSeq`) |
| Same-symbol merge | `Types#mergePrefixAndArgs` |
| Depth bounding | `BaseTypeSeq#maxDepth`, `TypeConstraints` depth limits |

The reference engine in this repo embeds `scala.tools.nsc.Global` and reads these
values directly, so the spec below is *executable*: every claim is a golden file
the TCK regenerates from the live compiler.

---

## 2. Class linearization `L(C)` (the ordering)

This part **is** in the SLS (§5.1.2) and we restate it for completeness because it
determines base-type-seq order.

For a class/trait `C` with template `C extends C₁ with … with Cₙ`:

```
L(C) = C, L(Cₙ) ⨾ … ⨾ L(C₁)
```

where `⨾` is concatenation that keeps the **right** operand's occurrence of any
duplicated class and drops the left's:

```
xs ⨾ ys = xs' ++ ys     where xs' = xs with every element of ys removed
```

Properties the sequence must satisfy (the TCK asserts these as invariants):

- **L1.** `C` is the first element of `L(C)`.
- **L2.** Each class appears exactly once.
- **L3.** If `D` is a proper base class of `E`, then `E` precedes `D` in `L(C)`
  (more-derived first; `scala.Any`/`AnyRef`/`Object` last).
- **L4.** Linearization is monotonic with respect to the declared parent order
  (later parents dominate earlier ones).

`baseClasses` is exactly `L(C)` as a list of class symbols.

> **Important (verified against scalac 2.13):** `baseClasses` order is **not** the
> same as `baseTypeSeq` order. `baseClasses` is the mixin-order-sensitive
> linearization `L(C)`; `baseTypeSeq` is sorted by a *symbol-id total order
> consistent with subtyping*. Two types with identical base classes in different
> linearization order share the same `baseTypeSeq` order. Concretely, for
> `D extends B with C` and `D2 extends C with B`:
>
> | | `baseClasses` (linearization) | `baseTypeSeq` (symbol-id order) |
> |---|---|---|
> | `D`  | `D, C, B, A, Object, Any`  | `D, B, C, A, Object, Any` |
> | `D2` | `D2, B, C, A, Object, Any` | `D2, B, C, A, Object, Any` |
>
> So the **mixin-order / linearization signal lives in `baseClasses`**, and the
> residual "ordering differs from scalac" concern should be checked there, not in
> `baseTypeSeq` (whose order is a deterministic symbol-id sort). The corpus's
> diamond entry (06) records this; a `baseClasses` query is future work.

---

## 3. The base type sequence (the construction — the missing part)

`baseTypeSeq(T)` is a `BaseTypeSeq`: an ordered array of *types* `bt₀, bt₁, …`
such that `bt₀ = T` (or its widening). Its elements cover the same *set* of base
classes as `baseClasses`, but ordered by the symbol-id total order above (not the
linearization). Each `btᵢ` is, conceptually, `T.baseType(symᵢ)`: the base type
*as seen from* `T`, with prefixes instantiated and type arguments substituted
along the path from `T` to that base class.

> **Subtlety (verified):** for a same-symbol merge, the `baseTypeSeq` *element* and
> `T.baseType(sym)` can differ in representation. For `Box[Animal] with Box[Dog]`
> (`class Box[+A]`), the `baseTypeSeq` element for `Box` is the **intersection**
> `Box[Dog] with Box[Animal]` (a `RefinedType` with `typeSymbol = Box`), whereas
> `T.baseType(Box)` returns the glb-reduced `Box[Dog]`. The glb/lub merge of §3.1
> is what `baseType` performs; `baseTypeSeq` may carry the unreduced intersection
> until `baseType` is forced. Whether IntelliJ represents the merged base as an
> intersection or a glb is a likely divergence point.

### 3.1 Construction for a compound/refined type

For `T = P₁ with … with Pₙ { R }` (a `CompoundType`/`RefinedType`), the sequence
is produced by **merging** the parents' base type sequences:

1. Compute `bts(Pⱼ)` for each parent.
2. Merge them into a single ordered sequence whose symbol order is `L(T)`
   (§2). The merge is a k-way topological pass: at each step it emits the next
   symbol that does not appear *later* in the linearization of any unconsumed
   parent (this is what `BaseTypeSeqs#baseTypeSeq` / the `maxDepth`-bounded merge
   loop does).
3. **Same-symbol merge (the key under-documented step).** When several parents
   contribute a base type with the *same* class symbol `S` but different
   prefixes or type arguments, the entry for `S` is the combination
   `mergePrefixAndArgs(candidates, variance, depth)`:
   - **Prefix:** the prefixes are themselves combined (recursively, via the same
     merge / a `lub`-like operation on prefixes).
   - **Type arguments**, positionally, by the declared variance of `S`'s
     parameter:
     - *covariant* → greatest lower bound (`glb`) of the candidate arguments,
     - *contravariant* → least upper bound (`lub`),
     - *invariant* → the arguments must be equivalent; otherwise the position is
       widened to a bound (or yields a type with a wildcard/range), and depth is
       charged.
   - If the merge would exceed `maxDepth`, the result is **approximated**
     (truncated to bounds) rather than computed exactly — see §3.3.

### 3.2 Construction for a `TypeRef` `pre.C[args]`

`baseTypeSeq(pre.C[args])` takes `C`'s own (declaration-site) base type sequence
and *asSeenFrom*s every element through `pre` with `C`'s type parameters bound to
`args`. No merge is needed (a class has a single declared parent list); the
linearization is `C`'s, and each entry is substituted. This is the common case
and the one most exercised by HList-style projections (`L#Head`, `L#Tail`), where
`pre` is itself a projection and the substitution must thread through it.

### 3.3 Depth bounding and approximation

`BaseTypeSeq` carries a `maxDepth`. The merge in §3.1 is bounded: beyond
`maxDepth`, same-symbol argument merges are replaced by their bounds rather than
recursively computed. This means base type sequences are, in pathological cases,
an **approximation**, and two compilers can legitimately differ on the
*approximated* tail while agreeing on everything a program can actually observe.
The TCK records depth alongside each golden so divergences can be classified as
*"real linearization bug"* vs *"approximation-seam difference"*.

---

## 4. Type rendering normal form (the cross-engine contract)

Goldens must be comparable between scalac and IntelliJ, which print types
differently. The TCK defines a **canonical rendering** that both engines must
produce. A `RenderedType` is a string built by these rules:

- **Corpus-defined symbols** are rendered by their name *relative to the corpus
  module*, using `#` for type-member nesting: `Animal`, `Outer#Inner`. The
  synthetic wrapper module prefix (`__tck.Corpus.`) is stripped.
- **Library symbols** are rendered by fully-qualified name: `scala.Any`,
  `scala.collection.immutable.List`. (No alias expansion: `Predef.String`
  renders as `java.lang.String` only if dealiased; the TCK dealiases type
  aliases before rendering — see below.)
- **Type application**: `Name[Arg₁, …, Argₙ]`, arguments rendered recursively.
- **Refinement**: `Parent { decls }` with `decls` sorted by name; each `type X`
  rendered as `type X = T` / `type X >: L <: U`, each `def`/`val` by signature.
- **Singleton / path-dependent**: `p.type` where `p` is the rendered stable path;
  `this` is normalized to the enclosing corpus symbol's name.
- **Type aliases are dealiased** before rendering, so `String` and
  `Predef.String` both render to their dealiased form. (baseTypeSeq elements are
  class types, so this mainly affects query *inputs*.)

These rules are normative: an engine that produces a different string for the
same type is non-conforming, even if its internal type is "morally" equal. Where
the rules are ambiguous, scalac's behavior (as captured in goldens) is the tie
breaker, and the rule is tightened here.

---

## 4a. Referencing context-dependent types (anchors)

Most query types can be named from a single neutral scope (the synthetic corpus
object): `Dog`, `Box[Dog]`, `HCons[Dog, HNil]#Head`. But some types **only exist
relative to a specific `this`** and cannot be named from outside the template
that introduces it:

- `this.type` of a particular trait/class,
- an abstract type member relative to `this` (`this.T`),
- a **self-type** witness: with `trait AnimalBox { self: Animal => }`, the fact
  that the instance is an `Animal` is observable only through `AnimalBox.this`.

The corpus expresses these with **anchors**: a `/*ANCHOR <id>*/` marker placed at
a statement position inside the relevant template in `source.scala`. A type query
may name an anchor; the engine resolves the query's expression *as if written at
that marker*, so `this`, the self-type, and in-scope members resolve correctly.

```scala
trait AnimalBox { self: Animal =>
  type T
  /*ANCHOR inAnimalBox*/
}
```
```json
{ "name": "AnimalBoxThis", "expr": "this.type", "anchor": "inAnimalBox" }
```

Both engines must honor anchors identically:

- **scalac** splices `type __q = <expr>` in at the marker and reads the resolved
  type off the typed tree (owner prefix + self-type intact).
- **IntelliJ** resolves `<expr>` with the PSI element at the marker offset as the
  resolution context (`createTypeElementFromText(expr, contextAtAnchor)`).

This is where SCL-21947 lives: `AnimalBox <:< Animal` is **false** (the trait
itself is not an `Animal`), but `AnimalBox.this.type <:< Animal` is **true**, and
the self-type even participates in `AnimalBox.this`'s base type sequence
(`AnimalBox with Animal` heads it). An engine that resolves `this.type` without
the self-type context gets both the conformance and the sequence wrong.

## 5. Conformance (`A <:< B`)

The TCK treats conformance as **human-authored ground truth** (we know
`Dog <:< Animal`), cross-checked against scalac (validates the harness) and
against IntelliJ (the system under test). The cases exercised, in order of the
corpus:

1. Nominal subtyping and transitivity.
2. Variance of type constructors (`List[Dog] <:< List[Animal]`, etc.).
3. Refinement subtyping (`T { type A = Int } <:< T`).
4. Projection/path-dependent conformance (the SCL-21585 / SCL-21947 shapes).
5. Self-type–dependent conformance (SCL-21947).

Conformance and base type sequences are **linked**: `A <:< B` for two class types
holds iff `B`'s symbol is in `baseClasses(A)` *and* `A.baseType(B.symbol)`
conforms to `B` argument-wise. A base-type-seq ordering or same-symbol-merge bug
therefore manifests as a conformance bug, which is why the TCK covers both
against the same corpus.

---

## 6. What the TCK asserts

For every corpus entry and every query, against each engine:

- **Conformance**: `engine.conforms(lhs, rhs) == expect` (human ground truth).
- **Base type sequence**: `engine.baseTypeSeq(t)` equals the committed golden
  (generated by the scalac engine), compared as a list of `RenderedType`. Note
  baseTypeSeq order is the *symbol-id* order, not the linearization (§2 box).
- **Base classes (linearization)**: `engine.baseClasses(t)` equals the committed
  golden — an **ordered** list of class names. This is the mixin-order-sensitive
  signal and the primary residual-ordering surface (the scalac engine computes it
  from `Type#baseClasses`; the IntelliJ engine from `MixinNodes.linearization`).
- **Invariants** L1–L4 of §2 hold for every produced sequence (engine-internal
  sanity, independent of the golden).

A divergence is classified as:

- **Order bug** — same membership, different order (the primary suspected
  IntelliJ defect).
- **Membership bug** — a base type present in one engine, absent in the other.
- **Merge/render bug** — same symbol present in both, different rendered arguments
  or prefix.
- **Approximation seam** — divergence only beyond `maxDepth` (§3.3); recorded, not
  failed, unless it is observable via a conformance query.

---

## 7. Open questions / future work

- **Scala 3.** Dotty has no `BaseTypeSeq` object; it computes `baseType(cls)`
  on demand and linearization via `baseClasses`. A Scala-3 reference engine would
  derive the sequence as `baseClasses.map(baseType)` and we must confirm IntelliJ
  models Scala-3 semantics, not Scala-2, for Scala-3 sources.
- **Existentials and wildcards** in merged arguments — exact rendering normal form
  TBD; currently scalac is the oracle.
- **Higher-kinded** base types (`F[_]` parents) — merge variance interacts with
  kind; corpus coverage pending.

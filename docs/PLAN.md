# PLAN — scala-type-system-tck

Status and roadmap. The contract (corpus format, rendering, checks) is [TCK.md](TCK.md); the semantics are [SPEC-GAPS.md](SPEC-GAPS.md).

## Status

### Done
- [x] Project skeleton, git init, scala-cli reference module.
- [x] SPEC.md — the missing spec (conformance + baseTypeSeq construction); later
      folded into TCK.md (contract) and SPEC-GAPS.md §3 (semantics).
- [x] Corpus format + loader (upickle).
- [x] `TckEngine` abstraction + `RenderedType`.
- [x] `ScalacEngine` (embedded Global): resolve type strings, conforms, baseTypeSeq, render.
- [x] Golden generation + verification CLI (`Main generate` / `Main verify`).
- [x] munit test running corpus through ScalacEngine.
- [x] Corpus 00–03: nominal, variance, refinement, projection/HList.
- [x] Anchors: `/*ANCHOR id*/` markers + `anchor` on type decls; engine splices
      query aliases at the marker and recovers types from the typed tree.
- [x] Corpus 04: context-dependent types via self-type (SCL-21947 shape).
- [x] Corpus 05: refinement substitution through a projection (SCL-21585 proper —
      `(M { type A = B })#A`, via type-alias projection, and combined with `with`).
- [x] Corpus 06–15 (gap-analysis fill, all 2.13-reachable): diamond linearization,
      same-symbol glb/lub merge, F-bounds, higher-kinded, existentials, structural
      refinements, singleton/literal/path, variance-through-inheritance,
      inner-class prefix, top/bottom + value classes. All green vs scalac oracle.
- [x] SPEC correction: `baseClasses` (linearization, mixin-ordered) ≠ `baseTypeSeq`
      (symbol-id order); same-symbol merge stored as intersection vs glb in
      `baseType`. Verified against scalac.

### baseClasses (linearization) dimension
- [x] **Reference engine + goldens**: `baseClasses` ordered list per query type
      (mixin-order sensitive). 06 now records `D=D,C,B,A` vs `D2=D2,B,C,A`.
      Verify/test assert it. This is the real residual-ordering surface.
- [ ] **IntelliJ engine**: compare against `MixinNodes.linearization(clazz|compound)`
      (returns an ordered `Seq[ScType]`) — ordered comparison, normalizing the
      self/Any convention.

### Done (IntelliJ side — in the intellij-scala repo, branch `scala-typesystem-tck`)
- [x] PSI engine scaffold: `lang/typeSystemTck/{TckCorpus,TypeSystemTckTest}.scala`.
      Loads this corpus directly from `~/code/scala-type-system-tck` (Gson),
      splices `__q_` aliases (anchors via lexical placement), reads
      `ScTypeAliasDefinition.aliasedType`, runs `conforms` (hard) + `BaseTypes.get`
      (set membership vs golden). Renderer normalizes `canonicalText` to TCK.md §4.
      Note: `BaseTypes.get` is **unordered** (`HashMap.values`) — order can't be
      checked yet; membership only.
- [x] Corpus 16–21: member-type / asSeenFrom + singleton val-path-through-refinement
      shapes (SCL-21947), with the `termTypes` probe dimension reading inferred
      member-access types vs the scalac oracle.
- [x] Corpus 22: `MutableSettings` shape — refinement on the BOUND of an abstract
      type member, refined member (`type T`) inherited transitively. `(x:
      BooleanSetting).value` left the prefix as the raw `SettingValue.this` →
      abstract `SettingValue.this.T` instead of `BooleanSetting#T`. **Fixed** in
      IntelliJ `ThisTypeSubstitution.hasSameOrInheritor`: it now widens an abstract
      type-alias compound component to its upper bound (it already did so for type
      *parameters*), so `SettingValue` is found under `Setting`'s bound and the
      this-type re-anchors. Guarded by `OverrideHighlightingTest`. The `#T`-vs-
      `Boolean` render difference is pinned in the PSI test's `Deferred.termType`
      (representation seam; conformance/`=:=` is correct).

### SPEC-GAPS.md follow-ups (SLS 2.13 gap analysis)
- [x] [SPEC-GAPS.md](SPEC-GAPS.md): what the SLS specifies vs what only scalac defines,
      for memberType, asSeenFrom, base types, lub, path equivalence, packedType, self types.
- [x] Corpus 18 made legal: the multi-path merge now goes through compound types
      (`L with R`); `trait LR extends L with R` was rejected by scalac at refchecks
      (the engine stops after typer). Pins that `<:<` doesn't use the merged base type.
- [x] Corpus 29: asSeenFrom of an outer class type parameter through an inner class re-extending the outer — `c.f` is `String`; a literal SLS §3.4 reading gives an unsound `Int`.
- [x] Corpus 30: `this.type` seen from an unstable prefix — `mk().arr` is `Array[_1] forSome { type _1 <: X with Singleton }` (scalac `captureThis`).
- [x] Corpus 31: override-aware singleton path through a plain `val` override — `b.get: b.x.type`, which conforms to `String` (scalac `rebind`).
- [x] Corpus 32: invariant same-class merge in a compound type — `I[Dog] with I[Cat]` has base type `I[_1] forSome { type _1 >: Cat with Dog <: Animal }`; `x.get: Animal`, `pick(x): Dog`.
- [x] Corpus 33: type avoidance beyond singletons — block-local vals in invariant positions and block-local classes/objects pack existentially (SLS §6.11).
- [x] Corpus 34: lub keeps the path prefix — `lub(global.TypeSymbol, global.TermSymbol) = global.Symbol`; `lub(a.Tree, b.Tree) = G#Tree`.
- [x] Corpus 35: intersection component order — `Cat with Dog` / `Dog with Cat` and `Inv[...]` of them conform both ways but are not `=:=`.
- [x] Corpus 36: lub is not associative — an n-ary `match` over `List`/`Vector`/`Set` differs from the left-nested `if`.
- [x] Corpus 37: self-type spelling — self-type members are spelled after the using class (`Definitions.this.Symbol`); `this` is `Impl with Api`, or `SymbolTable` when the self type extends the class.

### TODO (next phases)
- [ ] Order-preserving base-type API in IntelliJ so the sequence (not just the
      set) can be checked — the crux of the residual SCL-21585/21947 ordering bug.
- [ ] Tighten canonical rendering for refinements / existentials / singletons.
- [ ] Linearization invariant checks in the runner: the type itself first, each class
      once, every class before its proper base classes, `Any` last.
- [ ] Depth/approximation-seam recording (SPEC-GAPS.md §3, Depth) in goldens.
- [ ] Scala 3 reference engine (see Open questions).
- [ ] Consume the corpus as a build dependency (unpack in the build) rather than
      referencing the sibling checkout.
- [x] CI: verify (ground truth + legality through refchecks + golden drift), munit,
      and regenerate-and-diff of all goldens (`.github/workflows/ci.yml`).

## Open questions

- **Scala 3.** Dotty has no `BaseTypeSeq`; it computes `baseType(cls)` on demand and linearization via `baseClasses`. A Scala 3 reference engine would derive the sequence as `baseClasses.map(baseType)`. We must also confirm that IntelliJ applies Scala 3 semantics, not Scala 2's, to Scala 3 sources.
- **Higher-kinded base types** (`F[_]` parents): merge variance interacts with kind; corpus coverage pending.

# PLAN — scala-type-system-tck

A Technology Compatibility Kit for Scala's type system: a progressive corpus of
type definitions plus conformance and base-type-sequence queries, runnable
against the Scala compiler (reference oracle) and the IntelliJ Scala plugin's PSI
type system (system under test).

See [SPEC.md](SPEC.md) for the type-system specification this validates.

## Architecture

- **Corpus** = language-neutral data. Each entry is a directory under `corpus/`:
  - `source.scala` — the preamble (type/class/trait declarations).
  - `tck.json` — `description`, `concepts`, `types` (each `{name, expr, anchor?}`),
    `conformance` queries (with human `expect`), `baseTypeSeq` query list.
  - **Anchors**: context-dependent types (`this.type`, `this.T`, self-type
    references) name a `/*ANCHOR id*/` marker in `source.scala`; the engine
    resolves the expression at that marker's lexical context. See SPEC §4a.
  - `expected.json` — **generated** goldens: baseTypeSeq results as canonical
    `RenderedType` lists, plus the scalac conformance results.
- **TckEngine** (abstract) — `conforms`, `baseTypeSeq`, `render`. Two impls:
  - `ScalacEngine` (this repo) — embeds `scala.tools.nsc.Global`. **Reference.**
  - `IntellijPsiEngine` (in the intellij-scala repo) — `ScType`, `conforms`,
    `bts`. Consumes the same corpus data + goldens.
- **Canonical rendering** (SPEC §4) is the cross-engine contract.

## Status

### Done
- [x] Project skeleton, git init, scala-cli reference module.
- [x] SPEC.md — the missing spec (conformance + baseTypeSeq construction).
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

### Key follow-up surfaced by 06/07
- [ ] **Add a `baseClasses` query dimension** to the reference engine + goldens.
      This is the actual mixin-order/linearization signal (06 shows `baseTypeSeq`
      order is mixin-INsensitive). The residual SCL ordering issue should be
      checked here. Needs an ordered-linearization API on the IntelliJ side too.

### Done (IntelliJ side — in the intellij-scala repo, branch `scala-typesystem-tck`)
- [x] PSI engine scaffold: `lang/typeSystemTck/{TckCorpus,TypeSystemTckTest}.scala`.
      Loads this corpus directly from `~/code/scala-type-system-tck` (Gson),
      splices `__q_` aliases (anchors via lexical placement), reads
      `ScTypeAliasDefinition.aliasedType`, runs `conforms` (hard) + `BaseTypes.get`
      (set membership vs golden). Renderer normalizes `canonicalText` to SPEC §4.
      Note: `BaseTypes.get` is **unordered** (`HashMap.values`) — order can't be
      checked yet; membership only.

### TODO (next phases)
- [ ] Order-preserving base-type API in IntelliJ so the sequence (not just the
      set) can be checked — the crux of the residual SCL-21585/21947 ordering bug.
- [ ] Tighten canonical rendering for refinements / existentials / singletons.
- [ ] L1–L4 invariant checks in the runner.
- [ ] Depth/approximation-seam recording (SPEC §3.3) in goldens.
- [ ] Scala 3 reference engine (SPEC §7).
- [ ] Consume the corpus as a build dependency (unpack in the build) rather than
      referencing the sibling checkout.
- [ ] CI: regenerate goldens, assert no drift.

## Conventions
- Corpus entries are numbered and **progressive**: each introduces one new concept
  on top of the previous. A failing early entry should explain later failures.
- Goldens are committed and regenerated via `Main generate`; drift is a test
  failure.

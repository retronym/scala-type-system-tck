# PLAN — scala-type-system-tck

A Technology Compatibility Kit for Scala's type system: a progressive corpus of
type definitions plus conformance and base-type-sequence queries, runnable
against the Scala compiler (reference oracle) and the IntelliJ Scala plugin's PSI
type system (system under test).

See [SPEC.md](SPEC.md) for the type-system specification this validates.

## Architecture

- **Corpus** = language-neutral data. Each entry is a directory under `corpus/`:
  - `source.scala` — the preamble (type/class/trait declarations).
  - `tck.json` — `description`, `concepts`, named `types` (type-expression
    strings), `conformance` queries (with human `expect`), `baseTypeSeq` query
    list.
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

### TODO (next phases)
- [ ] Corpus 04: path-dependent types via self-type (SCL-21947 shape).
- [ ] Corpus 05: refinement substitution through a projection (SCL-21585 shape).
- [ ] Tighten canonical rendering for refinements / existentials / singletons.
- [ ] L1–L4 invariant checks in the runner.
- [ ] `IntellijPsiEngine` in intellij-scala consuming `corpus/` + goldens.
- [ ] Depth/approximation-seam recording (SPEC §3.3) in goldens.
- [ ] Scala 3 reference engine (SPEC §7).
- [ ] CI: regenerate goldens, assert no drift.

## Conventions
- Corpus entries are numbered and **progressive**: each introduces one new concept
  on top of the previous. A failing early entry should explain later failures.
- Goldens are committed and regenerated via `Main generate`; drift is a test
  failure.

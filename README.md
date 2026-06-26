# scala-type-system-tck

A Technology Compatibility Kit for Scala's type system. It pins down two
operations — **conformance** (`A <:< B`) and **base type sequences**
(`baseTypeSeq(A)`) — with a progressive corpus of type definitions and queries,
and runs them against two implementations:

- the **Scala compiler** (`scala.tools.nsc.Global`), used as the reference oracle;
- the **IntelliJ Scala plugin** PSI type system (in the intellij-scala repo), the
  system under test.

It exists to track down divergences such as
[SCL-21585](https://youtrack.jetbrains.com/issue/SCL-21585) and
[SCL-21947](https://youtrack.jetbrains.com/issue/SCL-21947), where IntelliJ's
`baseTypeSeq` ordering / refinement substitution differs from scalac's.

## Layout

```
SPEC.md                     the "missing spec": conformance + baseTypeSeq construction
PLAN.md                     status and roadmap
corpus/NN-name/
  source.scala              the type-declaration preamble
  tck.json                  named type expressions + conformance/baseTypeSeq queries
  expected.json             generated goldens (scalac oracle)
reference/                  scala-cli module: the scalac reference engine
```

The corpus is **language-neutral data**. A type is referenced abstractly by a
*type-expression string* resolved in the preamble's scope — scalac resolves it as
a `type` alias, IntelliJ via `createTypeElementFromText`. Context-dependent types
(`this.type`, `this.T`, self-type references) name a `/*ANCHOR id*/` marker in
`source.scala` and are resolved at that location (see SPEC §4a). The same corpus
and goldens drive both engines (see `TckEngine` in `reference/Tck.scala`).

## Usage

```bash
scala-cli run  reference -- generate     # (re)write expected.json goldens
scala-cli run  reference -- verify       # check conformance + golden drift
scala-cli run  reference -- show 03-projection-hlist
scala-cli test reference                 # munit: corpus vs ground truth + goldens
```

## How a corpus entry works

`tck.json` lists named types as Scala type expressions and asserts conformance
with **human-authored** ground truth; baseTypeSeq expectations are captured into
`expected.json` from the compiler. The reference engine wraps the preamble plus
one `type __q_<name> = <expr>` alias per named type into a synthetic
`__tck.Corpus` object, compiles it up to typer, and reads `<:<` and
`.baseTypeSeq` off the live types, rendering each via the canonical normal form
in [SPEC.md §4](SPEC.md).

Adding an entry: create `corpus/NN-name/{source.scala,tck.json}`, run
`generate`, eyeball the golden, commit.

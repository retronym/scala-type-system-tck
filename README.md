# scala-type-system-tck

A Technology Compatibility Kit for Scala 2.13's type system. A corpus of small programs, each paired with type-system questions, is answered by two implementations and the answers are compared:

- the **Scala compiler** (`scala.tools.nsc.Global`), the reference oracle, which generates the goldens;
- the **IntelliJ Scala plugin**'s PSI type system (in intellij-scala), the system under test.

The questions are conformance (`A <: B`), equivalence (`A =:= B`), base type sequences and linearization, `prefix baseType C`, and the inferred type of an expression. The TCK exists to find divergences such as [SCL-21585](https://youtrack.jetbrains.com/issue/SCL-21585) and [SCL-21947](https://youtrack.jetbrains.com/issue/SCL-21947), path-dependent and self-type false errors in the cake pattern.

## Documentation

- [docs/TCK.md](docs/TCK.md): the contract. The corpus entry format, anchors, the rendering normal form, what is checked, and how to add an entry.
- [docs/SPEC-GAPS.md](docs/SPEC-GAPS.md): the semantics. What the Scala Language Specification says about each operation the corpus exercises, what it leaves open, and what scalac actually does.
- [docs/PLAN.md](docs/PLAN.md): status and roadmap.

## Layout

```
corpus/NN-name/
  source.scala      the preamble: Scala declarations, with /*ANCHOR id*/ markers
  tck.json          the queries, with human-authored expectations
  expected.json     generated goldens (scalac's answers)
reference/          scala-cli module: the scalac reference engine and CLI
docs/               contract, semantics, plan
```

## Usage

```bash
scala-cli run  reference -- verify       # legality, ground truth, golden drift
scala-cli run  reference -- generate     # (re)write expected.json goldens
scala-cli run  reference -- show 03-projection-hlist
scala-cli test reference                 # the same checks as munit tests
```

CI ([.github/workflows/ci.yml](.github/workflows/ci.yml)) runs `verify`, the munit tests, and a drift check that regenerates every golden and fails on any difference.

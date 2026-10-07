package tck

/**
 * Runs the whole corpus through the scalac reference engine and asserts:
 *  - the entry is a legal program (compiles through refchecks),
 *  - conformance and equivalence match human-authored ground truth, and
 *  - every recorded answer matches the committed golden (drift guard).
 *
 * The same assertions, with `IntellijPsiEngine` substituted, are how the plugin
 * is validated in the intellij-scala repo.
 */
class ScalacTckTest extends munit.FunSuite {
  private val entries = Corpus.load()

  test("corpus is non-empty") {
    assert(entries.nonEmpty, "no corpus entries found")
  }

  entries.foreach { e =>
    val actual = ScalacEngine.run(e)

    test(s"${e.id}: is a legal program (compiles through refchecks)") {
      assertEquals(ScalacEngine.legalityErrors(e), Nil)
    }

    e.entry.conformance.zip(actual.conformance).foreach { case (q, r) =>
      test(s"${e.id}: ${q.lhs} <:< ${q.rhs} == ${q.expect}") {
        assertEquals(r.holds, q.expect)
      }
    }

    e.entry.equivalence.zip(actual.equivalence).foreach { case (q, r) =>
      test(s"${e.id}: ${q.lhs} =:= ${q.rhs} == ${q.expect}") {
        assertEquals(r.holds, q.expect)
      }
    }

    test(s"${e.id}: has a golden") {
      assert(Corpus.readGolden(e).isDefined, "no expected.json (run `generate`)")
    }

    Corpus.readGolden(e).foreach { g =>
      g.baseTypeSeq.foreach { case (t, expected) =>
        test(s"${e.id}: baseTypeSeq($t) matches golden") {
          assertEquals(actual.baseTypeSeq.getOrElse(t, Nil), expected)
        }
      }
      g.baseClasses.foreach { case (t, expected) =>
        test(s"${e.id}: baseClasses($t) matches golden") {
          assertEquals(actual.baseClasses.getOrElse(t, Nil), expected)
        }
      }
      g.termTypes.foreach { case (t, expected) =>
        test(s"${e.id}: termType($t) matches golden") {
          assertEquals(actual.termTypes.getOrElse(t, ""), expected)
        }
      }
      g.baseTypes.foreach { case (t, expected) =>
        test(s"${e.id}: baseType($t) matches golden") {
          assertEquals(actual.baseTypes.getOrElse(t, ""), expected)
        }
      }
    }
  }
}

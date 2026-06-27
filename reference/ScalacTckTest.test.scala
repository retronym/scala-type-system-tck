package tck

/**
 * Runs the whole corpus through the scalac reference engine and asserts:
 *  - conformance matches human-authored ground truth (validates the harness), and
 *  - baseTypeSeq matches the committed golden (regression / drift guard).
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

    e.entry.conformance.zip(actual.conformance).foreach { case (q, r) =>
      test(s"${e.id}: ${q.lhs} <:< ${q.rhs} == ${q.expect}") {
        assertEquals(r.holds, q.expect)
      }
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

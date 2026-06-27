package tck

/**
 * CLI for the reference engine.
 *
 *   generate   (re)write expected.json goldens for every corpus entry
 *   verify     run the corpus and report conformance + golden mismatches
 *   show <id>  print the engine's output for one entry
 */
object Main {
  def main(args: Array[String]): Unit = args.toList match {
    case "generate" :: _ => generate()
    case "show" :: id :: _ => show(id)
    case ("verify" :: _) | Nil => if (!verify()) sys.exit(1)
    case other => Console.err.println(s"unknown command: ${other.mkString(" ")}"); sys.exit(2)
  }

  private def generate(): Unit =
    Corpus.load().foreach { e =>
      Corpus.writeGolden(e, ScalacEngine.run(e))
      println(s"[${e.id}] golden written (${e.entry.types.size} types)")
    }

  private def show(id: String): Unit =
    Corpus.load().find(_.id == id) match {
      case None => Console.err.println(s"no such entry: $id"); sys.exit(2)
      case Some(e) =>
        val g = ScalacEngine.run(e)
        println(s"# ${e.id}: ${e.entry.description}")
        g.conformance.foreach(c => println(f"  ${c.lhs} <:< ${c.rhs} = ${c.holds}"))
        g.baseTypeSeq.foreach { case (t, seq) =>
          println(s"  baseTypeSeq($t):")
          seq.foreach(s => println(s"    - $s"))
        }
        g.baseClasses.foreach { case (t, seq) =>
          println(s"  baseClasses($t): ${seq.mkString(", ")}")
        }
        g.termTypes.foreach { case (t, ty) =>
          println(s"  termType($t): $ty")
        }
    }

  /** Returns true if everything passes. */
  private def verify(): Boolean = {
    var allOk = true
    Corpus.load().foreach { e =>
      var entryOk = true
      val actual = ScalacEngine.run(e)
      // 1. conformance vs human ground truth
      e.entry.conformance.zip(actual.conformance).foreach { case (q, r) =>
        if (r.holds != q.expect) {
          entryOk = false
          println(s"[${e.id}] CONFORMANCE: ${q.lhs} <:< ${q.rhs} expected ${q.expect}, scalac says ${r.holds}")
        }
      }
      // 2. baseTypeSeq vs committed golden (regression)
      Corpus.readGolden(e) match {
        case None => println(s"[${e.id}] no golden (run `generate`)")
        case Some(g) =>
          g.baseTypeSeq.foreach { case (t, expected) =>
            val got = actual.baseTypeSeq.getOrElse(t, Nil)
            if (got != expected) {
              entryOk = false
              println(s"[${e.id}] BASETYPESEQ($t) drift:")
              println(s"    golden: $expected")
              println(s"    actual: $got")
            }
          }
          g.baseClasses.foreach { case (t, expected) =>
            val got = actual.baseClasses.getOrElse(t, Nil)
            if (got != expected) {
              entryOk = false
              println(s"[${e.id}] BASECLASSES($t) drift:")
              println(s"    golden: $expected")
              println(s"    actual: $got")
            }
          }
          g.termTypes.foreach { case (t, expected) =>
            val got = actual.termTypes.getOrElse(t, "")
            if (got != expected) {
              entryOk = false
              println(s"[${e.id}] TERMTYPE($t) drift: golden=$expected actual=$got")
            }
          }
      }
      allOk &&= entryOk
      println(s"[${e.id}] ${if (entryOk) "ok" else "FAIL"} — ${e.entry.description}")
    }
    allOk
  }
}

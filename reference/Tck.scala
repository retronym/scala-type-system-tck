package tck

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters._
import upickle.default._

/** A rendered type in the TCK canonical normal form (SPEC §4). */
object RenderedType {
  type T = String
}

/** A single conformance query with human-authored ground truth. */
case class ConformanceQuery(lhs: String, rhs: String, expect: Boolean)
object ConformanceQuery { implicit val rw: ReadWriter[ConformanceQuery] = macroRW }

/**
 * A named type expression. Resolved either at the top level of the synthetic
 * corpus object (no anchor) or, when `anchor` is set, textually at the
 * `/*ANCHOR <id>*/` marker in `source.scala` — required for context-dependent
 * types such as `this.type`, self-type references, and `this.SomeMember`.
 */
case class TypeDecl(name: String, expr: String, anchor: Option[String] = None)
object TypeDecl { implicit val rw: ReadWriter[TypeDecl] = macroRW }

/** One corpus entry, loaded from `tck.json` + `source.scala`. */
case class CorpusEntry(
    description: String,
    concepts: List[String],
    types: List[TypeDecl],
    conformance: List[ConformanceQuery],
    baseTypeSeq: List[String]
)
object CorpusEntry { implicit val rw: ReadWriter[CorpusEntry] = macroRW }

/** A loaded corpus entry: its metadata plus its source preamble and directory. */
case class LoadedEntry(id: String, dir: Path, entry: CorpusEntry, source: String)

/** Per-engine actual results for one entry, also the on-disk golden shape. */
case class ConformanceResult(lhs: String, rhs: String, holds: Boolean)
object ConformanceResult { implicit val rw: ReadWriter[ConformanceResult] = macroRW }

case class Golden(
    conformance: List[ConformanceResult],
    baseTypeSeq: Map[String, List[RenderedType.T]],
    // Linearization `baseClasses` (mixin-order sensitive) — the ordered list of
    // base-class names. Distinct from baseTypeSeq order (SPEC §2). Defaulted so
    // older goldens without it still parse.
    baseClasses: Map[String, List[String]] = Map.empty
)
object Golden { implicit val rw: ReadWriter[Golden] = macroRW }

/**
 * The abstract type-system engine. Two implementations are expected:
 *  - [[ScalacEngine]] in this repo (the reference oracle), and
 *  - an IntelliJ-PSI engine in the intellij-scala repo (the system under test),
 * both driven by the same corpus and compared against the same goldens.
 */
trait TckEngine {
  def name: String

  /** Engine-specific resolved context for a corpus entry. */
  type Ctx

  /** Resolve every named type in the entry within the source preamble's scope. */
  def load(loaded: LoadedEntry): Ctx

  /** Is the type named `lhs` a subtype of the type named `rhs`? (SPEC §5) */
  def conforms(ctx: Ctx, lhs: String, rhs: String): Boolean

  /** The base type sequence of the named type, as canonical RenderedTypes (SPEC §3). */
  def baseTypeSeq(ctx: Ctx, typeName: String): List[RenderedType.T]

  /** The linearization `baseClasses` of the named type, as ordered class names (SPEC §2). */
  def baseClasses(ctx: Ctx, typeName: String): List[String]

  /** Compute the full actual result (conformance + baseTypeSeq + baseClasses) for one entry. */
  final def run(loaded: LoadedEntry): Golden = {
    val ctx = load(loaded)
    val conf = loaded.entry.conformance.map(q =>
      ConformanceResult(q.lhs, q.rhs, conforms(ctx, q.lhs, q.rhs)))
    val bts = loaded.entry.baseTypeSeq.map(t => t -> baseTypeSeq(ctx, t)).toMap
    val bcs = loaded.entry.baseTypeSeq.map(t => t -> baseClasses(ctx, t)).toMap
    Golden(conf, bts, bcs)
  }
}

object Corpus {
  /** Walk up from the cwd to find the directory containing `corpus/`. */
  def root(): Path = {
    var dir = Paths.get("").toAbsolutePath
    while (dir != null && !Files.isDirectory(dir.resolve("corpus"))) dir = dir.getParent
    require(dir != null, "could not locate corpus/ directory from cwd")
    dir.resolve("corpus")
  }

  /** Load every entry under `corpus/`, sorted by directory name. */
  def load(): List[LoadedEntry] = {
    val corpus = root()
    Files.list(corpus).iterator().asScala.toList
      .filter(Files.isDirectory(_))
      .sortBy(_.getFileName.toString)
      .map { dir =>
        val id = dir.getFileName.toString
        val entry = read[CorpusEntry](Files.readString(dir.resolve("tck.json")))
        val source = Files.readString(dir.resolve("source.scala"))
        LoadedEntry(id, dir, entry, source)
      }
  }

  def goldenPath(loaded: LoadedEntry): Path = loaded.dir.resolve("expected.json")

  def readGolden(loaded: LoadedEntry): Option[Golden] = {
    val p = goldenPath(loaded)
    if (Files.exists(p)) Some(read[Golden](Files.readString(p))) else None
  }

  def writeGolden(loaded: LoadedEntry, g: Golden): Unit =
    Files.writeString(goldenPath(loaded), write(g, indent = 2) + "\n")
}

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

/** A single type-equivalence (`=:=`) query with human-authored ground truth.
 *  Strictly more discriminating than conformance: a `<:<` row may pass while the
 *  corresponding `=:=` is wrong (singleton/path-dependent equivalence, SCL-21947). */
case class EquivalenceQuery(lhs: String, rhs: String, expect: Boolean)
object EquivalenceQuery { implicit val rw: ReadWriter[EquivalenceQuery] = macroRW }

/**
 * A `baseType` query: the base type of `prefix` at the class of `clazz`
 * (scalac's `prefix baseType clazz.typeSymbol`). Both `prefix` and `clazz` name
 * entries in `types`; `clazz` is used only for its class. Exercises the
 * same-symbol merge (glb/lub) directly.
 */
case class BaseTypeQuery(name: String, prefix: String, clazz: String)
object BaseTypeQuery { implicit val rw: ReadWriter[BaseTypeQuery] = macroRW }

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
    // Type-equivalence (`=:=`) queries. Defaulted to Nil so older entries parse.
    equivalence: List[EquivalenceQuery] = Nil,
    baseTypeSeq: List[String],
    // Term probes: `val __t_<name> = <expr>` spliced (optionally at an anchor),
    // whose *inferred type* is read — exercises member resolution / asSeenFrom /
    // ThisTypeSubstitution. `expr` is a term, `anchor` reuses the marker mechanism.
    termTypes: List[TypeDecl] = Nil,
    // `prefix baseType clazz` queries — the merge primitive asSeenFrom relies on.
    baseTypes: List[BaseTypeQuery] = Nil
)
object CorpusEntry { implicit val rw: ReadWriter[CorpusEntry] = macroRW }

/** A loaded corpus entry: its metadata plus its source preamble and directory. */
case class LoadedEntry(id: String, dir: Path, entry: CorpusEntry, source: String)

/** Per-engine actual results for one entry, also the on-disk golden shape. */
case class ConformanceResult(lhs: String, rhs: String, holds: Boolean)
object ConformanceResult { implicit val rw: ReadWriter[ConformanceResult] = macroRW }

case class EquivalenceResult(lhs: String, rhs: String, holds: Boolean)
object EquivalenceResult { implicit val rw: ReadWriter[EquivalenceResult] = macroRW }

case class Golden(
    conformance: List[ConformanceResult],
    // Type-equivalence (`=:=`) results. Defaulted so older goldens still parse.
    equivalence: List[EquivalenceResult] = Nil,
    baseTypeSeq: Map[String, List[RenderedType.T]],
    // Linearization `baseClasses` (mixin-order sensitive) — the ordered list of
    // base-class names. Distinct from baseTypeSeq order (SPEC §2). Defaulted so
    // older goldens without it still parse.
    baseClasses: Map[String, List[String]] = Map.empty,
    // Inferred type of each term probe (member resolution / asSeenFrom result).
    termTypes: Map[String, RenderedType.T] = Map.empty,
    // Result of each `prefix baseType clazz` query (the merged base type).
    baseTypes: Map[String, RenderedType.T] = Map.empty
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

  /** Is the type named `lhs` equivalent (`=:=`) to the type named `rhs`? */
  def equiv(ctx: Ctx, lhs: String, rhs: String): Boolean

  /** The base type sequence of the named type, as canonical RenderedTypes (SPEC §3). */
  def baseTypeSeq(ctx: Ctx, typeName: String): List[RenderedType.T]

  /** The linearization `baseClasses` of the named type, as ordered class names (SPEC §2). */
  def baseClasses(ctx: Ctx, typeName: String): List[String]

  /** The inferred type of the term probe named `name` (member/asSeenFrom result). */
  def termType(ctx: Ctx, name: String): RenderedType.T

  /** The base type of `prefix` at the class of `clazz` (the merged `pre baseType clazz`). */
  def baseType(ctx: Ctx, prefix: String, clazz: String): RenderedType.T

  /** Compute the full actual result for one entry. */
  final def run(loaded: LoadedEntry): Golden = {
    val ctx = load(loaded)
    val conf = loaded.entry.conformance.map(q =>
      ConformanceResult(q.lhs, q.rhs, conforms(ctx, q.lhs, q.rhs)))
    val eqv = loaded.entry.equivalence.map(q =>
      EquivalenceResult(q.lhs, q.rhs, equiv(ctx, q.lhs, q.rhs)))
    val bts = loaded.entry.baseTypeSeq.map(t => t -> baseTypeSeq(ctx, t)).toMap
    val bcs = loaded.entry.baseTypeSeq.map(t => t -> baseClasses(ctx, t)).toMap
    val tts = loaded.entry.termTypes.map(d => d.name -> termType(ctx, d.name)).toMap
    val bfs = loaded.entry.baseTypes.map(q => q.name -> baseType(ctx, q.prefix, q.clazz)).toMap
    Golden(conf, eqv, bts, bcs, tts, bfs)
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

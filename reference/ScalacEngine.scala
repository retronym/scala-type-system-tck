package tck

import scala.collection.mutable.ListBuffer
import scala.reflect.internal.util.BatchSourceFile
import scala.reflect.io.VirtualDirectory
import scala.tools.nsc.reporters.StoreReporter
import scala.tools.nsc.{Global, Settings}

/**
 * Reference engine: embeds `scala.tools.nsc.Global` and reads conformance and
 * baseTypeSeq directly from the compiler. This is the oracle that goldens are
 * generated from (SPEC §1).
 *
 * Each corpus entry is compiled in a fresh Global (entries all define the same
 * synthetic `__tck.Corpus` module, so they must not share a symbol table).
 */
object ScalacEngine extends TckEngine {
  def name = "scalac-2.13"

  final class Ctx(
      val global: Global,
      val corpusModuleClass: Global#Symbol,
      val types: Map[String, Global#Type]
  )

  private val wrapperPkg = "__tck"
  private val wrapperObj = "Corpus"
  private val queryPrefix = "__q_"

  /** Wrap the preamble and the query type-aliases into a single compilable unit. */
  private def wrap(loaded: LoadedEntry): String = {
    val aliases = loaded.entry.types.map { case (k, expr) =>
      s"  type $queryPrefix$k = $expr"
    }.mkString("\n")
    s"""package $wrapperPkg
       |object $wrapperObj {
       |${loaded.source.linesIterator.map("  " + _).mkString("\n")}
       |$aliases
       |}
       |""".stripMargin
  }

  def load(loaded: LoadedEntry): Ctx = {
    val settings = new Settings
    settings.usejavacp.value = true
    settings.stopAfter.value = List("typer")
    settings.outputDirs.setSingleOutput(new VirtualDirectory("(memory)", None))
    val reporter = new StoreReporter(settings)
    val global = new Global(settings, reporter)

    import global._
    val src = new BatchSourceFile(s"$wrapperObj.scala", wrap(loaded))
    val run = new Run
    run.compileSources(List(src))

    val errors = reporter.infos.filter(_.severity == reporter.ERROR)
    if (errors.nonEmpty)
      sys.error(s"[${loaded.id}] compilation failed:\n" +
        errors.map(i => s"  ${i.pos.line}: ${i.msg}").mkString("\n") +
        "\n--- source ---\n" + wrap(loaded))

    val corpusModuleClass = rootMirror.staticModule(s"$wrapperPkg.$wrapperObj").moduleClass
    val prefix = corpusModuleClass.thisType
    val resolved: Map[String, global.Type] = loaded.entry.types.keys.map { k =>
      val sym = corpusModuleClass.info.decl(newTypeName(queryPrefix + k))
      require(sym != NoSymbol, s"[${loaded.id}] unresolved query type '$k'")
      // typeRef + dealias robustly expands the alias to its RHS with the right prefix.
      k -> typeRef(prefix, sym, Nil).dealias
    }.toMap

    new Ctx(global, corpusModuleClass.asInstanceOf[Global#Symbol], resolved)
  }

  def conforms(ctx: Ctx, lhs: String, rhs: String): Boolean = {
    val g = ctx.global
    val a = ctx.types(lhs).asInstanceOf[g.Type]
    val b = ctx.types(rhs).asInstanceOf[g.Type]
    a <:< b
  }

  def baseTypeSeq(ctx: Ctx, typeName: String): List[RenderedType.T] = {
    val g = ctx.global
    val tp = ctx.types(typeName).asInstanceOf[g.Type]
    tp.baseTypeSeq.toList.map(render(ctx, _))
  }

  // --- canonical rendering (SPEC §4) ---

  def render(ctx: Ctx, tp0: Global#Type): String = {
    val g = ctx.global
    import g._
    val corpusModuleClass = ctx.corpusModuleClass.asInstanceOf[g.Symbol]

    def isCorpusOwned(sym: Symbol): Boolean = {
      var s = sym
      while (s != NoSymbol) {
        if (s == corpusModuleClass) return true
        s = s.owner
      }
      false
    }

    def renderSym(sym: Symbol): String =
      if (isCorpusOwned(sym)) {
        val parts = ListBuffer[String]()
        var s = sym
        while (s != NoSymbol && s != corpusModuleClass) {
          if (s.isType && !s.isPackageClass) parts.prepend(s.name.toString.trim)
          s = s.owner
        }
        parts.mkString("#")
      } else sym.fullName

    def go(t: Global#Type): String = t.asInstanceOf[g.Type].dealias match {
      case TypeRef(_, sym, args) if sym.isRefinementClass =>
        go(sym.info) // expand the refinement structurally
      case TypeRef(_, sym, args) =>
        val base = renderSym(sym)
        if (args.isEmpty) base else s"$base[${args.map(go).mkString(", ")}]"
      case RefinedType(parents, decls) =>
        val ps = parents.map(go).mkString(" with ")
        val ds = decls.toList.sortBy(_.name.toString).map(renderDecl).mkString("; ")
        if (ds.isEmpty) ps else s"$ps { $ds }"
      case SingleType(_, sym)  => s"${renderSym(sym)}.type"
      case ThisType(sym)       => s"${renderSym(sym)}.this.type"
      case TypeBounds(lo, hi)  => s">: ${go(lo)} <: ${go(hi)}"
      case other               => other.toString
    }

    def renderDecl(sym: Symbol): String =
      if (sym.isType) sym.info match {
        case TypeBounds(lo, hi) => s"type ${sym.name} >: ${go(lo)} <: ${go(hi)}"
        case other              => s"type ${sym.name} = ${go(other)}"
      } else s"def ${sym.name}: ${go(sym.info.resultType)}"

    go(tp0)
  }
}

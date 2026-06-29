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
      val types: Map[String, Global#Type],
      val terms: Map[String, Global#Type]
  )

  private val wrapperPkg = "__tck"
  private val wrapperObj = "Corpus"
  private val queryPrefix = "__q_"
  private val termPrefix = "__t_"

  /** `type __q_<name> = <expr>` for a type query. */
  private def alias(d: TypeDecl): String = s"type $queryPrefix${d.name} = ${d.expr}"
  /** `val __t_<name> = <expr>` for a term probe (its inferred type is read). */
  private def probe(d: TypeDecl): String = s"val $termPrefix${d.name} = ${d.expr}"

  /**
   * Wrap the preamble plus the query aliases and term probes into one compilable
   * unit. Anchored decls are spliced at their `/*ANCHOR id*/` marker (so they
   * resolve in that template's context); the rest become top-level members.
   */
  private def wrap(loaded: LoadedEntry): String = {
    val decls: List[(Option[String], String)] =
      loaded.entry.types.map(d => d.anchor -> alias(d)) ++
        loaded.entry.termTypes.map(d => d.anchor -> probe(d))
    val (anchored, topLevel) = decls.partition(_._1.isDefined)

    var body = loaded.source
    anchored.groupBy(_._1.get).foreach { case (id, ds) =>
      val marker = s"/*ANCHOR $id*/"
      require(body.contains(marker), s"[${loaded.id}] anchor '$id' not found in source.scala")
      // Splice on the marker's own line: a leading `;` keeps it a valid statement
      // position whether or not the marker starts the template body.
      body = body.replace(marker, marker + " ; " + ds.map(_._2).mkString(" ; "))
    }

    val topDecls = topLevel.map("  " + _._2).mkString("\n")
    s"""package $wrapperPkg
       |object $wrapperObj {
       |${body.linesIterator.map("  " + _).mkString("\n")}
       |$topDecls
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

    // Recover query aliases (TypeDef) and term probes (ValDef) from the typed
    // tree: their symbols' types are resolved in their lexical context (owner
    // prefix, self-type), wherever spliced.
    val typeSyms = scala.collection.mutable.Map[String, Symbol]()
    val termSyms = scala.collection.mutable.Map[String, Symbol]()
    def collect(t: Tree): Unit = {
      t match {
        case td: TypeDef if td.name.startsWith(queryPrefix) =>
          typeSyms(td.name.toString.stripPrefix(queryPrefix)) = td.symbol
        case vd: ValDef if vd.name.toString.trim.startsWith(termPrefix) =>
          termSyms(vd.name.toString.trim.stripPrefix(termPrefix)) = vd.symbol
        case _ =>
      }
      t.children.foreach(collect)
    }
    run.units.foreach(u => collect(u.body))

    val resolved: Map[String, global.Type] = loaded.entry.types.map { d =>
      val sym = typeSyms.getOrElse(d.name,
        sys.error(s"[${loaded.id}] unresolved query type '${d.name}'"))
      // typeRef through the owner's thisType + dealias expands the alias in-context.
      d.name -> typeRef(sym.owner.thisType, sym, Nil).dealias
    }.toMap

    val terms: Map[String, global.Type] = loaded.entry.termTypes.map { d =>
      val sym = termSyms.getOrElse(d.name,
        sys.error(s"[${loaded.id}] unresolved term probe '${d.name}'"))
      d.name -> sym.info.resultType // the inferred type of `val __t_<name> = <expr>`
    }.toMap

    val corpusModuleClass = rootMirror.staticModule(s"$wrapperPkg.$wrapperObj").moduleClass
    new Ctx(global, corpusModuleClass.asInstanceOf[Global#Symbol], resolved, terms)
  }

  def conforms(ctx: Ctx, lhs: String, rhs: String): Boolean = {
    val g = ctx.global
    val a = ctx.types(lhs).asInstanceOf[g.Type]
    val b = ctx.types(rhs).asInstanceOf[g.Type]
    a <:< b
  }

  def equiv(ctx: Ctx, lhs: String, rhs: String): Boolean = {
    val g = ctx.global
    val a = ctx.types(lhs).asInstanceOf[g.Type]
    val b = ctx.types(rhs).asInstanceOf[g.Type]
    a =:= b
  }

  def baseTypeSeq(ctx: Ctx, typeName: String): List[RenderedType.T] = {
    val g = ctx.global
    val tp = ctx.types(typeName).asInstanceOf[g.Type]
    tp.baseTypeSeq.toList.map(render(ctx, _))
  }

  def baseClasses(ctx: Ctx, typeName: String): List[String] = {
    val g = ctx.global
    val tp = ctx.types(typeName).asInstanceOf[g.Type]
    tp.baseClasses.map(symName(ctx, _))
  }

  def termType(ctx: Ctx, name: String): RenderedType.T =
    render(ctx, ctx.terms(name).asInstanceOf[ctx.global.Type])

  def baseType(ctx: Ctx, prefix: String, clazz: String): RenderedType.T = {
    val g = ctx.global
    val pre = ctx.types(prefix).asInstanceOf[g.Type]
    val clazzSym = ctx.types(clazz).asInstanceOf[g.Type].typeSymbol
    val bt = pre.baseType(clazzSym)
    if (bt == g.NoType) "<none>" else render(ctx, bt)
  }

  /** Render a class symbol: corpus-relative (`Outer#Inner`) or fully-qualified. */
  private def symName(ctx: Ctx, sym0: Global#Symbol): String = {
    val g = ctx.global
    import g._
    val corpusModuleClass = ctx.corpusModuleClass.asInstanceOf[g.Symbol]
    val sym = sym0.asInstanceOf[g.Symbol]
    def isCorpusOwned(s0: Symbol): Boolean = {
      var s = s0
      while (s != NoSymbol) { if (s == corpusModuleClass) return true; s = s.owner }
      false
    }
    if (isCorpusOwned(sym)) {
      val parts = ListBuffer[String]()
      var s = sym
      while (s != NoSymbol && s != corpusModuleClass) {
        if (s.isType && !s.isPackageClass) parts.prepend(s.name.toString.trim)
        s = s.owner
      }
      parts.mkString("#")
    } else sym.fullName
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

    // Render a path-dependent prefix (`X.this`, `p.q`) when it is a corpus-owned
    // this-type or singleton — crucial for distinguishing `Impl.this.Tree` from
    // `Api#Tree` (SCL-21947). Library and trivial corpus-module prefixes -> None.
    def prefixStr(pre: Type): Option[String] = pre match {
      case ThisType(s) if s != corpusModuleClass && isCorpusOwned(s) && !s.isPackageClass =>
        Some(renderSym(s) + ".this")
      case SingleType(p, s) if isCorpusOwned(s) && !s.isPackageClass =>
        val nm = s.name.toString.trim
        Some(prefixStr(p).map(_ + "." + nm).getOrElse(nm))
      case _ => None
    }

    def go(t: Global#Type): String = t.asInstanceOf[g.Type].dealias match {
      case TypeRef(_, sym, args) if sym.isRefinementClass =>
        go(sym.info) // expand the refinement structurally
      case TypeRef(pre, sym, args) =>
        val base = prefixStr(pre) match {
          case Some(p) => s"$p.${sym.name.toString.trim}"
          case None    => renderSym(sym)
        }
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

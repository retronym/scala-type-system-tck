# SLS 2.13 vs scalac: a gap analysis of path-dependent types and the cake

This document maps the type-system operations behind path-dependent types and the cake pattern onto the Scala Language Specification (SLS 2.13). For each operation it records what the SLS specifies, what it leaves open, and what exists only in scalac. The aim is to tell implementers of other Scala type checkers (IntelliJ, Metals and presentation compilers, Scala 3 / TASTy consumers reading Scala 2 signatures) where the SLS is enough and where they have to follow scalac.

The SLS is deliberately declarative. It defines relations and leaves algorithms, approximations and representation choices to the compiler, and that is a legitimate choice. Where this document says "unspecified", it is describing that choice, not a defect. A few places are different: there, a literal reading of the SLS gives a different answer from scalac, and in two of them the SLS answer is unsound. Those are collected in [Candidate spec clarifications](#candidate-spec-clarifications).

**Sources.** The SLS text is `spec/` at scala/scala `2.13.x` (as published at [scala-lang.org/files/archive/spec/2.13](https://scala-lang.org/files/archive/spec/2.13/)). scalac code references are to tag [`v2.13.18`](https://github.com/scala/scala/tree/v2.13.18) (98f40d0). Every example below was compiled with scalac 2.13.18 (`scala-cli compile --scala 2.13 --server=false -Vprint:typer`) and the stated types are copied from the typer output. The IntelliJ column refers to branch `scala-typesystem-tck` of intellij-scala (60333a241f) and [PR #5](https://github.com/retronym/intellij-scala/pull/5) and its [parity notes](https://github.com/retronym/intellij-scala/pull/5#issuecomment-6012224726).

## Summary

| Operation | SLS coverage | SLS | scalac | Where IntelliJ diverged |
|---|---|---|---|---|
| 1. `memberType` (override-aware) | **Specified** for paths (by name); the symbol-based mechanism (`rebind`) is implementation | §3.4 (member bindings), §6.4, §5.1.3–4 | [`Types.memberType`](https://github.com/scala/scala/blob/v2.13.18/src/reflect/scala/reflect/internal/Types.scala#L712), [`rebind`](https://github.com/scala/scala/blob/v2.13.18/src/reflect/scala/reflect/internal/Types.scala#L3980), [`singleType`](https://github.com/scala/scala/blob/v2.13.18/src/reflect/scala/reflect/internal/Types.scala#L4002) | Designators pointed at the declaration, so singleton-typed overrides were invisible (corpus 21) |
| 2. `asSeenFrom` | **Specified** (an algorithm); differs from scalac in two corner cases, one unsound | §3.4 item 2 | [`AsSeenFromMap`](https://github.com/scala/scala/blob/v2.13.18/src/reflect/scala/reflect/internal/tpe/TypeMaps.scala#L417) | Unbounded growth, first-match-wins, missing anchor (corpus 25–27) |
| 3. Base types / `baseTypeSeq` | **Partly**: a set with a strict merge rule; scalac's variance merge, order and depth are unspecified | §3.4 item 1, §5.1, §5.1.2 | [`compoundBaseTypeSeq`](https://github.com/scala/scala/blob/v2.13.18/src/reflect/scala/reflect/internal/BaseTypeSeqs.scala#L192), [`mergePrefixAndArgs`](https://github.com/scala/scala/blob/v2.13.18/src/reflect/scala/reflect/internal/Types.scala#L5061), [`validateBaseTypes`](https://github.com/scala/scala/blob/v2.13.18/src/compiler/scala/tools/nsc/typechecker/RefChecks.scala#L965) | First arm instead of merge; self type missing from `X.this` (corpus 04, 16, 18) |
| 4. `lub` | **Not specified** (explicitly delegated) | §3.5.2 (LUBs and GLBs) | [`GlbLubs.lub`](https://github.com/scala/scala/blob/v2.13.18/src/reflect/scala/reflect/internal/tpe/GlbLubs.scala#L326), [`lubList_x`](https://github.com/scala/scala/blob/v2.13.18/src/reflect/scala/reflect/internal/tpe/GlbLubs.scala#L79), [`lubDepth`](https://github.com/scala/scala/blob/v2.13.18/src/reflect/scala/reflect/internal/Types.scala#L4491) | Prefix of a class seen from a path was normalized away; binary (pairwise) lub |
| 5. Path / singleton equivalence | **Specified** as a relation; how the *type of a path* is computed is items 1 and 7 | §3.1, §3.2.1, §3.5.1, §3.5.2, §6.4, §6.5 | [`isSameSingletonType`](https://github.com/scala/scala/blob/v2.13.18/src/reflect/scala/reflect/internal/tpe/TypeComparers.scala#L162), [`isSubType2`](https://github.com/scala/scala/blob/v2.13.18/src/reflect/scala/reflect/internal/tpe/TypeComparers.scala#L452) | Lenient cake equivalences, since removed (corpus 28) |
| 6. `packedType` / type avoidance | **Specified** (existentials over block locals); scalac packs lazily, with the same results | §6.11, §6.1, §3.2.12, §4.1 | [`Typer.packedType`](https://github.com/scala/scala/blob/v2.13.18/src/compiler/scala/tools/nsc/typechecker/Typers.scala#L4404), [`computeType`](https://github.com/scala/scala/blob/v2.13.18/src/compiler/scala/tools/nsc/typechecker/Typers.scala#L6578) | Widens local singletons only; local classes escape (corpus 23) |
| 7. Self types | **Partly**: the type of `this` is specified; name visibility and spelling of self-type members are not | §5.1, §6.5, §3.2.5, ch. 2 | [`SelfTypeCompleter`](https://github.com/scala/scala/blob/v2.13.18/src/compiler/scala/tools/nsc/typechecker/Namers.scala#L1011), [`ThisType.underlying`](https://github.com/scala/scala/blob/v2.13.18/src/reflect/scala/reflect/internal/Types.scala#L1394) | Spells self-type members after the *declaring* trait, which forces an SLS-style subclass rule in `asSeenFrom` |

### On the PR #5 wording

The PR says: "The SLS defines the concepts involved (linearization, base types, paths and singleton types) and leaves the algorithmic detail to the compiler: how base types are merged when a class is reached through several parents, or exactly when two paths name the same instance." Checked against the text, this is right in spirit but undersells the SLS in two places and is slightly off in one:

- The SLS gives algorithms, not only concepts, for `asSeenFrom` (§3.4), for the base types of each type form (§3.4), and for block types (§6.11). An implementer can follow them directly.
- "How base types are merged" *is* specified, by a rule stricter than scalac's: one of the instances must conform to all the others, otherwise the program is in error. scalac enforces exactly that rule for class definitions (`illegal inheritance; ... inherits different type instances`), but accepts compound types such as `Box[Dog] with Box[Cat]` and merges them variance-wise. The variance merge is what is unspecified.
- "When two paths name the same instance" is specified by §3.5.1 in terms of the *types* of paths. What the SLS leaves open is how a member reached through a self type is named, and therefore which this-type ends up in a path's type (sections 1 and 7).

A more precise sentence would be: *"The SLS specifies these operations declaratively. It leaves to the compiler how a class reached through several parents is merged in a compound type, how lubs are computed, and how members seen through a self type are named, and that last choice decides which `this` a path's type is spelled with."*

## 1. `memberType`: the type of a member seen from a prefix

### What the SLS says

§3.4 defines the member bindings of a type `T`: a binding `d` such that there is a definition `d'` in a base class `C` of `T` and "´d´ results from ´d'´ by replacing every type ´T'´ in ´d'´ by ´T'´ in ´C´ seen from ´T´". §6.4: a selection `r.x` "refers statically to a term member ´m´ of ´r´ that is identified in ´T´ by the name ´x´". Which members a class has, after overriding, is §5.1.3–5.1.4.

That covers two things scalac does:

- **Anchoring at the declaration site.** `asSeenFrom` is applied with the class `C` that contains `d'`. scalac's [`computeMemberType`](https://github.com/scala/scala/blob/v2.13.18/src/reflect/scala/reflect/internal/Types.scala#L719) is `sym.tpeHK.asSeenFrom(this, sym.owner)`. This is the "owner-chain matching" rule of IntelliJ's `ThisTypeSubstitution`, and it is in the spec.
- **Override awareness.** Members are identified by name in the prefix's type, after overriding, so a more specific override is what the path sees.

### What is left open

Nothing semantically. The gap is in *mechanism*. The SLS identifies members by name in the type of the prefix, so a path `p.x` always means "the `x` of whatever `p`'s type is now". An implementation that identifies members by *declaration* (a scalac `Symbol`, an IntelliJ `PsiElement`) has to re-identify the member whenever `asSeenFrom` replaces the prefix. scalac does this in [`rebind`](https://github.com/scala/scala/blob/v2.13.18/src/reflect/scala/reflect/internal/Types.scala#L3980), called by the canonical creators [`singleType`](https://github.com/scala/scala/blob/v2.13.18/src/reflect/scala/reflect/internal/Types.scala#L4002) and, for abstract types, [`typeRef`](https://github.com/scala/scala/blob/v2.13.18/src/reflect/scala/reflect/internal/Types.scala#L4067). It rebinds to the overriding member of the new prefix when that member is stable and not of volatile type. The SLS ensures the rebinding stays a path: "A stable member can only be overridden by a stable member" (§5.1.4), and a value of volatile type "cannot appear in a path" (§3.6).

The underlying type of a singleton is then `pre.memberType(sym).resultType` ([`defineUnderlyingOfSingleType`](https://github.com/scala/scala/blob/v2.13.18/src/reflect/scala/reflect/internal/Types.scala#L1488)), computed from the rebound symbol.

### Example (verified)

```scala
trait A { val x: AnyRef; def get: x.type = x }
trait B extends A { val x: String }
def f(b: B) = b.get.length          // scalac: f: Int
```

`get`'s type `A.this.x.type`, seen from `b.type`, is `b.x.type`. The `x` in it is rebound to `B#x`, whose type is `String`, so `.length` resolves. Without rebinding, the underlying is `AnyRef` and the call fails. The override-object variant (`override object gen extends { val global: Global.this.type } ...`) is corpus 21. The plain-`val` variant is a proposed new entry (see [new TCK entries](#candidate-new-tck-entries)).

**For implementers:** a declaration-keyed type representation must reproduce `rebind` wherever a prefix is substituted. IntelliJ does it in `ScProjectionType.actual`. The PR notes it as "restored in three hand-synced places", which is the risk.

## 2. `asSeenFrom`

### What the SLS says

§3.4 item 2 defines "´T´ in class ´C´ seen from prefix ´S´", given that `S` has a base type `S'#C[...]`, by cases:

- type parameter: "If ´S´ has a base type `´D´[´U_1 , \ldots , U_n´]` … then ´T´ in ´C´ seen from ´S´ is ´U_i´". Otherwise, if `C` is defined in a class `C'`, it is "´T´ in ´C'´ seen from ´S'´".
- this-type: "If ´D´ is a subclass of ´C´ and ´S´ has a type instance of class ´D´ among its base types, then ´T´ in ´C´ seen from ´S´ is ´S´." Otherwise, the same outward step.
- otherwise, the mapping applies to all type components.

### scalac

[`AsSeenFromMap`](https://github.com/scala/scala/blob/v2.13.18/src/reflect/scala/reflect/internal/tpe/TypeMaps.scala#L417) has the same shape. [`thisTypeAsSeen`](https://github.com/scala/scala/blob/v2.13.18/src/reflect/scala/reflect/internal/tpe/TypeMaps.scala#L636) and [`classParameterAsSeen`](https://github.com/scala/scala/blob/v2.13.18/src/reflect/scala/reflect/internal/tpe/TypeMaps.scala#L554) both loop on `(pre baseType clazz).prefix` and `clazz.owner`, which is exactly the SLS's step from `(S, C)` to `(S', C')`. Both stop when the prefix runs out ([`skipPrefixOf`](https://github.com/scala/scala/blob/v2.13.18/src/reflect/scala/reflect/internal/tpe/TypeMaps.scala#L408)), returning the type unchanged. The loop only climbs a finite owner chain and only strips prefixes, so it terminates. The SLS recursion terminates for the same reason.

On termination: the unbounded growth IntelliJ hit (`global.analyzer.global.analyzer…`) is not inherent in the definition. It came from feeding substitution results back into member resolution. A faithful `asSeenFrom` computes from the declared type and the prefix once.

At each step, the match test is [`matchesPrefixAndClass`](https://github.com/scala/scala/blob/v2.13.18/src/reflect/scala/reflect/internal/tpe/TypeMaps.scala#L577): `clazz == candidate && pre.widen.baseTypeIndex(clazz) != -1`. This differs from the SLS in three ways.

**(a) This-types: "subclass of" vs "same class".** The SLS matches `D.this` when `D` is a *subclass* of `C`. scalac 2.13 matches only when `D` *is* the class at the current step. The SLS text matches scalac 2.10's `toPrefix` (`sym isNonBottomSubClass clazz && pre.widen.typeSymbol isNonBottomSubClass sym`, [v2.10.7 Types.scala:4538](https://github.com/scala/scala/blob/v2.10.7/src/reflect/scala/reflect/internal/Types.scala#L4538)). 2.11 rewrote `asSeenFrom` with the stricter test.

For types that scalac builds itself, the two readings appear to agree. scalac spells a this-type after a class on the owner chain of the reference (section 7), so a member of `C` never mentions `D.this` for a proper subclass `D` of `C` that isn't `C` itself or an enclosing class. We could not build a program where they differ: the obvious attempt, `abstract class D extends D#C { class C { def me: D.this.type = D.this } }`, is rejected as an illegal cyclic reference. The difference does matter to an implementation that spells types differently. IntelliJ names a self-type member after its *declaring* trait (`SymbolTable.this.Type` inside `trait Definitions { self: SymbolTable => }`), so it needs the SLS's subclass reading to re-anchor it, which is what the parity notes call "scalac `toPrefix`'s first branch". Corpus 27's comment describes scalac's behaviour in terms of `toPrefix` too. In 2.13 the same answer comes from a different place: scalac infers `foo: Definitions.this.Type` (verified), and that matches at the first step under the strict test.

**(b) Class type parameters: the SLS checks all of `S`'s base types first.** The SLS tests "´S´ has a base type ´D´[…]" before stepping outward. scalac only tests `D` when the walk reaches `D` ([`TypeMaps.scala:564`](https://github.com/scala/scala/blob/v2.13.18/src/reflect/scala/reflect/internal/tpe/TypeMaps.scala#L564)). They differ when an inner class re-extends its outer class with different arguments:

```scala
class D[A](val a: A) { class C extends D[Int](1) { def f: A = D.this.a } }
val d = new D[String]("s")
val c = new d.C
val r = c.f      // scalac 2.13: r: String  (Scala 3 also accepts `val r: String = c.f`)
```

`A` in `f` is the *outer* instance's parameter. Read literally, the SLS finds the base type `D[Int]` of `c.type` at the first step and answers `Int`, and at runtime `c.f` returns the `String` `"s"`. So the literal reading is unsound here, and scalac's owner-chain-first order is the correct one. The Scala 3 spec's `asSeenFrom` uses the same wording ("If `baseType(p, D) = r.D[W_1, ..., W_m]` is defined, then W_i"), and the Scala 3 compiler also answers `String`.

**(c) Unstable prefixes.** The SLS's this-type rule answers "´S´" whether or not `S` is a singleton. scalac returns `S` only when it is stable. Otherwise it creates a fresh existential `_1.type forSome { val _1: S }` ([`captureThis`](https://github.com/scala/scala/blob/v2.13.18/src/reflect/scala/reflect/internal/tpe/TypeMaps.scala#L481)), and `asSeenFrom` abstracts over it ([`Types.scala:689`](https://github.com/scala/scala/blob/v2.13.18/src/reflect/scala/reflect/internal/Types.scala#L689)). Similarly, a singleton whose prefix becomes unstable is widened ([`singleTypeAsSeen`](https://github.com/scala/scala/blob/v2.13.18/src/reflect/scala/reflect/internal/tpe/TypeMaps.scala#L655)).

```scala
class X { def arr: Array[this.type] = Array(this) }
def mk(): X = new X
val a = mk().arr             // scalac: Array[_ <: X with Singleton]
mk().arr(0) = new X          // scalac: error, required: _1.type where val _1: X
```

The literal answer `Array[X]` would accept the second line, which is unsound. Read together with §6.4, though, the SLS gets this right: "For other expressions ´e´, ´e.x´ is typed as if it was `{ val ´y´ = ´e´; ´y´.´x´ }`". With §6.11's block typing that gives `Array[y.type] forSome { val y: X }`, which is scalac's answer. So the gap is only that §3.4 doesn't say it assumes a stable `S`.

**Self types.** `C.this` reached through a self type is covered, although the reasoning crosses three chapters. The base types of `p.type` are those of the type of `p` (§3.4), the type of `C.this` outside stable contexts is the self type (§6.5), and the self type includes the declared self type (§5.1). scalac's `pre.widen` of a `ThisType` is `typeOfThis` ([`ThisType.underlying`](https://github.com/scala/scala/blob/v2.13.18/src/reflect/scala/reflect/internal/Types.scala#L1394)), so `baseTypeIndex` sees the self type. Corpus 16: inside `trait Impl { self: Api => }`, `root` has type `Impl.this.Tree`.

## 3. Base types and `baseTypeSeq`

### What the SLS says

§3.4 item 1 defines the *set* of base types, case by case. The base types of a compound type are the "reduced union": if the multiset contains several instances of the same class, "all those instances are replaced by one of them which conforms to all others. It is an error if no such instance exists." The base types of `p.type` are those of the type of `p`, and the self type enters through §5.1/§6.5.

### scalac

scalac distinguishes two contexts, and only one of them follows the SLS rule.

- **Class definitions: the SLS rule, enforced late.** [`RefChecks.validateBaseTypes`](https://github.com/scala/scala/blob/v2.13.18/src/compiler/scala/tools/nsc/typechecker/RefChecks.scala#L965) keeps, for each base class, the instances that are not subsumed by another, and reports an error if more than one remains. That is the reduced-union rule. It runs after typer, though, and during typing the class's base type sequence already holds a merged entry.
- **Compound types: a variance merge.** [`compoundBaseTypeSeq`](https://github.com/scala/scala/blob/v2.13.18/src/reflect/scala/reflect/internal/BaseTypeSeqs.scala#L192) walks the parents' sequences in lockstep and collects same-class entries into a lazy intersection ([L253](https://github.com/scala/scala/blob/v2.13.18/src/reflect/scala/reflect/internal/BaseTypeSeqs.scala#L253)). On first access it is resolved by [`mergePrefixAndArgs(variants, Contravariant, …)`](https://github.com/scala/scala/blob/v2.13.18/src/reflect/scala/reflect/internal/BaseTypeSeqs.scala#L90), i.e. a glb: the **prefixes are glb'd** ([L5068](https://github.com/scala/scala/blob/v2.13.18/src/reflect/scala/reflect/internal/Types.scala#L5068)), covariant arguments are glb'd and contravariant ones lub'd, and an invariant mismatch becomes an existential bounded by the glb and the lub ([L5118–5130](https://github.com/scala/scala/blob/v2.13.18/src/reflect/scala/reflect/internal/Types.scala#L5118)). The merged entry fails only if this returns `NoType` ("no common type instance of base types … exists").

Verified:

```scala
trait L extends Box[Dog]; trait R extends Box[Cat]
trait LR  extends L with R                         // error: inherits different type instances of trait Box
trait LRB extends L with R with Box[Dog with Cat]  // OK: one instance conforms to all others
def boxes(x: Box[Dog] with Box[Cat]) = x.get       // OK: boxes(...): Cat with Dog
def sinks(x: Sink[Dog] with Sink[Cat]) = x.put _  // OK: Animal => Unit
def inter(x: I[Dog] with I[Cat]) = x.get           // OK (I invariant): Animal, via the existential
def inter2(x: I[Dog] with I[Cat]) = pick(x)        // OK: pick[Dog](x), inference sees the first parent
```

**Conformance does not use the merged base type.** For a compound type on the left, scalac's conformance checks the components one by one ([`fourthTry`](https://github.com/scala/scala/blob/v2.13.18/src/reflect/scala/reflect/internal/tpe/TypeComparers.scala#L623): `parents exists (_ <:< tp2)`). It does not look at the merged base type, so scalac is inconsistent with itself here (verified):

```scala
def f(x: Box[Dog] with Box[Cat]) = {
  val g = x.get                    // Cat with Dog: member types use the merged base type
  val b: Box[Dog with Cat] = x     // error: type mismatch; neither component conforms
}
```

This follows the SLS's conformance rule for compound types ("conforms to each of its component types"), which never mentions the reduced union. The SLS avoids the inconsistency only by declaring the type erroneous. An implementer who merges base types (as IntelliJ now does) must still not use the merge for `<:`. Corpus 18 pins this down: `(L with R) <: Box[Dog with Cat]` is false.

So for compound types the SLS says "error" and scalac merges. The merge rules (glb of prefixes, variance per argument, existential for an invariant mismatch) are not in the SLS. The Scala 3 spec's `baseType` does specify a merge (`meet`/`join`: `&` for covariant, `|` for contravariant, `=:=` required for invariant arguments and for prefixes). That is prior art for a clarification, although it is not what scalac 2 does for prefixes or invariant arguments.

**Order.** The SLS has a set. scalac has a sequence sorted by [`Symbol.isLess`](https://github.com/scala/scala/blob/v2.13.18/src/reflect/scala/reflect/internal/Symbols.scala#L1989) (base-class count, then symbol id), not by linearization (SPEC.md §2). The order shows up in the component order of merged intersections (`Cat with Dog` above). That order is invisible to conformance, because scalac checks invariant arguments by mutual `<:<` ([`isSubArgs`](https://github.com/scala/scala/blob/v2.13.18/src/reflect/scala/reflect/internal/Types.scala#L4761)). It is visible to `=:=`, because [`isSameType2`](https://github.com/scala/scala/blob/v2.13.18/src/reflect/scala/reflect/internal/tpe/TypeComparers.scala#L253) compares refined-type parents pairwise. Verified: `Cat with Dog =:= Dog with Cat` is false, and `Inv[Cat with Dog] <:< Inv[Dog with Cat]` is true. The SLS is order-sensitive in both places: two compound types are equivalent only if their components "occur in the same order" (§3.5.1), and an invariant argument must be equivalent (§3.5.2). So by the letter of the SLS, `val w: Inv[Dog with Cat] = (??? : Inv[Cat with Dog])` is ill-typed, and scalac accepts it.

**Self types.** The base types of `X.this` include those of the self type, because `ThisType.underlying` is `typeOfThis`. The SLS defines the self type as "the greatest lower bound of ´T´ and ´C´", and a glb is not unique (§3.5.2 says so). scalac picks `C with T`, or `T` alone when `T` already conforms to `C` ([`SelfTypeCompleter`](https://github.com/scala/scala/blob/v2.13.18/src/compiler/scala/tools/nsc/typechecker/Namers.scala#L1015)). Verified: `trait Impl { self: Api => def me = this }` gives `me: Impl with Api`, and inside `trait Definitions { self: SymbolTable => }` (where `SymbolTable extends Definitions`) `this` has type `SymbolTable`. Corpus 04 has `AnimalBox with Animal` at the head of the sequence.

**Depth.** `BaseTypeSeq` depth bounds and the approximation they imply are implementation only (SPEC.md §3.3).

### Note for the TCK: corpus 18 is not a legal program

`18-multipath-base-type` declares `trait LR extends L with R` with `L extends Box[Dog]`, `R extends Box[Cat]` (and the `Sink` analogue). scalac rejects both at refchecks. The reference engine stops after typer, so the goldens (`Box[Cat with Dog]`, `Sink[Animal]`) record typer's provisional merge for a program that does not compile. All other corpus preambles compile cleanly through the full compiler (checked by wrapping each `source.scala` in an object). Suggested fix: express the merge with compound types (`Box[Dog] with Box[Cat]`), which is legal and exercises the same `mergePrefixAndArgs` path, and keep a class-template variant that adds `with Box[Dog with Cat]`. A cheap guard against this happening again would be for the reference engine to run through `refchecks` and fail on errors. For the same reason, "`Box[Dog]` and `Box[Cat]` merge to `Box[Dog with Cat]`" in the PR description holds for compound types. For class parents, scalac reports an error.

## 4. Least upper bound

### What the SLS says

§3.5.2 defines lub and glb from the conformance preorder, and says they may not exist ("A[Any], A[A[Any]], A[A[A[Any]]], ... form a descending sequence of upper bounds") or may not be unique. The compiler "is free to reject a term which has a type specified as a least upper or greatest lower bound, and that bound would be more complex than some compiler-set limit", and "free to pick any one of them". A footnote describes the limit as "at most two deeper than the maximum nesting level of the operand types". The SLS delegates lub deliberately and explicitly. That is the right call given that the general problem has no solution, but it leaves implementers with only scalac to match.

### scalac

[`lub(ts, depth)`](https://github.com/scala/scala/blob/v2.13.18/src/reflect/scala/reflect/internal/tpe/GlbLubs.scala#L326):

1. Drops operands that conform to another one ([`elimSub`](https://github.com/scala/scala/blob/v2.13.18/src/reflect/scala/reflect/internal/tpe/GlbLubs.scala#L245)).
2. **n-ary, over base type sequences.** [`lubList_x`](https://github.com/scala/scala/blob/v2.13.18/src/reflect/scala/reflect/internal/tpe/GlbLubs.scala#L79) walks the sequences of *all* operands at once. Wherever every operand's head has the same class, it emits `mergePrefixAndArgs(heads, Covariant, depth)`: prefixes are lub'd and arguments merged by variance. Base type sequence elements are already seen from the operand's prefix (§3.4), so a lub of path-dependent types keeps the path.
3. [`spanningTypes`](https://github.com/scala/scala/blob/v2.13.18/src/reflect/scala/reflect/internal/tpe/GlbLubs.scala#L187) keeps the minimal candidates, and their intersection is refined with the common members (`lub1`).
4. Depth is [`lubDepth`](https://github.com/scala/scala/blob/v2.13.18/src/reflect/scala/reflect/internal/Types.scala#L4491) (`lubDepthAdjust` of the types' depth and their base type sequences' depth). That is not the footnote's "two deeper" rule. When depth runs out, an argument merge gives up with `NoType` and the candidate is dropped.

Verified examples:

```scala
trait G { class Tree }; val a, b: G
if (c) new a.Tree else new b.Tree                                 // G#Tree  (prefixes a.type, b.type lub to G)

trait Symbols { self: SymbolTable => abstract class Symbol; class TypeSymbol extends Symbol; class TermSymbol extends Symbol }
trait Use { val global: SymbolTable
  def l(c: Boolean) = if (c) (null: global.TypeSymbol) else (null: global.TermSymbol) }  // Use.this.global.Symbol

class A[+T]; class B extends A[B]; class C extends A[C]
if (c) new B else new C                                           // A[A[Object]]  (the SLS's own example)
```

The second is the cake shape behind most of IntelliJ's remaining `Typers.scala` false errors: `BoundsUtil` normalized `global.TypeSymbol` to its declaration-site type before walking base classes and got `Symbols.this.Symbol`.

**lub is not associative.** Because of depth limits and refinement construction, folding pairwise is not the same as the n-ary lub. Verified (types abbreviated, `I = immutable.Iterable[Int] with Int => AnyVal with Equals`):

```scala
def nary(i: Int) = i match { case 0 => List(1); case 1 => Vector(2); case _ => Set(3) }
// I { def iterableFactory: IterableFactory[[_] Iterable[_] with Int with _ => Any with Equals] }
def foldL(c1: Boolean, c2: Boolean) = if (c1) (if (c2) List(1) else Vector(2)) else Set(3)
// I { def iterableFactory: IterableFactory[[_] Iterable[_] with Int with _ => Any with Equals { def iterableFactory: ... }] }
```

A `match` lubs its cases n-ary. A nested `if` lubs pairwise in the source's nesting. IntelliJ's `BoundsUtil.lub` is binary, so it can match scalac on `match` and `List(...)` only by also being n-ary there.

## 5. Path and singleton equivalence

### What the SLS says

Paths are `C.this`, `p.x` for a stable member `x`, and `super` selections (§3.1). `p.type` denotes the value of `p` (§3.2.1). Equivalence (§3.5.1): "If a path ´p´ has a singleton type `´q´.type`, then `´p´.type ´\equiv q´.type`", plus `O.this.type ≡ p.type` for a static path `p` to object `O`. Conformance (§3.5.2): "A singleton type `´p´.type` conforms to the type of the path ´p´", and "A type projection `´T´#´t´` conforms to `´U´#´t´` if ´T´ conforms to ´U´".

### scalac

This matches closely:

- [`isSameSingletonType`](https://github.com/scala/scala/blob/v2.13.18/src/reflect/scala/reflect/internal/tpe/TypeComparers.scala#L162) follows each side's chain of singleton underlyings and compares the ends. That is the §3.5.1 rule applied transitively.
- Two singletons conform if they are `=:=` or the left one's underlying conforms ([`TypeComparers.scala:455`](https://github.com/scala/scala/blob/v2.13.18/src/reflect/scala/reflect/internal/tpe/TypeComparers.scala#L455)).
- Two `TypeRef`s with the same symbol conform when their prefixes do ([L478](https://github.com/scala/scala/blob/v2.13.18/src/reflect/scala/reflect/internal/tpe/TypeComparers.scala#L478)), which is the projection rule. So `p.C <: q.C` exactly when `p.type <: q.type`.
- `ThisType`s are equal only for the same class ([L257](https://github.com/scala/scala/blob/v2.13.18/src/reflect/scala/reflect/internal/tpe/TypeComparers.scala#L257)).
- Implementation-only extras: unifying prefixes for different symbols with the same name when a prefix is existential ([`isUnifiable`](https://github.com/scala/scala/blob/v2.13.18/src/reflect/scala/reflect/internal/tpe/TypeComparers.scala#L53)), and the several representations of a module ([L227–237](https://github.com/scala/scala/blob/v2.13.18/src/reflect/scala/reflect/internal/tpe/TypeComparers.scala#L227)).

Cake cases, verified:

```scala
trait Analyzer { val global: Global }
abstract class Global { class Tree; val analyzer: Analyzer { val global: Global.this.type } }
def f(a: HasGlobal) = {
  implicitly[a.global.analyzer.global.type =:= a.global.type]   // OK
  implicitly[a.global.analyzer.global.Tree =:= a.global.Tree]   // OK
}
```

This follows from the SLS: by §3.4 the type of the path `a.global.analyzer.global` is `Global.this.type` seen from `a.global.type`, which is `a.global.type`, and §3.5.1 then applies. In the other direction, `Types.this` and `SymbolTable.this` in a cake are *never* equivalent, either in the SLS (no rule relates two this-types) or in scalac (corpus 28). The cake still type-checks because both sides of an override are spelled with the same `this`. For the PR's example (`api.Types` / `internal.Types` with `RefinedTypeExtractor.unapply`), scalac's typer prints both signatures as `unapply(tpe: Types.this.RefinedType): Types.this.Type`, and the override check compares them as seen from one class. IntelliJ's earlier `sameThisInstance` leniency was compensating for a spelling difference (section 7), not for an equivalence the SLS omits.

**What is left open** is not equivalence but the inputs to it: which member a path selects (section 1) and which this-type a member's type is spelled with (section 7). One small mismatch is described in section 3: scalac's `=:=` on compound types is order-sensitive like SLS ≡, but invariant arguments are checked by mutual conformance, not ≡.

## 6. `packedType` / type avoidance

### What the SLS says

Here the SLS is precise. §6.11: "The type of a block `´s_1´; ´\ldots´; ´s_n´; ´e´` is `´T´ forSome {´\,Q\,´}`", where `Q` binds every locally defined name free in `T`. It gives the clause for each kind: `val x: T` becomes `val x: T`, a local class `c` becomes `type c <: T` with "´T´ the least class type or refinement type which is a proper supertype of the type ´c´", objects become `val x: T`, and type aliases become `type t >: T <: T`. The §3.2.12 simplification rules then remove the quantifier where they can. §6.1 defines the packed type for skolems, and §4.1 says an omitted value type is "the packed type of expression ´e´". This is more precise than the Scala 3 spec, which says type avoidance "is currently not defined in this specification".

### scalac

scalac reaches the same types by a different route. [`typedBlock`](https://github.com/scala/scala/blob/v2.13.18/src/compiler/scala/tools/nsc/typechecker/Typers.scala#L2635) gives a block the unpacked type of its result expression. Packing happens where a type is *inferred*: [`computeType`](https://github.com/scala/scala/blob/v2.13.18/src/compiler/scala/tools/nsc/typechecker/Typers.scala#L6578) for a `val`/`def` without a type, function literal bodies ([L3299](https://github.com/scala/scala/blob/v2.13.18/src/compiler/scala/tools/nsc/typechecker/Typers.scala#L3299)), and the branch comparison for `if` ([L4913](https://github.com/scala/scala/blob/v2.13.18/src/compiler/scala/tools/nsc/typechecker/Typers.scala#L4913)). [`packedType`](https://github.com/scala/scala/blob/v2.13.18/src/compiler/scala/tools/nsc/typechecker/Typers.scala#L4404) collects the symbols owned by the block (transitively through their bounds) and calls `existentialAbstraction`, which applies the simplification rules.

Verified results all agree with §6.11:

```scala
class Tree { def thisTree: this.type = this }; class Ref[A](val a: A); class Base
val foo = { val X: Tree = null; X.thisTree }              // Tree                         (corpus 23)
val inv = { val X: Tree = new Tree; new Ref[X.type](X) }   // Ref[_ <: Tree with Singleton]
val cls = { class C extends Base; new C }                  // Base                         (SLS's example)
val ref = { class C extends Base; new Ref(new C) }         // Ref[_ <: Base]               (SLS's example)
val lc  = { class C { def me: this.type = this }; new C().me }  // AnyRef{def me: this.type}
```

The `lc` result shows the "least class type or refinement type" clause working: a refinement. One small difference from the letter: for `foo`, the simplification rules give `Tree with Singleton` (`val X: Tree` stands for `type t <: Tree with Singleton`). scalac reports `Tree`, even for a `final val`, because definition types are widened. §4.1 doesn't say this.

For implementers, §6.11 is enough to follow directly. IntelliJ's gap ("`ScBlock.avoidLocalSingletons`" widens local singletons, local classes escape) is a gap against the spec as much as against scalac. Widening alone is also wrong in invariant positions: `inv` must be `Ref[_ <: Tree with Singleton]`, not `Ref[Tree]`.

## 7. Self types

### What the SLS says

§5.1: "If a type ´T´ is given for the formal self parameter, ´S´ is the greatest lower bound of ´T´ and ´C´ … Inside the template, the type of `this` is assumed to be ´S´." §6.5: `C.this` has type `C.this.type` when a stable type is expected or as a selection prefix, "otherwise it is the self type of class ´C´". §3.2.5: "the unqualified type name ´t´ where ´t´ is bound in some class, object, or package ´C´ is taken as a shorthand for ´C.´`this.type#`´t´". Chapter 2 lists where names come from: "definitions and declarations, inheritance, import clauses, or package clauses".

### What is left open

1. **Visibility.** Nothing in chapter 2 makes the self type's members visible by simple name inside the template. The self type is not inherited, imported or defined there. scalac resolves simple names against the members of the enclosing class's `this`, whose type includes the self type, and the whole cake pattern depends on that.
2. **Spelling.** §3.2.5 expands `t` to `C.this.type#t` "where ´t´ is bound in" `C`. Inside `trait Definitions { self: SymbolTable => }`, `Symbol` is bound in `SymbolTable` (or `Symbols`), not in `Definitions`, and `SymbolTable.this` is not a path at that point (§3.1's `C.this` requires an enclosing `C`). scalac spells it after the **using** class: `Definitions.this.Symbol`. This is well-formed, and §3.4 gives it the right meaning, since the base types of `Definitions.this.type` include `SymbolTable` through the self type. Verified: `def sym: Symbol` prints as `def sym: Definitions.this.Symbol`, and the inferred `def foo = NoSymbol.tpe` is `Definitions.this.Type`. Seen from `g: Global`, both become `g.Symbol` and `g.Type`.
3. **Which glb.** scalac's self type is `C with T` (class first), or `T` when `T <: C` ([`Namers.scala:1015`](https://github.com/scala/scala/blob/v2.13.18/src/compiler/scala/tools/nsc/typechecker/Namers.scala#L1015)).

### Why it matters

The spelling choice decides the rest. With scalac's spelling, every this-type in a member's declared type belongs to a class on the reference's owner chain, which is why 2.13's `asSeenFrom` can use exact-class matching (section 2a), and why both sides of a cake override come out with the same `this` (section 5). IntelliJ spells these after the declaring trait (`Symbols.this.Symbol`). That needs the SLS's subclass rule plus a self-type step in the owner-chain walk (the self-type allowance in the PR's owner-chain matching rule), and it was the root of the `Types.this =:= SymbolTable.this` leniency the PR has since removed. An implementer who adopts scalac's spelling (the using class) avoids that whole family of special cases.

## Candidate spec clarifications

Ranked by usefulness: soundness first, then rules that implementers have been observed to get wrong.

1. **§3.4, `asSeenFrom` on class type parameters: walk outward first.** Replace "If ´S´ has a base type `´D´[…]`" with a test made only at the step where the enclosing class is `D`, as scalac does. The current wording gives `Int` for `c.f` in [2(b)](#2-asseenfrom), which is unsound. The Scala 3 spec has the same wording and would benefit from the same fix.
2. **§3.4, base types of compound types: describe the merge, or limit the error to templates.** As written, `Box[Dog] with Box[Cat]` is an error, and scalac accepts it with base type `Box[Cat with Dog]`. Either specify the merge (as the Scala 3 spec does with `meet`, noting scalac 2's glb of prefixes and existential for invariant mismatches), or restrict the "it is an error" sentence to class templates, where scalac enforces it.
3. **Chapter 2 / §5.1 / §3.2.5, self-type members.** State that members of the self type are visible by simple name in the template, and that such a name expands to `C.this.type#t` where `C` is the class whose template contains the reference. This one sentence fixes the spelling that §§2, 5 and 7 depend on.
4. **§3.4, stable prefixes.** Say that `S` is assumed stable, with a pointer to §6.4's `{ val y = e; y.x }` rewriting for other prefixes. This makes the this-type rule's answer always a singleton (2(c)).
5. **§3.5.2, invariant arguments.** Either require mutual conformance rather than ≡ for invariant arguments (scalac's behaviour), or relax compound-type equivalence in §3.5.1 so that component order doesn't matter. As written, `Inv[Cat with Dog]` and `Inv[Dog with Cat]` are unrelated.
6. **§3.5.2 footnote on lub depth.** The "two deeper" description no longer matches `lubDepth`. Dropping the specific number, or describing it as compiler-defined, would be accurate. It may also be worth noting that the chosen lub is not associative, so `if`-nesting is observable.
7. **§3.4, this-type rule wording.** "´D´ is a subclass of ´C´" is the 2.10 formulation. It is harmless given (3), but if (3) is adopted, "´D´ is ´C´" would match 2.13 and make the termination argument more obvious.
8. **§4.1, widening of inferred definition types.** Mention that singleton types (and `with Singleton`) are widened when the type of a non-final definition is inferred. Low priority.

## Candidate new TCK entries

Each entry follows the existing format (`source.scala`, `tck.json` with `types`, `conformance`, `equivalence`, `termTypes`, `baseTypes`). Expected values are the verified scalac answers above.

| # | Name | Probes (scalac answer) | Pins down |
|---|---|---|---|
| fix 18 | `18-multipath-base-type` | Recast with compound types `Box[Dog] with Box[Cat]` (`baseType` = `Box[Cat with Dog]`), `Sink[Dog] with Sink[Cat]` (`Sink[Animal]`); class-template variant `LRB extends L with R with Box[Dog with Cat]` | The variance merge, on a legal program |
| 29 | `asf-outer-type-param` | `termTypes`: `c.f` → `String` | §2(b): owner chain before base types |
| 30 | `asf-unstable-prefix` | `termTypes`: `mk().arr` → `Array[_ <: X with Singleton]`; `mk().self` → `X` | §2(c): `captureThis`. Needs the existential rendering (SPEC §7) |
| 31 | `singleton-rebind-val` | with `val b: B` at an anchor: `termTypes` `b.get` → `b.x.type`; conformance `b.x.type <: String` = true | §1: `rebind` for a plain `val` override (complements 21) |
| 32 | `compound-invariant-merge` | `termTypes`: `x.get` → `Animal`, `pick(x)` → `Dog` for `x: I[Dog] with I[Cat]` | §3: existential merge for invariant arguments |
| 33 | `block-local-existentials` | `termTypes`: `inv` → `Ref[_ <: Tree with Singleton]`, `ref` → `Ref[_ <: Base]`, `cls` → `Base`, `lc` → `AnyRef{def me: this.type}` | §6: type avoidance beyond singletons |
| 34 | `cake-lub-prefix` | `termTypes`: the cake `l(c)` → `global.Symbol`; `if (c) new a.Tree else new b.Tree` → `G#Tree` | §4: lub keeps the path; prefix lub |
| 35 | `intersection-order` | conformance `Inv[Cat with Dog] <: Inv[Dog with Cat]` = true (both ways); equivalence `=:=` = false; `Cat with Dog =:= Dog with Cat` = false | §3: order visible to `=:=`, invisible to `<:` |
| 36 | `lub-associativity` | `termTypes`: `nary` vs `foldL` (different refinements) | §4: n-ary vs pairwise lub |
| 37 | `self-type-spelling` | `termTypes` at an anchor inside `trait Definitions { self: SymbolTable => }`: `sym` → `Definitions.this.Symbol`, `this` → `SymbolTable`; in `Impl { self: Api => }`, `this` → `Impl with Api` | §7: spelling after the using class; choice of glb |

Sketch for 29:

```scala
// Concept: asSeenFrom of an OUTER class type parameter through an inner class that
// re-extends the outer class with a different argument. scalac walks the owner chain
// (C, then D) before consulting D's base types, so `A` is the outer instance's
// argument (String), not the inner re-extension's (Int). SPEC-GAPS.md §2(b).
class D[A](val a: A) { class C extends D[Int](1) { def f: A = D.this.a } }
object Use {
  val d = new D[String]("s")
  val c = new d.C
  /*ANCHOR inUse*/
}
```

```json
{
  "description": "asSeenFrom of an outer class type parameter through an inner class re-extending the outer (SPEC-GAPS §2b): c.f is String, not Int.",
  "concepts": ["asSeenFrom", "type-parameter", "inner-class", "owner-chain"],
  "types": [],
  "conformance": [],
  "baseTypeSeq": [],
  "termTypes": [ { "name": "cf", "expr": "c.f", "anchor": "inUse" } ]
}
```

Corpus 27's comment should also be updated: in 2.13 the re-anchoring comes from `foo` being inferred as `Definitions.this.Type` and then matched exactly by `thisTypeAsSeen`. `toPrefix`'s subclass branch is 2.10 code (section 2(a)).

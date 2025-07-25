package com.goodcover.tagless

import scala.annotation.experimental
import scala.quoted.*

private[tagless] object DeriveMacros:
  // Unfortunately there is no flag for default parameters.
  private val defaultRegex = ".*\\$default\\$\\d+".r

@experimental
private[tagless] class DeriveMacros[Q <: Quotes](using val q: Q):
  import q.reflect.*

  type Transform = PartialFunction[(Symbol, TypeRepr, Term), Term]
  type Combine   = PartialFunction[(Symbol, TypeRepr, Seq[Term]), Term]

  final class ReplaceTerm(from: Term, to: Term) extends TreeMap:
    override def transformTerm(tree: Term)(owner: Symbol): Term =
      if tree == from then to else super.transformTerm(tree)(owner)

  private val nonOverridableOwners =
    TypeRepr.of[(Object, Any, AnyRef, AnyVal)].typeArgs.map(_.typeSymbol).toSet

  private val nonOverridableFlags =
    List(Flags.Final, Flags.Artifact, Flags.Synthetic, Flags.Mutable, Flags.Param)

  // TODO: This is a hack - replace with `Symbol.newTypeAlias` on Scala 3.6+
  private def newTypeAlias(owner: Symbol, name: String, flags: Flags, tpe: TypeRepr, privateIn: Symbol): Symbol =
    try
      val ctx             = q.getClass.getMethod("ctx").invoke(q)
      val aliasClass      = Class.forName("dotty.tools.dotc.core.Types$TypeAlias")
      val symbolsClass    = Class.forName("dotty.tools.dotc.core.Symbols$")
      val decoratorsClass = Class.forName("dotty.tools.dotc.core.Decorators$")
      val alias           = aliasClass.getConstructors.head.newInstance(tpe)
      val symbols         = symbolsClass.getDeclaredField("MODULE$").get(null)
      val decorators      = decoratorsClass.getDeclaredField("MODULE$").get(null)
      val toTypeName      = decorators.getClass.getMethods.find(_.getName == "toTypeName").get
      val newSymbol       = symbols.getClass.getMethods.find(_.getName == "newSymbol").get
      val typeName        = toTypeName.invoke(decorators, name)
      val nestingLevel    = ctx.getClass.getMethod("nestingLevel").invoke(ctx)
      val sym             = newSymbol.invoke(symbols, ctx, owner, typeName, flags, alias, privateIn, 0, nestingLevel)
      sym.asInstanceOf[Symbol]
    catch
      case _: Exception =>
        report.errorAndAbort(s"Not supported: type $name in $owner")

  extension (xf: Transform)
    def transformRepeated(method: Symbol, tpe: TypeRepr, arg: Term): Tree =
      val x            = Symbol.freshName("x")
      val resultType   = xf(method, tpe, Select.unique(arg, "head")).tpe
      val lambdaType   = MethodType(x :: Nil)(_ => tpe :: Nil, _ => resultType)
      val lambda       = Lambda(method, lambdaType, (_, xs) => xf(method, tpe, xs.head.asExpr.asTerm))
      val result       = Select.overloaded(arg, "map", resultType :: Nil, lambda :: Nil)
      val repeatedType = defn.RepeatedParamClass.typeRef.appliedTo(resultType)
      Typed(result, TypeTree.of(using repeatedType.asType))

    def transformArg(method: Symbol, paramAndArg: (Definition, Tree)): Tree =
      paramAndArg match
        case (param: ValDef, arg: Term) =>
          val paramType = param.tpt.tpe.widenParam
          if !xf.isDefinedAt(method, paramType, arg) then arg
          else if !param.tpt.tpe.isRepeated then xf(method, paramType, arg)
          else xf.transformRepeated(method, paramType, arg)
        case (_, arg)                   => arg

  extension (term: Term)
    def appliedToAll(argss: List[List[Tree]]): Term =
      argss.foldLeft(term): (term, args) =>
        val typeArgs = for case arg: TypeTree <- args yield arg
        val termArgs = for case arg: Term <- args yield arg
        if typeArgs.isEmpty then term.appliedToArgs(termArgs)
        else term.appliedToTypeTrees(typeArgs)

    def call(method: Symbol)(argss: List[List[Tree]]): Term = argss match
      case args1 :: args2 :: argss if !args1.exists(_.isExpr) && args2.forall(_.isExpr) =>
        val typeArgs = for case arg: TypeTree <- args1 yield arg.tpe
        val termArgs = for case arg: Term <- args2 yield arg
        Select.overloaded(term, method.name, typeArgs, termArgs).appliedToAll(argss)
      case args :: argss if !args.exists(_.isExpr)                                      =>
        val typeArgs = for case arg: TypeTree <- args yield arg.tpe
        Select.overloaded(term, method.name, typeArgs, Nil).appliedToAll(argss)
      case args :: argss if args.forall(_.isExpr)                                       =>
        val termArgs = for case arg: Term <- args yield arg
        Select.overloaded(term, method.name, Nil, termArgs).appliedToAll(argss)
      case argss                                                                        =>
        term.select(method).appliedToAll(argss)

    def replace(from: Expr[?], to: Expr[?]): Term =
      ReplaceTerm(from.asTerm, to.asTerm).transformTerm(term)(Symbol.spliceOwner)

  extension (expr: Expr[?])
    def transformTo[A: Type](
      args: Transform = PartialFunction.empty,
      body: Transform = PartialFunction.empty
    ): Expr[A] =
      val term = expr.asTerm

      def transformDef(method: DefDef)(argss: List[List[Tree]]): Option[Term] =
        val sym      = method.symbol
        val delegate = term.call(sym):
          for (params, xs) <- method.paramss.zip(argss)
          yield for paramAndArg <- params.params.zip(xs)
          yield args.transformArg(sym, paramAndArg)
        Some(body.applyOrElse((sym, method.returnTpt.tpe, delegate), _ => delegate))

      def transformVal(value: ValDef): Option[Term] =
        val sym      = value.symbol
        val delegate = term.select(sym)
        Some(body.applyOrElse((sym, value.tpt.tpe, delegate), _ => delegate))

      Some(term).newClassOf[A](transformDef, transformVal)

  extension (exprs: Seq[Expr[?]])
    def combineTo[A: Type](
      args: Seq[Transform] = exprs.map(_ => PartialFunction.empty),
      body: Combine = PartialFunction.empty
    ): Expr[A] =
      val terms = exprs.map(_.asTerm)

      def combineDef(method: DefDef)(argss: List[List[Tree]]): Option[Term] =
        val sym       = method.symbol
        val delegates = terms
          .lazyZip(args)
          .map: (term, xf) =>
            term.call(sym):
              for (params, args) <- method.paramss.zip(argss)
              yield for paramAndArg <- params.params.zip(args)
              yield xf.transformArg(sym, paramAndArg)
        Some(body.applyOrElse((sym, method.returnTpt.tpe, delegates), _ => delegates.head))

      def combineVal(value: ValDef): Option[Term] =
        val sym       = value.symbol
        val delegates = terms.map(_.select(sym))
        Some(body.applyOrElse((sym, value.tpt.tpe, delegates), _ => delegates.head))

      terms.headOption.newClassOf[A](combineDef, combineVal)

  extension (sym: Symbol)
    def privateIn: Symbol =
      sym.privateWithin.fold(Symbol.noSymbol)(_.typeSymbol)

    def overrideKeeping(flags: Flags*): Flags =
      flags.iterator.filter(sym.flags.is).foldLeft(Flags.Override)(_ | _)

    // TODO: Handle accessibility.
    def overridableMembers(delegate: Option[Term]): List[Symbol] =
      val typeAliases = for
        member <- sym.typeMembers
        if member.isTypeDef
        tpe     = delegate match
                    case Some(delegate) => TypeSelect(delegate, member.name).tpe
                    case None           => report.errorAndAbort(s"Not supported: $member in $sym")
      yield member -> tpe

      val cls        = This(sym).tpe
      val aliases    = typeAliases.toMap
      val (from, to) = typeAliases.unzip

      for
        member <- List.concat(sym.typeMembers, sym.fieldMembers, sym.methodMembers)
        if !member.isNoSymbol
        if !member.isClassConstructor
        if !nonOverridableFlags.exists(member.flags.is)
        if !nonOverridableOwners.contains(member.owner)
        if !DeriveMacros.defaultRegex.matches(member.name)
      yield
        if member.isTypeDef then
          val flags = member.overrideKeeping(Flags.Infix)
          newTypeAlias(sym, member.name, flags, aliases(member), member.privateIn)
        else if member.isValDef then
          val tpe   = cls.memberType(member).substituteTypes(from, to)
          val flags = member.overrideKeeping(Flags.Lazy)
          Symbol.newVal(sym, member.name, tpe, flags, member.privateIn)
        else if member.isDefDef then
          val tpe   = cls.memberType(member).substituteTypes(from, to)
          val flags = member.overrideKeeping(Flags.ExtensionMethod, Flags.Infix)
          Symbol.newMethod(sym, member.name, tpe, flags, member.privateIn)
        else member

  extension (tpe: TypeRepr)
    def contains(that: TypeRepr): Boolean =
      tpe != tpe.substituteTypes(that.typeSymbol :: Nil, TypeRepr.of[Any] :: Nil)

    def containsAll(types: TypeRepr*): Boolean =
      types.forall(tpe.contains)

    def isRepeated: Boolean =
      tpe.typeSymbol == defn.RepeatedParamClass

    def isByName: Boolean = tpe match
      case ByNameType(_) => true
      case _             => false

    def bounds: TypeBounds = tpe match
      case bounds: TypeBounds => bounds
      case tpe                => TypeBounds(tpe, tpe)

    def widenParam: TypeRepr =
      if tpe.isRepeated then tpe.typeArgs.head else tpe.widenByName

    def summon: Term = Implicits.search(tpe) match
      case success: ImplicitSearchSuccess => success.tree
      case failure: ImplicitSearchFailure => report.errorAndAbort(failure.explanation)
      case _                              => report.errorAndAbort(s"Not found: given ${tpe.show}")

    def lambda(args: List[Symbol]): TypeLambda =
      val n                      = args.length
      val names                  = args.map(_.name)
      def bounds(tl: TypeLambda) =
        val params = List.tabulate(n)(tl.param)
        args.map(_.info.substituteTypes(args, params).bounds)
      def result(tl: TypeLambda) =
        val params = List.tabulate(n)(tl.param)
        tpe.substituteTypes(args, params)
      TypeLambda(names, bounds, result)

    def summonLambda[T <: AnyKind: Type](arg: TypeRepr, args: TypeRepr*): Term =
      TypeRepr.of[T].appliedTo(tpe.lambda((arg :: args.toList).map(_.typeSymbol))).summon

    def summonOpt[T <: AnyKind: Type]: Option[Term] =
      Implicits.search(TypeRepr.of[T].appliedTo(tpe)) match
        case success: ImplicitSearchSuccess => Some(success.tree)
        case _                              => None

  extension (delegate: Option[Term])
    def newClassOf[T: Type](
      transformDef: DefDef => List[List[Tree]] => Option[Term],
      transformVal: ValDef => Option[Term],
      additionalBody: Symbol => List[DefDef] = _ => Nil,
    ): Expr[T] =
      val T = TypeRepr.of[T].dealias.typeSymbol
      if T.flags.is(Flags.Enum) then report.errorAndAbort(s"Not supported: $T is an enum")
      if !T.isClassDef || !T.flags.is(Flags.Trait) && !T.flags.is(Flags.Abstract) then
        report.errorAndAbort(s"Not supported: $T is not a trait or abstract class")

      val name    = Symbol.freshName("$anon")
      val parents = List(TypeTree.of[Object], TypeTree.of[T])
      val cls     = Symbol.newClass(
        Symbol.spliceOwner,
        name,
        parents.map(_.tpe),
        sym => sym.overridableMembers(delegate),
        None
      )
      val members = cls.declarations
        .filterNot(_.isClassConstructor)
        .map: member =>
          member.tree match
            case method: DefDef => DefDef(member, transformDef(method))
            case value: ValDef  => ValDef(member, transformVal(value))
            case tpe: TypeDef   => tpe
            case _              => report.errorAndAbort(s"Not supported: $member in ${member.owner}")

      val newCls = New(TypeIdent(cls)).select(cls.primaryConstructor).appliedToNone
      Block(ClassDef(cls, parents, members ++ additionalBody(cls)) :: Nil, newCls).asExprOf[T]

  def convertArgsToTupleType(argss: DefDef): TypeRepr =
    // Convert argss: List[List[ValDef]] to tuple type
    argss.paramss match {
      case Nil                =>
        // No arguments - use Unit
        TypeRepr.of[Unit]
      case List(Nil)          =>
        // Single empty parameter list - use Unit
        TypeRepr.of[Unit]
      case List(args)         =>
        // Multiple arguments in single parameter list - create tuple type
        val argTypes = args.params.collect { case valDef: ValDef =>
          valDef.tpt.tpe
        }
        defn.TupleClass(argTypes.length).typeRef.appliedTo(argTypes)
      case multipleParamLists =>
        // Multiple parameter lists - create tuple of tuples type
        val paramListTypes = multipleParamLists.map { paramList =>
          if (paramList.params.isEmpty) TypeRepr.of[Unit]
          else {
            val argTypes = paramList.params.collect { case valDef: ValDef =>
              valDef.tpt.tpe
            }
            if (argTypes.length == 1) argTypes.head
            else defn.TupleClass(argTypes.length).typeRef.appliedTo(argTypes)
          }
        }
        defn.TupleClass(paramListTypes.length).typeRef.appliedTo(paramListTypes)
    }

  def convertTuples(argss: List[List[Tree]]) = {
    // Convert argss: List[List[Tree]] to a tuple of tuples
    val argsTuple = argss match {
      case Nil                =>
        // No arguments - use Unit
        '{ () }
      case List(Nil)          =>
        // Single empty parameter list - use Unit
        '{ () }
      case List(args)         =>
        // Multiple arguments in single parameter list - create tuple
        val argExprs = args.map(_.asExpr)
        Expr.ofTupleFromSeq(argExprs)
      case multipleParamLists =>
        // Multiple parameter lists - create tuple of tuples
        val paramListTuples = multipleParamLists.map { paramList =>
          if (paramList.isEmpty) '{ () }
          else if (paramList.length == 1) paramList.head.asExpr
          else Expr.ofTupleFromSeq(paramList.map(_.asExpr))
        }
        Expr.ofTupleFromSeq(paramListTuples)
    }
    argsTuple
  }

  def summonP[T, P[_]: Type](using Type[T], Quotes): Expr[P[T]] =
    import quotes.reflect.*
    Expr.summon[P[T]] match {
      case Some(ct) =>
        ct
      case None     =>
        report.error(
          s"Unable to find a ${Type.of[P]} for type ${Type.show[T]}",
          Position.ofMacroExpansion
        )
        throw new Exception("Error when applying macro")
    }

end DeriveMacros

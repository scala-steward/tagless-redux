package com.goodcover.taglessAkka

import com.goodcover.tagless.EncoderGeneratorMacro

import scala.annotation.{ compileTimeOnly, StaticAnnotation }
import scala.reflect.macros.whitebox

@compileTimeOnly("Cannot expand @akkaEncoder")
class akkaEncoder extends StaticAnnotation {

  def macroTransform(annottees: Any*): Any = macro EncoderGenerator.akkaImpl
}

class EncoderGenerator(val c: whitebox.Context) extends EncoderGeneratorMacro {

  def akkaImpl(annottees: c.Expr[Any]*): c.Expr[Any] = {
    import c.universe._
    annottees.map(_.tree).toList match {
      case (head: ClassDef) :: Nil                               =>
        c.Expr[Any](
          apply[AkkaCodecFactory.type, AkkaImpl](
            head,
            None,
            EmptyTree,
            Seq(q"system: _root_.akka.actor.ActorSystem")
          )
        )
      case (classDef: ClassDef) :: (objectDef: ModuleDef) :: Nil =>
        c.Expr[Any](
          apply[AkkaCodecFactory.type, AkkaImpl](
            classDef,
            Some(objectDef),
            EmptyTree,
            Seq(q"system: _root_.akka.actor.ActorSystem")
          )
        )

      case _ => c.abort(c.enclosingPosition, "Unexpected error")
    }
  }
}

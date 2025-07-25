package com.goodcover.tagless.kryo.tests

import cats.tagless.{ Derive, FunctorK }
import com.goodcover.tagless.kryo.kryoEncoder
import com.goodcover.tagless.kryo.Default._

object algs {

  @kryoEncoder
  trait SafeAlg[F[_]] {
    def test(i: Int, foo: String): F[Int]
    def test2(i: Int): F[Int]
    def id: F[String]
  }

  //
  object SafeAlg {
    implicit def functorK: FunctorK[SafeAlg] = Derive.functorK
  }
}

package cats.tagless.tests

import cats.tagless.FunctorK

trait SafeAlg[F[_]] derives FunctorK {
  def parseInt(i: String): F[Int]
  def divide(dividend: Float, divisor: Float): F[Float]
  def divide2: F[Float]
}

object SafeAlg {
////  def apply[F[_]](implicit F: SafeAlg[F]): SafeAlg[F] = F
}

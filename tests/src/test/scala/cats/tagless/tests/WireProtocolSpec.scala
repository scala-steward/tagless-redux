package cats.tagless.tests

import cats.tagless.FunctorK
import cats.tagless.tests.WireProtocolSpec.QuoteAlg
import cats.{~>, Eval, Id}
import com.dispalt.tagless.util.{PairE, WireProtocol}
import com.dispalt.tagless.util.WireProtocol.{Decoder, Encoded, Invocation}
import com.dispalt.taglessKryo.Default.LocalInjector
import com.esotericsoftware.kryo.pool.KryoPool
import org.scalatest.flatspec.AnyFlatSpec

import java.nio.ByteBuffer
import scala.util.Try

class WireProtocolSpec extends AnyFlatSpec {

  it should "manually produce a wire protocol" in {
    // Summon the implicit
    val wp = WireProtocol[QuoteAlg]

    // Mock the server side implementation, work is a pass through,
    // inc always equals 1
    val impl: QuoteAlg[Id] = new QuoteAlg[Id] {
      override def complete(work: Boolean): Id[Boolean] = work

      override def inc: Id[Int] = 1
    }

    val workShouldBeThis = true

    // With the `wp.encoder` you convert the tagless algebra into two parts
    //  the payload of the call to, in this case, complete, and the decoder
    //  of the return value.
    // Think of this as happening on the client side
    val (payload, howToDecodeResultFromServer) = wp.encoder.complete(workShouldBeThis)

    // Assuming the result is now making it to the server side.
    //  We decode what was sent us, `payload`..
    //  We then use that to invoke the server side algebra with the
    //  correct call.
    //  That gives us the result and then how to encode the result of the return type
    val resultPair  = wp.decoder.apply(payload).get
    val finalResult = resultPair.second(resultPair.first.run[Id](impl))

    // `finalResult` now holds the result from the server, we decode that back on the client
    // and, tada, we have a result.
    assert(howToDecodeResultFromServer.apply(finalResult).get == workShouldBeThis)

  }

}

object WireProtocolSpec {

  trait QuoteAlg[F[_]] {
    def complete(work: Boolean): F[Boolean]
    def inc: F[Int]
  }

  object QuoteAlg {

    /** Manual derivation of functorK */
    implicit val functorK: FunctorK[QuoteAlg] = new FunctorK[QuoteAlg] {

      override def mapK[F[_], G[_]](af: QuoteAlg[F])(fk: F ~> G): QuoteAlg[G] =
        new QuoteAlg[G] {
          override def complete(work: Boolean): G[Boolean] = fk(af.complete(work))

          override def inc: G[Int] = fk(af.inc)
        }
    }

    implicit def wireProtocolQuoteAlg: WireProtocol[QuoteAlg] =
      new WireProtocol[QuoteAlg] {

        override def decoder: WireProtocol.Decoder[PairE[Invocation[QuoteAlg, *], WireProtocol.Encoder]] = {

          new Decoder[PairE[Invocation[QuoteAlg, *], WireProtocol.Encoder]] {
            override def apply(ab: Array[Byte]): Try[PairE[Invocation[QuoteAlg, *], WireProtocol.Encoder]] = {
              Try {
                val (fn, rest) = LocalInjector.invert(ab).map(_.asInstanceOf[Tuple2[String, Any]]).get
                fn match {
                  case "complete" =>
                    val work = rest.asInstanceOf[Tuple1[Boolean]]
                    val invocation = new Invocation[QuoteAlg, Boolean] {
                      override def run[F[_]](mf: QuoteAlg[F]): F[Boolean] = mf.complete(work._1)
                    }

                    PairE(invocation, new WireProtocol.Encoder[Boolean] {
                      override def apply(a: Boolean): Array[Byte] = Array(if (a) 1.toByte else 0.toByte)
                    })

                  case "inc" =>
                    val invocation = new Invocation[QuoteAlg, Int] {
                      override def run[F[_]](mf: QuoteAlg[F]): F[Int] = mf.inc
                    }

                    PairE(invocation, new WireProtocol.Encoder[Int] {
                      override def apply(a: Int): Array[Byte] =
                        ByteBuffer.wrap(new Array[Byte](4)).reset().putInt(a).array()
                    })
                }

              }
            }
          }
        }

        override def encoder: QuoteAlg[WireProtocol.Encoded] = new QuoteAlg[WireProtocol.Encoded] {

          override def complete(work: Boolean): (Array[Byte], Decoder[Boolean]) = {
            (LocalInjector.apply(("complete", Tuple1(work))), new Decoder[Boolean] {
              override def apply(ab: Array[Byte]): Try[Boolean] = Try(ab(0) == 1)
            })
          }

          override def inc: (Array[Byte], Decoder[Int]) = {
            (LocalInjector.apply(("inc", ())), new Decoder[Int] {
              override def apply(ab: Array[Byte]): Try[Int] = Try(ByteBuffer.wrap(ab).getInt())
            })
          }
        }
      }
  }
}

package scalaz
package iteratee

import effect._
import Iteratee._
import Id._

trait EnumeratorT[E, F[_]] { self =>
  def apply[A]: StepT[E, F, A] => IterateeT[E, F, A]

  def mapE[I](et: EnumerateeT[E, I, F])(implicit M: Monad[F]): EnumeratorT[I, F] = et run self

  def map[B](f: E => B)(implicit ev: Monad[F]): EnumeratorT[B, F] =
    EnumerateeT.map[E, B, F](f) run self

  def #::(e: => E)(implicit F: Monad[F]): EnumeratorT[E, F] = {
    new EnumeratorT[E, F] {
      def apply[A] = _.mapCont(_(elInput(e))) &= self
    }
  }

  def flatMap[B](f: E => EnumeratorT[B, F])(implicit M1: Monad[F]) =
    EnumerateeT.flatMap(f) run self

  def flatten[B, G[_]](implicit ev: E =:= G[B], MO: F |>=| G): EnumeratorT[B, F] = {
    import MO._
    flatMap(e => EnumeratorT.enumeratorTMonadTrans.liftM(MO.promote(ev(e))))
  }

  def bindM[B, G[_]](f: E => G[EnumeratorT[B, F]])(implicit F: Monad[F], G: Monad[G]): F[G[EnumeratorT[B, F]]] = {
    import scalaz.syntax.semigroup._
    val iter = fold[G[EnumeratorT[B, F]], F, G[EnumeratorT[B, F]]](G.point(EnumeratorT.empty[B, F])) {
      case (acc, concat) => G.bind(acc) { en => 
                              G.map(concat) { append => en |+| append } 
                            }
    }   

    (iter &= self.map(f)).run
  }

  def collect[B](pf: PartialFunction[E, B])(implicit monad: Monad[F]): EnumeratorT[B, F] =
    EnumerateeT.collect[E, B, F](pf) run self

  def uniq(implicit ord: Order[E], M: Monad[F]): EnumeratorT[E, F] =
    EnumerateeT.uniq[E, F] run self

  def zipWithIndex(implicit M: Monad[F]): EnumeratorT[(E, Long), F] =
    EnumerateeT.zipWithIndex[E, F] run self

  def drainTo[M[_]](implicit M: Monad[F], P: PlusEmpty[M], Z: Applicative[M]): F[M[E]] =
    (IterateeT.consume[E, F, M] &= self).run

  def reduced[B](b: B)(f: (B, E) => B)(implicit M: Monad[F]): EnumeratorT[B, F] =
    new EnumeratorT[B, F] {
      def apply[A] = (step: StepT[B, F, A]) => {
        def check(s: StepT[E, F, B]): IterateeT[B, F, A] = s.fold(
          cont = k => k(eofInput) >>== {
            s => s.mapContOr(_ => sys.error("diverging iteratee"), check(s))
          }
          , done = (a, _) => step.mapCont(f => f(elInput(a)))
        )

        iterateeT(M.bind((IterateeT.fold[E, F, B](b)(f) &= self).value) { s => check(s).value })
      }
    }
    
  def cross[E2](e2: EnumeratorT[E2, F])(implicit M: Monad[F]): EnumeratorT[(E, E2), F] =
    EnumerateeT.cross[E, E2, F](e2) run self
}

trait EnumeratorTInstances0 {
  implicit def enumeratorTSemigroup[E, F[_]](implicit F0: Bind[F]): Semigroup[EnumeratorT[E, F]] = new EnumeratorTSemigroup[E, F] {
    implicit def F = F0
  }
}

trait EnumeratorTInstances extends EnumeratorTInstances0 {
  implicit def enumeratorTMonoid[E, F[_]](implicit F0: Monad[F]): Monoid[EnumeratorT[E, F]] = new EnumeratorTMonoid[E, F] {
    implicit def F = F0
  }

  implicit def enumeratorTMonad[F[_]](implicit M0: Monad[F]): Monad[({type λ[α]=EnumeratorT[α, F]})#λ] = new EnumeratorTMonad[F] {
    implicit def M = M0
  }

  implicit def enumeratorTMonadTrans: MonadTrans[({ type λ[β[_], α] = EnumeratorT[α, β] })#λ] = new MonadTrans[({ type λ[β[_], α] = EnumeratorT[α, β] })#λ] {
    def liftM[G[_]: Monad, E](ga: G[E]): EnumeratorT[E, G] = new EnumeratorT[E, G] {
      def apply[A] = (s: StepT[E, G, A]) => iterateeT(Monad[G].bind(ga) { e => s.mapCont(k => k(elInput(e))).value })
    }

    implicit def apply[G[_]: Monad]: Monad[({type λ[α] = EnumeratorT[α, G]})#λ] = enumeratorTMonad[G]
  }
}

trait EnumeratorTFunctions {
  def enumerate[E](as: Stream[E]): Enumerator[E] = enumStream[E, Id](as)

  def empty[E, F[_]: Applicative]: EnumeratorT[E, F] =
    new EnumeratorT[E, F] {
      def apply[A] = _.pointI
    }

  /** 
   * An EnumeratorT that is at EOF
   */
  def enumEofT[E, F[_] : Applicative]: EnumeratorT[E, F] =
    new EnumeratorT[E, F] {
      def apply[A] = _.mapCont(_(eofInput))
    }

  /**
   * An enumerator that forces the evaluation of an effect in the F monad when it is consumed.
   */
  def perform[E, F[_]: Monad, B](f: F[B]): EnumeratorT[E, F] =
    new EnumeratorT[E, F] {
      def apply[A] = s => iterateeT(Monad[F].bind(f) { _ => s.pointI.value })
    }

  def enumOne[E, F[_]: Applicative](e: E): EnumeratorT[E, F] =
    new EnumeratorT[E, F] {
      def apply[A] = _.mapCont(_(elInput(e)))
    }

  def enumStream[E, F[_] : Monad](xs: Stream[E]): EnumeratorT[E, F] =
    new EnumeratorT[E, F] {
      def apply[A] = (s: StepT[E, F, A]) => xs match {
        case h #:: t => s.mapCont(k => k(elInput(h)) >>== enumStream[E, F](t).apply[A])
        case _       => s.pointI
      }
    }

  def enumList[E, F[_] : Monad](xs: List[E]): EnumeratorT[E, F] =
    new EnumeratorT[E, F] {
      def apply[A] = (s: StepT[E, F, A]) => xs match {
        case h :: t => s.mapCont(k => k(elInput(h)) >>== enumList[E, F](t).apply[A])
        case Nil    => s.pointI
      }
    }

  def enumIterator[E, F[_]](x: => Iterator[E])(implicit MO: MonadPartialOrder[F, IO]) : EnumeratorT[E, F] =
    new EnumeratorT[E, F] {
      import MO._
      def apply[A] = {
        def go(xs: Iterator[E])(s: StepT[E, F, A]): IterateeT[E, F, A] =
          if(xs.isEmpty) s.pointI
          else {
            s mapCont { k => 
              val next = xs.next
              k(elInput(next)) >>== go(xs)
            }
          }
        go(x)
      }
    }

  def enumReader[F[_]](r: => java.io.Reader)(implicit MO: MonadPartialOrder[F, IO]): EnumeratorT[IoExceptionOr[Char], F] =
    new EnumeratorT[IoExceptionOr[Char], F] {
      import MO._
      lazy val reader = r
      def apply[A] = (s: StepT[IoExceptionOr[Char], F, A]) =>
        s.mapCont(
          k => {
            val i = IoExceptionOr(reader.read)
            if (i exists (_ != -1)) k(elInput(i.map(_.toChar))) >>== apply[A]
            else s.pointI
          }
        )
    }

  /**
   * An enumerator that yields the elements of the specified array from index min (inclusive) to max (exclusive)
   */
  def enumArray[E, F[_]: Monad](a : Array[E], min: Int = 0, max: Option[Int] = None) : EnumeratorT[E, F] =
    new EnumeratorT[E, F] {
      private val limit = max.map(_ min (a.length)).getOrElse(a.length)
      def apply[A] = {
        def loop(pos : Int): StepT[E, F, A] => IterateeT[E, F, A] = {
          s => 
            s.mapCont(
              k => if (limit > pos) k(elInput(a(pos))) >>== loop(pos + 1)
                   else             s.pointI
            )   
        }

        loop(min)
      }
    }

  def repeat[E, F[_] : Monad](e: E): EnumeratorT[E, F] =
    new EnumeratorT[E, F] {
      def apply[A] = (s: StepT[E, F, A]) => s.mapCont(_(elInput(e)) >>== apply[A])
    }

  def iterate[E, F[_] : Monad](f: E => E, e: E): EnumeratorT[E, F] =
    new EnumeratorT[E, F] {
      def apply[A]: StepT[E, F, A] => IterateeT[E, F, A] = {
        type StepM = StepT[E, F, A]
        type IterateeM = IterateeT[E, F, A]

        def checkCont1(z: (E => (StepM => IterateeM)) => E => (Input[E] => IterateeM) => IterateeM, lastState: E): (StepM => IterateeM) = {
          def step: E => (StepM => IterateeM) = {
            state => _.mapCont(k => z(step)(state)(k))
          }

          step(lastState)
        }

        checkCont1(contFactory => state => k => k(elInput(e)) >>== contFactory(f(state)), e)
      }
    }
}

// Instances are mixed in with the IterateeT object
object EnumeratorT extends EnumeratorTFunctions with EnumeratorTInstances

//
// Type class implementation traits
//

private[scalaz] trait EnumeratorTSemigroup[E, F[_]] extends Semigroup[EnumeratorT[E, F]] {
  implicit def F: Bind[F]

  def append(f1: EnumeratorT[E, F], f2: => EnumeratorT[E, F]) =
    new EnumeratorT[E, F] {
      def apply[A] = (s: StepT[E, F, A]) => f1[A](s) >>== f2[A]
    }
}

private[scalaz] trait EnumeratorTMonoid[E, F[_]] extends Monoid[EnumeratorT[E, F]] with EnumeratorTSemigroup[E, F] {
  implicit def F: Monad[F]

  def zero = new EnumeratorT[E, F] {
    def apply[A] = (s: StepT[E, F, A]) => s.pointI
  }
}

private[scalaz] trait EnumeratorTFunctor[F[_]] extends Functor[({type λ[α]=EnumeratorT[α, F]})#λ] {
  implicit def M: Monad[F]
  abstract override def map[A, B](fa: EnumeratorT[A, F])(f: A => B): EnumeratorT[B, F] = fa.map(f)
}

private [scalaz] trait EnumeratorTMonad[F[_]] extends Monad[({type λ[α]=EnumeratorT[α, F]})#λ] with EnumeratorTFunctor[F] {
  def bind[A, B](fa: EnumeratorT[A, F])(f: A => EnumeratorT[B, F]) = fa.flatMap(f)
  def point[E](e: => E) = EnumeratorT.enumOne[E, F](e)
}
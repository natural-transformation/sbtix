package se.nullable.sbtix.data

import scalaz._, Scalaz._

case class RoseTreeF[+A, +R](value: A, children: List[R])

object RoseTreeF {
  implicit def bitraversable: Bitraverse[RoseTreeF] = new Bitraverse[RoseTreeF] {
    override def bitraverseImpl[G[_], A, B, C, D]
    (fab: RoseTreeF[A, B])
    (f: A => G[C], g: B => G[D])
    (implicit ev: Applicative[G]): G[RoseTreeF[C, D]] = {
      (f(fab.value) ⊛ fab.children.traverse(g)) (RoseTreeF(_, _))
    }
  }

  implicit def traverse[A]: Traverse[({type T[X] = RoseTreeF[A, X]})#T] = bitraversable.rightTraverse[A]
}
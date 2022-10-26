package se.nullable.sbtix.data

case class RoseTree[A](value: A, children: List[RoseTree[A]])

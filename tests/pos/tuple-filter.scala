type P[x] <: Boolean = x match {
  case 3 => false
  case _ => true
}
val tup: (1, 2, 4) = (1, 2, 4)
val tup2: Tuple.Filter[(1, 2, 3, 4), P] = tup

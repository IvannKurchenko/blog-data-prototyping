package expences.prototype

import shapeless._

import java.time.{LocalDate, ZoneId, ZonedDateTime}
import scala.annotation.implicitNotFound

/**
 * Type class to generate test data prototype
 * @tparam T prototype
 */
@implicitNotFound("Cannot resolve prototype of the type ${T}")
class Prototype[T](val value: T)

trait PrototypeLowPriority {
  implicit val deriveHNil: Prototype[HNil] = new Prototype(HNil)

  implicit def deriveHCons[V, T <: HList](implicit sv: => Prototype[V], st: Prototype[T]): Prototype[V :: T] = {
    new Prototype(sv.value :: st.value)
  }

  implicit def deriveInstance[F, G](implicit gen: Generic.Aux[F, G], sg: => Prototype[G]): Prototype[F] = {
    new Prototype(gen.from(sg.value))
  }
}

object Prototype extends PrototypeLowPriority {
  def apply[T](implicit prototype: Prototype[T]): Prototype[T] = prototype

  implicit val stringPrototype: Prototype[String] = new Prototype("")
  implicit val intPrototype: Prototype[Int] = new Prototype(0)
  implicit val longPrototype: Prototype[Long] = new Prototype(0)
  implicit val floatPrototype: Prototype[Float] = new Prototype(0)
  implicit val doublePrototype: Prototype[Double] = new Prototype(0)
  implicit val booleanPrototype: Prototype[Boolean] = new Prototype(false)

  implicit val localDatePrototype = new Prototype[LocalDate](LocalDate.ofYearDay(1970, 1))
  implicit val zonedDateTimePrototype = new Prototype[ZonedDateTime](ZonedDateTime.of(1970, 1, 1, 0, 0, 0, 0, ZoneId.systemDefault()))

  implicit def optionPrototype[T]: Prototype[Option[T]] = new Prototype(None)
  implicit def setPrototype[T]: Prototype[Set[T]] = new Prototype(Set.empty)
  implicit def listPrototype[T]: Prototype[List[T]] = new Prototype(List.empty)
}

trait PrototypeSyntax {
  def prototype[T](implicit p: Prototype[T]): T = p.value

  // this piece of syntax is for monocle and we will need it later
  implicit class LensPrototypeSyntax[T](lens: T => T) {
    def prototype(implicit p: Prototype[T]): T = lens(p.value)
  }
}
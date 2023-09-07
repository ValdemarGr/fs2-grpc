package fs2.grpc.shared

// A typeclass that exists for every type A
final class Trivial[A] private extends Serializable

object Trivial {
  private val any                      = new Trivial[Any]
  implicit def instance[A]: Trivial[A] = any.asInstanceOf[Trivial[A]]
}

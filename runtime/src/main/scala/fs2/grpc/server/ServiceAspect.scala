package fs2.grpc.server

import io.grpc._
import cats._
import cats.syntax.all._
import fs2.Stream

final case class ServerCallContext[Req, Res, Dom[_], Cod[_]](
    metadata: Metadata,
    methodDescriptor: MethodDescriptor[Req, Res],
    dom: Dom[Req],
    cod: Cod[Res]
)

trait ServiceAspect[F[_], Dom[_], Cod[_], A] { self =>
  def visitUnaryToUnary[Req, Res](
      callCtx: ServerCallContext[Req, Res, Dom, Cod],
      req: Req,
      next: (Req, A) => F[Res]
  ): F[Res]

  def visitUnaryToStreaming[Req, Res](
      callCtx: ServerCallContext[Req, Res, Dom, Cod],
      req: Req,
      next: (Req, A) => fs2.Stream[F, Res]
  ): fs2.Stream[F, Res]

  def visitStreamingToUnary[Req, Res](
      callCtx: ServerCallContext[Req, Res, Dom, Cod],
      req: fs2.Stream[F, Req],
      next: (fs2.Stream[F, Req], A) => F[Res]
  ): F[Res]

  def visitStreamingToStreaming[Req, Res](
      callCtx: ServerCallContext[Req, Res, Dom, Cod],
      req: fs2.Stream[F, Req],
      next: (fs2.Stream[F, Req], A) => fs2.Stream[F, Res]
  ): fs2.Stream[F, Res]

  def modify[B](f: A => F[B])(implicit F: Monad[F]): ServiceAspect[F, Dom, Cod, B] =
    new ServiceAspect[F, Dom, Cod, B] {
      override def visitUnaryToUnary[Req, Res](
          callCtx: ServerCallContext[Req, Res, Dom, Cod],
          req: Req,
          request: (Req, B) => F[Res]
      ): F[Res] =
        self.visitUnaryToUnary[Req, Res](
          callCtx,
          req,
          (req, a) => f(a).flatMap(request(req, _))
        )

      override def visitUnaryToStreaming[Req, Res](
          callCtx: ServerCallContext[Req, Res, Dom, Cod],
          req: Req,
          request: (Req, B) => Stream[F, Res]
      ): Stream[F, Res] =
        self.visitUnaryToStreaming[Req, Res](
          callCtx,
          req,
          (req, a) => fs2.Stream.eval(f(a)).flatMap(request(req, _))
        )

      override def visitStreamingToUnary[Req, Res](
          callCtx: ServerCallContext[Req, Res, Dom, Cod],
          req: fs2.Stream[F, Req],
          request: (Stream[F, Req], B) => F[Res]
      ): F[Res] =
        self.visitStreamingToUnary[Req, Res](
          callCtx,
          req,
          (req, a) => f(a).flatMap(request(req, _))
        )

      override def visitStreamingToStreaming[Req, Res](
          callCtx: ServerCallContext[Req, Res, Dom, Cod],
          req: fs2.Stream[F, Req],
          request: (Stream[F, Req], B) => Stream[F, Res]
      ): Stream[F, Res] =
        self.visitStreamingToStreaming[Req, Res](
          callCtx,
          req,
          (req, a) => fs2.Stream.eval(f(a)).flatMap(request(req, _))
        )
    }
}

object ServiceAspect {
  def default[F[_], Dom[_], Cod[_]] = new ServiceAspect[F, Dom, Cod, Metadata] {
    override def visitUnaryToUnary[Req, Res](
        callCtx: ServerCallContext[Req, Res, Dom, Cod],
        req: Req,
        request: (Req, Metadata) => F[Res]
    ): F[Res] = request(req, callCtx.metadata)

    override def visitUnaryToStreaming[Req, Res](
        callCtx: ServerCallContext[Req, Res, Dom, Cod],
        req: Req,
        request: (Req, Metadata) => Stream[F, Res]
    ): Stream[F, Res] = request(req, callCtx.metadata)

    override def visitStreamingToUnary[Req, Res](
        callCtx: ServerCallContext[Req, Res, Dom, Cod],
        req: fs2.Stream[F, Req],
        request: (Stream[F, Req], Metadata) => F[Res]
    ): F[Res] = request(req, callCtx.metadata)

    override def visitStreamingToStreaming[Req, Res](
        callCtx: ServerCallContext[Req, Res, Dom, Cod],
        req: fs2.Stream[F, Req],
        request: (Stream[F, Req], Metadata) => Stream[F, Res]
    ): Stream[F, Res] = request(req, callCtx.metadata)
  }
}

package fs2.grpc.client

import io.grpc._
import cats._
import cats.syntax.all._
import fs2.Stream

final case class ClientCallContext[Req, Res, Dom[_], Cod[_], A](
    ctx: A,
    methodDescriptor: MethodDescriptor[Req, Res],
    dom: Dom[Req],
    cod: Cod[Res]
)

trait ClientAspect[F[_], G[_], Dom[_], Cod[_], A] { self =>
  def visitUnaryToUnary[Req, Res](
      callCtx: ClientCallContext[Req, Res, Dom, Cod, A],
      req: Req,
      request: (Req, Metadata) => G[Res]
  ): F[Res]

  def visitUnaryToStreaming[Req, Res](
      callCtx: ClientCallContext[Req, Res, Dom, Cod, A],
      req: Req,
      request: (Req, Metadata) => Stream[G, Res]
  ): Stream[F, Res]

  def visitStreamingToUnary[Req, Res](
      callCtx: ClientCallContext[Req, Res, Dom, Cod, A],
      req: Stream[F, Req],
      request: (Stream[G, Req], Metadata) => G[Res]
  ): F[Res]

  def visitStreamingToStreaming[Req, Res](
      callCtx: ClientCallContext[Req, Res, Dom, Cod, A],
      req: Stream[F, Req],
      request: (Stream[G, Req], Metadata) => Stream[G, Res]
  ): Stream[F, Res]

  def contraModify[B](f: B => F[A])(implicit F: Monad[F]): ClientAspect[F, G, Dom, Cod, B] =
    new ClientAspect[F, G, Dom, Cod, B] {
      def modCtx[Req, Res](ccc: ClientCallContext[Req, Res, Dom, Cod, B]): F[ClientCallContext[Req, Res, Dom, Cod, A]] =
        f(ccc.ctx).map(a => ccc.copy(ctx = a))

      override def visitUnaryToUnary[Req, Res](
          callCtx: ClientCallContext[Req, Res, Dom, Cod, B],
          req: Req,
          request: (Req, Metadata) => G[Res]
      ): F[Res] =
        modCtx(callCtx).flatMap(self.visitUnaryToUnary(_, req, request))

      override def visitUnaryToStreaming[Req, Res](
          callCtx: ClientCallContext[Req, Res, Dom, Cod, B],
          req: Req,
          request: (Req, Metadata) => Stream[G, Res]
      ): Stream[F, Res] =
        Stream.eval(modCtx(callCtx)).flatMap(self.visitUnaryToStreaming(_, req, request))

      override def visitStreamingToUnary[Req, Res](
          callCtx: ClientCallContext[Req, Res, Dom, Cod, B],
          req: Stream[F, Req],
          request: (Stream[G, Req], Metadata) => G[Res]
      ): F[Res] =
        modCtx(callCtx).flatMap(self.visitStreamingToUnary(_, req, request))

      override def visitStreamingToStreaming[Req, Res](
          callCtx: ClientCallContext[Req, Res, Dom, Cod, B],
          req: Stream[F, Req],
          request: (Stream[G, Req], Metadata) => Stream[G, Res]
      ): Stream[F, Res] =
        Stream.eval(modCtx(callCtx)).flatMap(self.visitStreamingToStreaming(_, req, request))
    }
}

object ClientAspect {
  def default[F[_], Dom[_], Cod[_]] = new ClientAspect[F, F, Dom, Cod, Metadata] {
    override def visitUnaryToUnary[Req, Res](
        callCtx: ClientCallContext[Req, Res, Dom, Cod, Metadata],
        req: Req,
        request: (Req, Metadata) => F[Res]
    ): F[Res] = request(req, callCtx.ctx)

    override def visitUnaryToStreaming[Req, Res](
        callCtx: ClientCallContext[Req, Res, Dom, Cod, Metadata],
        req: Req,
        request: (Req, Metadata) => Stream[F, Res]
    ): Stream[F, Res] = request(req, callCtx.ctx)

    override def visitStreamingToUnary[Req, Res](
        callCtx: ClientCallContext[Req, Res, Dom, Cod, Metadata],
        req: Stream[F, Req],
        request: (Stream[F, Req], Metadata) => F[Res]
    ): F[Res] = request(req, callCtx.ctx)

    override def visitStreamingToStreaming[Req, Res](
        callCtx: ClientCallContext[Req, Res, Dom, Cod, Metadata],
        req: Stream[F, Req],
        request: (Stream[F, Req], Metadata) => Stream[F, Res]
    ): Stream[F, Res] = request(req, callCtx.ctx)
  }
}

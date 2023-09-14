/*
 * Copyright (c) 2018 Gary Coady / Fs2 Grpc Developers
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package fs2.grpc.codegen

import com.google.protobuf.Descriptors.{MethodDescriptor, ServiceDescriptor}
import scalapb.compiler.FunctionalPrinter.PrinterEndo
import scalapb.compiler.{DescriptorImplicits, FunctionalPrinter, StreamType}

class Fs2GrpcServicePrinter(service: ServiceDescriptor, serviceSuffix: String, di: DescriptorImplicits) {
  import di._
  import Fs2GrpcServicePrinter.constants._

  private[this] val serviceName: String = service.name
  private[this] val serviceNameFs2: String = s"$serviceName$serviceSuffix"
  private[this] val servicePkgName: String = service.getFile.scalaPackage.fullName

  private[this] def serviceMethodSignature(method: MethodDescriptor) = {

    val scalaInType = method.inputType.scalaType
    val scalaOutType = method.outputType.scalaType
    val ctx = s"ctx: $Ctx"

    s"def ${method.name}" + (method.streamType match {
      case StreamType.Unary => s"(request: $scalaInType, $ctx): F[$scalaOutType]"
      case StreamType.ClientStreaming => s"(request: $Stream[F, $scalaInType], $ctx): F[$scalaOutType]"
      case StreamType.ServerStreaming => s"(request: $scalaInType, $ctx): $Stream[F, $scalaOutType]"
      case StreamType.Bidirectional => s"(request: $Stream[F, $scalaInType], $ctx): $Stream[F, $scalaOutType]"
    })
  }

  private[this] def methodName(method: MethodDescriptor) =
    method.streamType match {
      case StreamType.Unary => "unaryToUnary"
      case StreamType.ClientStreaming => "streamingToUnary"
      case StreamType.ServerStreaming => "unaryToStreaming"
      case StreamType.Bidirectional => "streamingToStreaming"
    }

  private[this] def handleMethod(method: MethodDescriptor) =
    methodName(method) + "Call"

  private[this] def visitMethod(method: MethodDescriptor) =
    "visit" + methodName(method).capitalize

  private[this] def createClientCall(method: MethodDescriptor) = {
    val basicClientCall =
      s"$Fs2ClientCall[G](channel, ${method.grpcDescriptor.fullName}, dispatcher, clientOptions)"
    if (method.isServerStreaming)
      s"$Stream.eval($basicClientCall)"
    else
      basicClientCall
  }

  private[this] def serviceMethodImplementation(method: MethodDescriptor): PrinterEndo = { p =>
    val inType = method.inputType.scalaType
    val outType = method.outputType.scalaType
    val descriptor = method.grpcDescriptor.fullName

    p
      .add(serviceMethodSignature(method) + " =")
      .indented {
        _.addStringMargin(
          s"""|clientAspect.${visitMethod(method)}[$inType, $outType](
              |  ${ClientCallContext}(ctx, $descriptor, implicitly[Dom[$inType]], implicitly[Cod[$outType]]),
              |  request,
              |  (req, m) => ${createClientCall(method)}.flatMap(_.${handleMethod(method)}(req, m))
              |)""".stripMargin
        )
      }
  }

  private[this] def serviceBindingImplementation(method: MethodDescriptor): PrinterEndo = { p =>
    val inType = method.inputType.scalaType
    val outType = method.outputType.scalaType
    val descriptor = method.grpcDescriptor.fullName
    val handler = s"$Fs2ServerCallHandler[G](dispatcher, serverOptions).${handleMethod(method)}[$inType, $outType]"

    val serviceCall = s"serviceImpl.${method.name}"

    p.addStringMargin(
      s"""|.addMethod(
          |  $descriptor,
          |  $handler{ (r, m) => 
          |    serviceAspect.${visitMethod(method)}[$inType, $outType](
          |      ${ServerCallContext}(m, $descriptor, implicitly[Dom[$inType]], implicitly[Cod[$outType]]),
          |      r,
          |      (r, m) => $serviceCall(r, m)
          |    )
          |  }
          |)"""
    )
  }

  private[this] def serviceMethods: PrinterEndo = _.seq(service.methods.map(serviceMethodSignature))

  private[this] def serviceMethodImplementations: PrinterEndo =
    _.call(service.methods.map(serviceMethodImplementation): _*)

  private[this] def serviceBindingImplementations: PrinterEndo =
    _.indent
      .add(s".builder(${service.grpcDescriptor.fullName})")
      .call(service.methods.map(serviceBindingImplementation): _*)
      .add(".build()")
      .outdent

  private[this] def serviceTrait: PrinterEndo =
    _.add(s"trait $serviceNameFs2[F[_], $Ctx] {").indent.call(serviceMethods).outdent.add("}")

  private[this] def serviceObject: PrinterEndo =
    _.add(s"object $serviceNameFs2 extends $Companion[$serviceNameFs2] {").indent.newline
      .call(serviceClient)
      .newline
      .call(serviceBinding)
      .newline
      .call(domsStructure)
      .newline
      .call(codsStructure)
      .outdent
      .newline
      .add("}")

  private[this] def doms =
    service.methods
      .map(_.inputType.scalaType)
      .distinct

  private[this] def cods =
    service.methods
      .map(_.outputType.scalaType)
      .distinct

  private[this] def typeclassStructure(
      name: String,
      fieldName: String,
      fieldType: String,
      values: Seq[String]
  ): PrinterEndo = { p =>
    val types: PrinterEndo = _.addWithDelimiter(",") {
      values.zipWithIndex.map { case (v, i) => s"${fieldName}$i: $fieldType[$v]" }
    }
    p.add(s"case class $name[${fieldType}[_]](")
      .indented(types)
      .add(")")
      .newline
      .add(s"object $name {")
      .indented {
        _.add(s"implicit def typeclassInstance[${fieldType}[_]](implicit ")
          .indented(types)
          .add(s"): ${name}[${fieldType}] = $name(")
          .indented {
            _.addWithDelimiter(",") {
              values.zipWithIndex.map { case (_, i) => s"${fieldName}$i" }
            }
          }
          .add(")")
      }
      .add("}")
  }

  private[this] def codsStructure: PrinterEndo =
    typeclassStructure("Cods", "cod", "Cod", cods)

  private[this] def domsStructure: PrinterEndo =
    typeclassStructure("Doms", "dom", "Dom", doms)

  private[this] def typeclasses: PrinterEndo = { p =>
    val doms = service.methods
      .map(_.inputType.scalaType)
      .distinct
      .zipWithIndex
      .map { case (n, i) => s"dom$i: Dom[$n]" }

    val cods = service.methods
      .map(_.outputType.scalaType)
      .distinct
      .zipWithIndex
      .map { case (n, i) => s"cod$i: Cod[$n]" }

    p.addWithDelimiter(",")(doms ++ cods)
  }

  private[this] def serviceClient: PrinterEndo = {
    _.addStringMargin(
      s"""|def mkClientFull[F[_], G[_]: $Async, Dom[_], Cod[_], $Ctx](
          |  dispatcher: $Dispatcher[G],
          |  channel: $Channel,
          |  clientAspect: ${ClientAspect}[F, G, Dom, Cod, $Ctx],
          |  clientOptions: $ClientOptions
          |)(implicit"""
    )
      .indented(typeclasses)
      .add(s"): $serviceNameFs2[F, $Ctx] = new $serviceNameFs2[F, $Ctx] {")
      .indent
      .call(serviceMethodImplementations)
      .outdent
      .add("}")
      .newline
      .addStringMargin(
        s"""|def mkClientTrivial[F[_], G[_]: $Async, $Ctx](
            |  dispatcher: $Dispatcher[G],
            |  channel: $Channel,
            |  clientAspect: ${ClientAspect}[F, G, $Trivial, $Trivial, $Ctx],
            |  clientOptions: $ClientOptions
            |) = 
            |  mkClientFull[F, G, $Trivial, $Trivial, $Ctx](
            |    dispatcher,
            |    channel,
            |    clientAspect,
            |    clientOptions
            |  )"""
      )
  }

  private[this] def serviceBinding: PrinterEndo = {
    _.addStringMargin(
      s"""|def serviceBindingFull[F[_], G[_]: $Async, Dom[_], Cod[_], $Ctx](
          |  dispatcher: $Dispatcher[G],
          |  serviceImpl: $serviceNameFs2[F, $Ctx],
          |  serviceAspect: ${ServiceAspect}[F, G, Dom, Cod, $Ctx],
          |  serverOptions: $ServerOptions
          |)(implicit"""
    )
      .indented(typeclasses)
      .add(") = {")
      .indent
      .add(s"$ServerServiceDefinition")
      .call(serviceBindingImplementations)
      .outdent
      .add("}")
      .newline
      .addStringMargin(
        s"""|def serviceBindingTrivial[F[_], G[_]: $Async, $Ctx](
            |  dispatcher: $Dispatcher[G],
            |  serviceImpl: $serviceNameFs2[F, $Ctx],
            |  serviceAspect: ${ServiceAspect}[F, G, $Trivial, $Trivial, $Ctx],
            |  serverOptions: $ServerOptions
            |) = 
            |  serviceBindingFull[F, G, $Trivial, $Trivial, $Ctx](
            |    dispatcher,
            |    serviceImpl,
            |    serviceAspect,
            |    serverOptions
            |  )"""
      )
  }

  // /

  def printService(printer: FunctionalPrinter): FunctionalPrinter = {
    printer
      .add(s"package $servicePkgName", "", "import _root_.cats.syntax.all._", "")
      .call(serviceTrait)
      .newline
      .call(serviceObject)
  }
}

object Fs2GrpcServicePrinter {

  private[codegen] object constants {

    private val effPkg = "_root_.cats.effect"
    private val fs2Pkg = "_root_.fs2"
    private val fs2grpcPkg = "_root_.fs2.grpc"
    private val fs2grpcServerPkg = "_root_.fs2.grpc.server"
    private val fs2grpcClientPkg = "_root_.fs2.grpc.client"
    private val fs2grpcSharedPkg = "_root_.fs2.grpc.shared"
    private val grpcPkg = "_root_.io.grpc"

    // /

    val Ctx = "A"

    val Async = s"$effPkg.Async"
    val Resource = s"$effPkg.Resource"
    val Dispatcher = s"$effPkg.std.Dispatcher"
    val Stream = s"$fs2Pkg.Stream"

    val Fs2ServerCallHandler = s"$fs2grpcPkg.server.Fs2ServerCallHandler"
    val Fs2ClientCall = s"$fs2grpcPkg.client.Fs2ClientCall"
    val ClientOptions = s"$fs2grpcPkg.client.ClientOptions"
    val ServerOptions = s"$fs2grpcPkg.server.ServerOptions"
    val Companion = s"$fs2grpcPkg.GeneratedCompanion"

    val ServerServiceDefinition = s"$grpcPkg.ServerServiceDefinition"
    val Channel = s"$grpcPkg.Channel"
    val Metadata = s"$grpcPkg.Metadata"

    val ServiceAspect = s"${fs2grpcServerPkg}.ServiceAspect"
    val ServerCallContext = s"${fs2grpcServerPkg}.ServerCallContext"
    val ClientAspect = s"${fs2grpcClientPkg}.ClientAspect"
    val ClientCallContext = s"${fs2grpcClientPkg}.ClientCallContext"
    val Trivial = s"${fs2grpcSharedPkg}.Trivial"
  }

}

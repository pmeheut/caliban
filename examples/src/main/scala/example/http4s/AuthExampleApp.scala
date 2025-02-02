package example.http4s

import caliban.GraphQL._
import caliban.schema.GenericSchema
import caliban.{ Http4sAdapter, RootResolver }
import org.http4s.HttpRoutes
import org.http4s.dsl.Http4sDsl
import org.http4s.blaze.server.BlazeServerBuilder
import org.http4s.server.{ Router, ServiceErrorHandler }
import org.typelevel.ci.CIString
import zio._
import zio.interop.catz._

object AuthExampleApp extends CatsApp {

  // Simple service that returns the token coming from the request
  trait Auth {
    def token: String
  }

  type AuthTask[A] = RIO[Auth, A]
  type MyTask[A]   = RIO[Any, A]

  case class MissingToken() extends Throwable

  // http4s middleware that extracts a token from the request and eliminate the Auth layer dependency
  object AuthMiddleware {
    def apply(route: HttpRoutes[AuthTask]): HttpRoutes[MyTask] =
      Http4sAdapter.provideSomeLayerFromRequest[Any, Auth](
        route,
        _.headers.get(CIString("token")) match {
          case Some(value) => ZLayer.succeed(new Auth { override def token: String = value.head.value })
          case None        => ZLayer.fail(MissingToken())
        }
      )
  }

  // http4s error handler to customize the response for our throwable
  object dsl extends Http4sDsl[MyTask]
  import dsl._
  val errorHandler: ServiceErrorHandler[MyTask] = _ => { case MissingToken() => Forbidden() }

  // our GraphQL API
  val schema: GenericSchema[Auth] = new GenericSchema[Auth] {}
  import schema._
  case class Query(token: RIO[Auth, String])
  private val resolver            = RootResolver(Query(ZIO.serviceWith[Auth](_.token)))
  private val api                 = graphQL(resolver)

  override def run =
    for {
      interpreter <- api.interpreter
      _           <- BlazeServerBuilder[MyTask]
                       .withServiceErrorHandler(errorHandler)
                       .bindHttp(8088, "localhost")
                       .withHttpWebSocketApp(wsBuilder =>
                         Router[MyTask](
                           "/api/graphql" -> AuthMiddleware(Http4sAdapter.makeHttpService(interpreter)),
                           "/ws/graphql"  -> AuthMiddleware(Http4sAdapter.makeWebSocketService(wsBuilder, interpreter))
                         ).orNotFound
                       )
                       .resource
                       .toScopedZIO *> ZIO.never
    } yield ()
}

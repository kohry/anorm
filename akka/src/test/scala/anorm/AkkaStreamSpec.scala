package anorm

import java.sql.{ Connection, ResultSet }

import scala.concurrent.Future

import scala.collection.immutable.Seq

import akka.NotUsed
import akka.stream.scaladsl.{ Sink, Source }

import acolyte.jdbc.AcolyteDSL.withQueryResult
import acolyte.jdbc.RowLists.stringList
import acolyte.jdbc.Implicits._

object AkkaStreamSpec extends org.specs2.mutable.Specification {
  "Akka Stream" title

  implicit lazy val system = akka.actor.ActorSystem("knox-core-tests")
  implicit def materializer = akka.stream.ActorMaterializer.create(system)

  "Akka Stream" should {
    "expose the query result as source" in (
      withQueryResult(stringList :+ "A" :+ "B" :+ "C")) { implicit con =>
        AkkaStream.source(SQL"SELECT * FROM Test", SqlParser.scalar[String]).
          runWith(Sink.seq[String]) must beEqualTo(Seq("A", "B", "C")).
          await(1000)
      }

    "manage resources" in {
      def run[T](sink: Sink[String, T])(implicit c: Connection) = {
        val graph = source(SQL"SELECT * FROM Test", SqlParser.scalar[String])

        Source.fromGraph(graph).runWith(sink.mapMaterializedValue { _ =>
          graph.resultSet
        })
      }

      def runAsync[T](sink: Sink[String, Future[T]])(implicit c: Connection) = {
        val graph = source(SQL"SELECT * FROM Test", SqlParser.scalar[String])

        Source.fromGraph(graph).runWith(sink).map { _ => graph.resultSet }
      }

      "on success" in (
        withQueryResult(stringList :+ "A" :+ "B" :+ "C")) { implicit con =>
          runAsync(Sink.seq[String]) must beLike[ResultSet] {
            case rs => rs.isClosed must beTrue and (
              rs.getStatement.isClosed must beTrue) and (
                con.isClosed must beFalse)
          }.await(1000)
        }

      "on cancellation" in (
        withQueryResult(stringList :+ "A" :+ "B" :+ "C")) { implicit con =>
          run(Sink.cancelled[String]) must beLike[ResultSet] {
            case rs => rs must beNull or (rs.isClosed must beTrue and (
              rs.getStatement.isClosed must beTrue))
          }
        }

      "on failure" in (
        withQueryResult(stringList :+ "A" :+ "B" :+ "C")) { implicit con =>
          run(Sink.reduce[String] { (_, _) => sys.error("Foo") }).
            aka("result") must beLike[ResultSet] {
              case rs => rs must beNull or (rs.isClosed must beTrue)
            }
        }
    }
  }

  def source[T](sql: Sql, parser: RowParser[T])(implicit connection: Connection) = new AkkaStream.ResultSource[T](connection, sql, ColumnAliaser.empty, parser)
}

import scala.concurrent.{Future, Await}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import slick.backend.DatabasePublisher
import slick.driver.H2Driver.api._

object GoodreadsLibraryValueCalculator extends App {
  val db = Database.forConfig("h2disk1")
  try {
    val httpResponse = TableQuery[HttpResponse]

    val action: Future[Unit] = db.run(DBIO.seq(httpResponse.schema.create))

    Await.result(action, Duration.Inf)
  } finally db.close

}

import com.typesafe.config._
import scala.concurrent.{Future, Await}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import slick.backend.DatabasePublisher
import slick.driver.H2Driver.api._
import dispatch._, Defaults._
import scala.xml.XML

object GoodreadsLibraryValueCalculator extends App {

  val config = ConfigFactory.load()
  val goodreadsConfig = config.getConfig("goodreads")
  val baseReviewService = url("https://www.goodreads.com/review/list")
  val db = Database.forConfig("h2disk1")

  /**
    * Retrieves a page of "reviews" from Goodreads
    *
    * @param page
    * @param per_page
    * @return
    */
  def getPage(page: String, per_page: String = "100") = {
    // TODO: Cache results using H2?
    val reviewService = baseReviewService <<? Map(
      "v" -> "2",
      "id" -> goodreadsConfig.getString("userId"),
      "shelf" -> goodreadsConfig.getString("shelf"),
      "key" -> goodreadsConfig.getString("key"),
      "page" -> page,
      "per_page" -> per_page
    )
    val reviewXMLString = Http(reviewService OK as.String)
    val reviewXML = XML.loadString(reviewXMLString())
    reviewXML \\ "reviews"
  }

  try {
    // Generate Http Response table
    val httpResponse = TableQuery[HttpResponse]
    val action: Future[Unit] = db.run(DBIO.seq(httpResponse.schema.create))
    Await.result(action, Duration.Inf)

    // TODO: Retrieve books
    val reviewService = baseReviewService <<? Map(
      "v" -> "2",
      "id" -> goodreadsConfig.getString("userId"),
      "shelf" -> goodreadsConfig.getString("shelf"),
      "key" -> goodreadsConfig.getString("key"),
      "page" -> "1",
      "per_page" -> "100"
    )
    val reviewXMLString = Http(reviewService OK as.String)
    val reviewXML = XML.loadString(reviewXMLString())
    val reviews = reviewXML \\ "reviews"


  } finally db.close

}

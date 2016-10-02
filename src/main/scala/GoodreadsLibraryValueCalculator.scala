import com.typesafe.config._
import scala.concurrent.{Future, Await}
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
  def getReviews(page: String = "1", per_page: String = "100") = {
    val reviewService = baseReviewService <<? Map(
      "v" -> "2",
      "id" -> goodreadsConfig.getString("userId"),
      "shelf" -> goodreadsConfig.getString("shelf"),
      "key" -> goodreadsConfig.getString("key"),
      "page" -> page,
      "per_page" -> per_page
    )
    val reviewServiceResponse = Http(reviewService OK as.String)
    val reviewXML = for (reviewXMLString <- reviewServiceResponse) yield XML.loadString(reviewXMLString)
    for (xml <- reviewXML) yield xml \\ "reviews"
  }

  /**
    * Retrieve the first page of reviews and use it to start retreiving all
    * remaining pages in parallel.
    *
    * TODO: When Scala 2.12 becomes available this can be flattened for cleaner usage patterns
    */
  val collectedReviews = for (firstReviewPage <- getReviews()) yield {
    val totalReviews = (firstReviewPage \@ "total").toInt
    val pageEnd = (firstReviewPage \@ "end").toInt
    val totalPages = (totalReviews / pageEnd.toDouble).ceil.toInt
    val remainingReviews = for (pageNumber <- 2 to totalPages) yield getReviews(pageNumber.toString)
    Future.sequence(Future.successful(firstReviewPage) +: remainingReviews)
  }

  /**
    * This is a little hairy but it works, producing a Future for a
    * IndexedSeq of scala.xml.Node objects each of which is a GoodReads
    * isbn13 item.
    */
  val collectedISBNs = for {
    reviewsPageFuture <- collectedReviews
    reviewsPage <- reviewsPageFuture
  } yield (for (reviews <- reviewsPage) yield reviews \\ "review" \\ "isbn13").flatten


  try {
    // Generate Http Response table
    val httpResponse = TableQuery[HttpResponse]
    val action: Future[Unit] = db.run(DBIO.seq(httpResponse.schema.create))
    Await.result(action, Duration.Inf)

    // TODO: Work with collectedReviews

  } finally db.close

}

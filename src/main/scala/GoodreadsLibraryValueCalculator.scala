import com.typesafe.config._

import scala.concurrent.{Await, Future}
import scala.concurrent.duration.Duration
import slick.backend.DatabasePublisher
import slick.driver.H2Driver.api._
import dispatch._
import Defaults._

import scala.xml.{Node, XML}

object GoodreadsLibraryValueCalculator extends App {

  val config = ConfigFactory.load()
  val goodreadsConfig = config.getConfig("goodreads")
  val baseReviewService = url("https://www.goodreads.com/review/list")
  val basePriceService = url("http://www.loot.co.za/search")
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
    *
    * @param per_page
    * @return
    */
  def getAllReviews(per_page: String = "100") = {
    val futures = for (firstReviewPage <- getReviews(per_page = per_page)) yield {
      val totalReviews = (firstReviewPage \@ "total").toInt
      val pageEnd = (firstReviewPage \@ "end").toInt
      val totalPages = (totalReviews / pageEnd.toDouble).ceil.toInt
      val remainingReviews = for (pageNumber <- 2 to totalPages) yield getReviews(pageNumber.toString)
      Future.sequence(Future.successful(firstReviewPage) +: remainingReviews)
    }
    futures.flatMap(identity)
  }

  /**
    * Retrieve the first page of reviews and use it to start retreiving all
    * remaining pages in parallel.
    *
    * TODO: Allow for page size to be customizable
    */
  val collectedReviews = getAllReviews()

  /**
    * This is a little hairy but it works, producing a Future for a
    * IndexedSeq of scala.xml.Node objects each of which is a GoodReads
    * isbn13 item.
    */
  val collectedISBNs = for {
    reviewsPage <- collectedReviews
  } yield (for (reviews <- reviewsPage) yield reviews \\ "review" \\ "isbn13").flatten

  /**
    * Group the ISBNs into chunks of 10. Each chunk of 10 will be processed in
    * parallel before the next chunk of 10 is handled and so on until all the
    * ISBNs have been processed
    *
    * TODO: Allow for chunk size to be customizable
    */
  val groupedISBNs = collectedISBNs.map(_.grouped(10).toList)

  /**
    *
    * @param isbn13
    * @return
    */
  def getPriceForISBN(isbn13: Node) = {
    val priceService = basePriceService <<? Map(
      "offset" -> "0",
      "cat" -> "b",
      "terms" -> isbn13.text
    )
    val priceServiceResponse = Http(priceService OK as.String)
    // TODO: Extract price from response
    priceServiceResponse
  }

  /**
    *
    * @param chunks
    * @return
    */
  def getPricesForISBNChunks(chunks: List[IndexedSeq[Node]]): Future[IndexedSeq[String]] = {
    chunks match {
      case Nil => Future.successful(IndexedSeq[String]())
      case chunk :: remainingChunks => for {
        batch <- Future.traverse(chunk) { node => getPriceForISBN(node) }
        nextBatch <- getPricesForISBNChunks(remainingChunks)
      } yield batch ++ nextBatch
    }
  }

  val prices = groupedISBNs.map(getPricesForISBNChunks).flatMap(identity)


  try {
    // Generate Http Response table
    val httpResponse = TableQuery[HttpResponse]
    val action: Future[Unit] = db.run(DBIO.seq(httpResponse.schema.create))
    Await.result(action, Duration.Inf)

    // TODO: Work with collectedReviews

  } finally db.close

}

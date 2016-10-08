import com.typesafe.config._

import scala.concurrent.{Await, Future}
import scala.concurrent.duration.Duration
import slick.backend.DatabasePublisher
import slick.driver.H2Driver.api._
import dispatch._
import Defaults._
import net.ruippeixotog.scalascraper.browser.JsoupBrowser
import net.ruippeixotog.scalascraper.dsl.DSL._
import net.ruippeixotog.scalascraper.dsl.DSL.Extract._
import net.ruippeixotog.scalascraper.dsl.DSL.Parse._
import org.joda.money.Money

import scala.util.Success
import scala.xml.{Node, XML}

object GoodreadsLibraryValueCalculator extends App {

  val config = ConfigFactory.load()
  val browser = JsoupBrowser()
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
    * TODO: Document
    *
    * @param isbn13
    * @return
    */
  def getPriceForISBNFromLoot(isbn13: Node) = {
    val priceService = url("http://www.loot.co.za/search") <<? Map(
      "offset" -> "0",
      "cat" -> "b",
      "terms" -> isbn13.text
    )
    val priceServiceResponse = Http(priceService OK as.String)
    val html = priceServiceResponse.map(browser.parseString)
    val potentialPrice = for (document <- html) yield document >?> text("div.productListing span.price del")
    for (option <- potentialPrice) yield option match {
      case Some(price) => Some(Money.parse("ZA" + price))
      case None => None
    }
  }

  /**
    * TODO: Implement this
    *
    * @param isbn13
    * @return
    */
  def getPriceForISBNFromAmazon(isbn13: Node) = {
    val priceService = url("https://www.amazon.com/s/ref=nb_sb_noss") <<? Map(
      "url" -> "search-alias=stripbooks",
      "field-keywords" -> isbn13.text
    )
    val priceServiceResponse = Http(priceService OK as.String)
    val html = priceServiceResponse.map(browser.parseString)
    // TODO: Correct for Amazon strucutre
    val potentialPrice = for (document <- html) yield document >?> text("div.productListing span.price del")
      for (option <- potentialPrice) yield option match {
      case Some(price) => Some(Money.parse("ZA" + price))
      case None => None
    }
  }

  val priceProviders: List[(Node) => Future[Option[Money]]] = List(
    getPriceForISBNFromLoot, getPriceForISBNFromAmazon)

  /**
    * TODO: Document
    *
    * @param isbn13
    * @param providers
    * @return
    */
  def getPriceForISBNFromProviders(isbn13: Node,
                                   providers: List[(Node) => Future[Option[Money]]]): Future[Option[Money]] = {
    providers match {
      case Nil => throw new Exception("Could not obtain a price")
      case provider :: remainingProviders =>
        provider(isbn13) recoverWith {
          case ex => getPriceForISBNFromProviders(isbn13, remainingProviders)
        }
    }
  }

  /**
    * Transforms an Option[Money] for a given Node to an Either[Money, Node]
    *
    * @param isbn13
    * @return
    */
  def getPriceForISBN(isbn13: Node) = getPriceForISBNFromProviders(isbn13, priceProviders) map {
    case Some(money) => Left(money)
    case None => Right(isbn13)
  }

  /**
    * TODO: Document
    *
    * @param chunks
    * @return
    */
  def getPricesForISBNChunks(chunks: List[IndexedSeq[Node]]): Future[IndexedSeq[Either[Money, Node]]] = {


    chunks match {
      case Nil => Future.successful(IndexedSeq[Either[Money, Node]]())
      case chunk :: remainingChunks =>
        for {
          batch <- Future.traverse(chunk)(getPriceForISBN)
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

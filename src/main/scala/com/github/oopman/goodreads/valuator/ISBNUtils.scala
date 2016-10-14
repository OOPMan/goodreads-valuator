package com.github.oopman.goodreads.valuator

import com.typesafe.config._
import com.typesafe.scalalogging.Logger
import dispatch.Defaults._
import dispatch._
import net.ruippeixotog.scalascraper.browser.JsoupBrowser
import net.ruippeixotog.scalascraper.dsl.DSL.Extract._
import net.ruippeixotog.scalascraper.dsl.DSL._
import org.joda.money.Money

import scala.concurrent.Future
import scala.util.{Failure, Success}
import scala.xml.XML

/**
  * Created by adamj on 2016/10/10.
  */
object ISBNUtils {
  val logger = Logger("com.github.oopman.goodreads.valuator.ISBNUntils")
  val config = ConfigFactory.load()
  val browser = JsoupBrowser()
  val empty = Future.successful("")
  val goodreadsConfig = config.getConfig("goodreads")
  val baseReviewService = url("https://www.goodreads.com/review/list")

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
    logger.info(s"Retrieving page $page of GoodReads Reviews")
    val reviewServiceResponse = Http(reviewService OK as.String) andThen {
      case Success(_) => logger.info(s"Retrieved page $page")
      case Failure(_) => logger.error(s"Failed to retrieve page $page")
    }
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
      logger.info(s"Total reviews: $totalReviews")
      logger.info(s"Retrieving ${totalPages - 1} additional pages")
      val remainingReviews = for (pageNumber <- 2 to totalPages) yield getReviews(pageNumber.toString)
      Future.sequence(Future.successful(firstReviewPage) +: remainingReviews).andThen {
        case Success(_) => logger.info("Retrieved all remaining pages")
        case Failure(_) => logger.error("Failed to retrieve some or all remaining pages")
      }
    }
    futures.flatMap(identity)
  }

  /**
    * TODO: Document
    *
    * @param isbn13
    * @return
    */
  def getPriceForISBNFromLoot(isbn13: String) = {
    // TODO: Cache response?
    val priceService = url("http://www.loot.co.za/search") <<? Map(
      "offset" -> "0",
      "cat" -> "b",
      "terms" -> isbn13
    )
    logger.info(s"Attempting to load $isbn13 price from Loot")
    val priceServiceResponse = Http(priceService OK as.String).fallbackTo(empty)
    val html = priceServiceResponse.map(browser.parseString)
    val potentialPrice = for (document <- html) yield document >?> text("div.productListing span.price del")
    for (option <- potentialPrice) yield option match {
      case Some(price) =>
        logger.info(s"Loaded price for $isbn13 from Loot")
        Some(Money.parse("ZAR" + price.tail))
      case None =>
        logger.warn(s"Failed to load price for $isbn13 from Loot")
        None
    }
  }

  /**
    * TODO: Document
    *
    * @param isbn13
    * @return
    */
  def getPriceForISBNFromAmazon(isbn13: String) = {
    // TODO: Cache response?
    val priceService = url("https://www.amazon.com/s/ref=nb_sb_noss") <<? Map(
      "field-keywords" -> isbn13
    )
    logger.info(s"Attempting to load $isbn13 price from Amazon")
    val priceServiceResponse = Http.configure(_ setFollowRedirects true)(priceService OK as.String)
    val html = priceServiceResponse.fallbackTo(empty).map(browser.parseString)
    val potentialPrice = for (document <- html) yield document >?> text("#result_0 .s-item-container span.a-color-price")
      for (option <- potentialPrice) yield option match {
      case Some(price) =>
        logger.info(s"Loaded price for $isbn13 from Amazon")
        Some(Money.parse("USD" + price.tail))
      case None =>
        logger.warn(s"Failed to load price for $isbn13 from Amazon")
        None
    }
  }

  val priceProviders: List[(String) => Future[Option[Money]]] = List(
    getPriceForISBNFromLoot, getPriceForISBNFromAmazon)

  /**
    * TODO: Document
    *
    * @param isbn13
    * @param providers
    * @return
    */
  def getPriceForISBN(isbn13: String,
                      providers: List[(String) => Future[Option[Money]]] = priceProviders): Future[Option[Money]] = {
    providers match {
      case Nil =>
        logger.error(s"Failed to load a price for $isbn13 from available Providers")
        throw new Exception("Could not obtain a price")
      case provider :: remainingProviders =>
        provider(isbn13) recoverWith {
          case ex => getPriceForISBN(isbn13, remainingProviders)
        }
    }
  }

  /**
    * TODO: Document
    *
    * @param chunks
    * @return
    */
  def getPricesForISBNChunks(chunks: List[IndexedSeq[String]]): Future[IndexedSeq[Option[Money]]] = {
    chunks match {
      case Nil =>
        logger.info("Done processing chunks")
        Future.successful(IndexedSeq[Option[Money]]())
      case chunk :: remainingChunks =>
        logger.info(s"Processing chunk of ${chunk.length} ISBNs. ${remainingChunks.length} chunks left to process")
        for {
          batch <- Future.traverse(chunk)(isbn13 => {
            logger.info(s"Attempting to obtain price for $isbn13")
            getPriceForISBN(isbn13)
          })
          nextBatch <- getPricesForISBNChunks(remainingChunks)
        } yield batch ++ nextBatch
    }
  }
}

package com.github.oopman.goodreads.valuator

import com.typesafe.config._
import dispatch.Defaults._
import dispatch._
import net.ruippeixotog.scalascraper.browser.JsoupBrowser
import net.ruippeixotog.scalascraper.dsl.DSL.Extract._
import net.ruippeixotog.scalascraper.dsl.DSL._
import org.joda.money.Money

import scala.concurrent.Future
import scala.xml.XML

/**
  * Created by adamj on 2016/10/10.
  */
object ISBNUtils {
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
    val priceServiceResponse = Http(priceService OK as.String).fallbackTo(empty)
    val html = priceServiceResponse.map(browser.parseString)
    val potentialPrice = for (document <- html) yield document >?> text("div.productListing span.price del")
    for (option <- potentialPrice) yield option match {
      case Some(price) => Some(Money.parse("ZAR" + price.tail))
      case None => None
    }
  }

  /**
    * TODO: Implement this
    *
    * @param isbn13
    * @return
    */
  def getPriceForISBNFromAmazon(isbn13: String) = {
    // TODO: Cache response?
    val priceService = url("https://www.amazon.com/s/ref=nb_sb_noss") <<? Map(
      "field-keywords" -> isbn13
    )
    val priceServiceResponse = Http.configure(_ setFollowRedirects true)(priceService OK as.String).fallbackTo(empty)
    val html = priceServiceResponse.map(browser.parseString)
    val potentialPrice = for (document <- html) yield document >?> text("#result_0 .s-item-container span.a-color-price")
      for (option <- potentialPrice) yield option match {
      case Some(price) => Some(Money.parse("USD" + price.tail))
      case None => None
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
      case Nil => throw new Exception("Could not obtain a price")
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
      case Nil => Future.successful(IndexedSeq[Option[Money]]())
      case chunk :: remainingChunks =>
        for {
          batch <- Future.traverse(chunk)(isbn13 => getPriceForISBN(isbn13))
          nextBatch <- getPricesForISBNChunks(remainingChunks)
        } yield batch ++ nextBatch
    }
  }
}

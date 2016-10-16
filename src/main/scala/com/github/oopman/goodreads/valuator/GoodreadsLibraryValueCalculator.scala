package com.github.oopman.goodreads.valuator

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}
import com.typesafe.config._
import dispatch._
import dispatch.Defaults._
import net.ruippeixotog.scalascraper.browser.JsoupBrowser
import slick.driver.H2Driver.api._
import com.github.oopman.goodreads.valuator.ISBNUtils._
import com.typesafe.scalalogging.Logger
import org.joda.money.{CurrencyUnit, Money}

object GoodreadsLibraryValueCalculator extends App {

  val logger = Logger("com.github.oopman.goodreads.valuator.GoodreadsLibraryValueCalculator")
  val config = ConfigFactory.load()
  val browser = JsoupBrowser()
  val goodreadsConfig = config.getConfig("goodreads")
  val baseReviewService = url("https://www.goodreads.com/review/list")
//  val db = Database.forConfig("h2disk1")

  val reviewPages = getAllReviews(goodreadsConfig.getString("page_size"))
  // TODO: Find a way to clean this up
  val reviewNodes = for (reviewPage <- reviewPages) yield (for (reviews <- reviewPage) yield reviews \\ "review").flatten

  // TODO: Find a way to clean this up
  val isbnNodes = for (review <- reviewNodes) yield (for (reviewNode <- review) yield reviewNode \\ "isbn13").flatten
  val collectedISBNs = isbnNodes.map { i => i.map { j => j.text }}
  // TODO: Find a way to clean this up
  val titleNodes = for (review <- reviewNodes) yield (for (reviewNode <- review) yield reviewNode \\ "title").flatten
  val collectedTitles = titleNodes.map { i => i.map { j => j.text }}

  val isbnToTitle = for {
    isbn <- collectedISBNs
    title <- collectedTitles
  } yield Map(isbn zip title: _*)

  /**
    * Group the ISBNs into chunks of 10. Each chunk of 10 will be processed in
    * parallel before the next chunk of 10 is handled and so on until all the
    * ISBNs have been processed
    *
    * TODO: Allow for chunk size to be customizable
    */
  val groupedISBNs = collectedISBNs.map(_.grouped(10).toList)

  val prices = groupedISBNs.map(getPricesForISBNChunks).flatMap(identity)
  val isbnsAndPrices = for {
    a <- collectedISBNs
    b <- prices
  } yield {
    a zip b
  }

  /**
    * Map of Currency -> Seq of (ISBN, Some(Money)) tuples
    *
    * Additionally, "" maps to Seq of (ISBN, None) tuples
    */
  val currencyToISBNsAndPrices = isbnsAndPrices.map(_ groupBy {
    case (_, Some(money)) => money.getCurrencyUnit.getCurrencyCode
    case _ => ""
  })

  val ISBNsWithNoPrice = currencyToISBNsAndPrices.map(_.getOrElse("", IndexedSeq()).map(_._1).mkString(", "))

  val currencyToSummedPrices = currencyToISBNsAndPrices.map(_.map {
    case ("", _) => ("", Money.zero(CurrencyUnit.USD))
    case (key, value) =>
      (key, value.flatMap(_._2).reduce(_.plus(_)))
  })

  val output = for (pricing <- currencyToSummedPrices; isbns <- ISBNsWithNoPrice) yield {
    logger.info("Pricing amounts")
    for ((currency, amount) <- pricing) {
      if (currency != "") logger.info(s"$amount")
    }
    logger.info(s"ISBNs with no price: $isbns")
    db.close()
    System.exit(0)
  }
}

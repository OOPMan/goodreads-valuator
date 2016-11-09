package com.github.oopman.goodreads.valuator

import dispatch.Defaults._
import slick.driver.H2Driver.api._
import com.typesafe.scalalogging.Logger
import org.joda.money.{CurrencyUnit, Money}

import scala.concurrent.{Await, Future}
import scala.concurrent.duration.Duration
import scala.util.{Failure, Success}

object GoodreadsLibraryValueCalculator extends App {
  val logger = Logger("com.github.oopman.goodreads.valuator.GoodreadsLibraryValueCalculator")
  val db = Database.forConfig("cache")
  val httpResponse = TableQuery[HttpResponse]

  /**
    * Perform valuation
    *
    * @return
    */
  def valuate(config: Config) = {
    val providers = config.providers.collect(ISBNUtils.priceProviders).toList
    val isbnUtils = new ISBNUtils(db)

    val reviewPages = isbnUtils.getAllReviews(config)
    // TODO: Find a way to clean this up
    val reviewNodes = for (reviewPage <- reviewPages) yield (for (reviews <- reviewPage) yield reviews \\ "review").flatten

    // TODO: Find a way to clean this up
    val isbnNodes = for (review <- reviewNodes) yield (for (reviewNode <- review) yield reviewNode \\ "isbn13").flatten
    val collectedISBNs = isbnNodes.map { i => i.map { j => j.text } }
    // TODO: Find a way to clean this up
    val titleNodes = for (review <- reviewNodes) yield (for (reviewNode <- review) yield reviewNode \\ "title").flatten
    val collectedTitles = titleNodes.map { i => i.map { j => j.text } }

    // TODO: Use this to output titles of unmatched ISBNs
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
    val groupedISBNs = collectedISBNs.map(_.grouped(config.chunkSize).toList)

    val prices = groupedISBNs.map(listOfSeqOfISBNs => isbnUtils.getPricesForISBNChunks(listOfSeqOfISBNs, providers)).flatMap(identity)
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

    val ISBNsWithNoPrice = currencyToISBNsAndPrices.map(_.getOrElse("", IndexedSeq()).map(_._1))

    val currencyToSummedPrices = currencyToISBNsAndPrices.map(_.map {
      case ("", _) => ("", Money.zero(CurrencyUnit.USD))
      case (key, value) =>
        (key, value.flatMap(_._2).reduce(_.plus(_)))
    })

    for {
      pricing <- currencyToSummedPrices
      isbns <- ISBNsWithNoPrice
      isbnsToTitles <- isbnToTitle
    } yield {
      logger.info("Pricing amounts")
      for ((currency, amount) <- pricing) {
        if (currency != "") logger.info(s"$amount")
      }
      val titles = isbns.collect(isbnsToTitles)
      val isbnsAndTitles = isbns zip titles
      logger.info(s"ISBNs with no price:")
      for (isbn <- isbns) {
        logger.info(s"$isbn: ${isbnsToTitles(isbn)}")
      }
      true
    }
  }

  Config.parser.parse(args, Config()) match {
    case Some(config) =>
      logger.debug(s"GoodReads UserID: ${config.gooodReadsUserId}")
      logger.debug(s"GoodReads API Key: ${config.goodreadsAPIKey}")
      logger.debug(s"GoodReads Shelf: ${config.shelf}")
      logger.debug(s"Page Size: ${config.pageSize}")
      logger.debug(s"Providers: ${config.providers.mkString(",")}")
      logger.info("Creating database tables")
      val init = db.run(DBIO.seq(httpResponse.schema.create)) recover {
        case _ =>
          logger.warn("Failed to create database tables, probably because they already exist.")
          Unit
      }
      Await.result(init, Duration.Inf)
      logger.info("Performing valuation")
      valuate(config) onComplete { t =>
        db.close()
        System.exit(0)
      }
    case None =>
      logger.error("No config")
      db.close()
      System.exit(-1)

  }
}

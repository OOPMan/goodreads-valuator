package com.github.oopman.goodreads.valuator

import scopt._

case class Config(gooodReadsUserId: String = "", goodreadsAPIKey: String = "",
                  shelf: String = Config.defaultShelf, pageSize: String = Config.defaultPageSize,
                  chunkSize: Int = Config.defaultChunkSize,
                  providers: Seq[String] = Config.defaultProviders)

object Config {
  val defaultShelf = "read"
  val defaultPageSize = "100"
  val defaultChunkSize = 10
  val defaultProviders = ISBNUtils.priceProviders.keys.toSeq

  val parser = new OptionParser[Config]("goodreadsValuator") {
    head("goodreadsValuator", "1.0.0")

    opt[Int]('u', "goodReadsUserId")
      .required()
      .text("GoodReads UserID for Shelves")
      .action { (value, config) =>
        config.copy(gooodReadsUserId = value.toString)
      }

    opt[String]('k', "goodReadsAPIKey")
      .required()
      .text("GoodReads Developer API key. See https://www.goodreads.com/api/keys")
      .action { (value, config) =>
        config.copy(goodreadsAPIKey = value)
      }

    opt[String]('s', "shelf")
      .text(s"Shelf to valuate. Defaults to $defaultShelf")
      .action { (value, config) =>
        config.copy(shelf = value)
      }

    opt[Int]('p', "pageSize")
      .text(s"Page size value for GoodReads API calls. Defaults to $defaultPageSize")
      .action { (value, config) =>
        config.copy(pageSize = value.toString)
      }

    opt[Int]('c', "chunkSize")
      .text(s"Maximum number of HTTP requests to perform in parallel. Defaults to $defaultChunkSize")
      .action { (value, config) =>
        config.copy(chunkSize = value)
      }

    opt[Seq[String]]('P', "providers")
      .valueName("<provider1>,<provider2>...")
      .text(s"Ordered list of pricing providers to use. Defaults to ${defaultProviders.mkString(",")}")
      .action { (value, config) =>
        config.copy(providers =  value)
      }

    help("help").text("Usage notes")
  }
}

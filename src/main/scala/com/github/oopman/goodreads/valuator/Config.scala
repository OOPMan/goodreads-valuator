package com.github.oopman.goodreads.valuator

import scopt._

case class Config(gooodReadsUserId: String = "", goodreadsAPIKey: String = "",
                  shelf: String = "read", pageSize: String = "100",
                  chunkSize: Int = 10)

object Config {

  val parser = new OptionParser[Config]("goodreadsValuator") {
    head("goodreadsValuator", "0.1.0")

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
      .text("Shelf to valuate. Defaults to read")
      .action { (value, config) =>
        config.copy(shelf = value)
      }

    opt[Int]('p', "pageSize")
      .text("Page size value for GoodReads API calls. Defaults to 100")
      .action { (value, config) =>
        config.copy(pageSize = value.toString)
      }

    opt[Int]('c', "chunkSize")
      .text("Maximum number of HTTP requests to perform in parallel")
      .action { (value, config) =>
        config.copy(chunkSize = value)
      }

    help("help").text("Usage notes")
  }
}

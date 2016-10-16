package com.github.oopman.goodreads.valuator

import slick.driver.H2Driver.api._
import slick.lifted.{ForeignKeyQuery, ProvenShape}

/**
  * The HttpResponse table stores the results of performing HTTP queries using
  * the Dispatch library.
  *
  * @param tag
  */
class HttpResponse(tag: Tag) extends Table[(String, String)](tag, "HTTPRESPONSE") {
  def url: Rep[String] = column[String]("URL", O.PrimaryKey)
  def body: Rep[String] = column[String]("BODY")

  def * : ProvenShape[(String, String)] =
    (url, body)
}

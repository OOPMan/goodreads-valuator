name := """goodreads-valuator"""

version := "1.0.0"
maintainer := "Adam Jorgensen <adam.jorgensen.za@gmail.com>"
packageSummary := "GoodReads Valuator"
packageDescription := "A tool to valuate GoodReads Shelf contents"

mainClass in Compile := Some("com.github.oopman.goodreads.valuator.GoodreadsLibraryValueCalculator")

scalaVersion := "2.11.8"

libraryDependencies ++= List(
  "com.github.scopt" %% "scopt" % "3.5.0",
  "com.typesafe.scala-logging" %% "scala-logging" % "3.5.0",
  "ch.qos.logback" %  "logback-classic" % "1.1.7",
  "com.typesafe.slick" %% "slick" % "3.1.1",
  "com.h2database" % "h2" % "1.3.175",
  "org.scalatest" %% "scalatest" % "2.2.4" % "test",
  "net.databinder.dispatch" %% "dispatch-core" % "0.11.2",
  "net.ruippeixotog" %% "scala-scraper" % "1.0.0",
  "org.jsoup" % "jsoup" % "1.9.2",
  "org.scala-lang.modules" %% "scala-xml" % "1.0.4",
  "org.joda" % "joda-money" % "0.11",
  "jline" % "jline" % "2.12.1",
  "com.lihaoyi" % "ammonite" % "0.7.7" % "test" cross CrossVersion.full
)

initialCommands in (Test, console) := """ammonite.Main().run()"""

fork in run := true

enablePlugins(JavaAppPackaging)
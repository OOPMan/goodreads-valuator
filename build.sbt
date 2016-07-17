name := """goodreads-library-value-calculator"""

mainClass in Compile := Some("HelloSlick")

scalaVersion := "2.11.8"

libraryDependencies ++= List(
  "com.typesafe.slick" %% "slick" % "3.0.0",
  "org.slf4j" % "slf4j-nop" % "1.6.4",
  "com.h2database" % "h2" % "1.3.175",
  "org.scalatest" %% "scalatest" % "2.2.4" % "test",
  "net.databinder.dispatch" %% "dispatch-core" % "0.11.2",
  "net.ruippeixotog" %% "scala-scraper" % "1.0.0",
  "org.jsoup" % "jsoup" % "1.9.2"
)


fork in run := true
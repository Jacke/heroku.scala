import sbt._
import Keys._
import com.typesafe.sbt.SbtScalariform._

object Build extends Build {

  val buildSettings = Seq(
    organization := "com.heroku.api",
    version := "0.0.1-SNAPSHOT",
    scalaVersion := "2.10.2",
    crossScalaVersions := Seq("2.10.2"),
    resolvers ++= Seq("TypesafeMaven" at "http://repo.typesafe.com/typesafe/maven-releases",
      "whydoineedthis" at "http://repo.typesafe.com/typesafe/releases",
      "spray repo" at "http://repo.spray.io", "spray nightlies" at "http://nightlies.spray.io/")
  ) ++ Defaults.defaultSettings ++ scalariformSettings

  val api = Project(
    id = "api",
    base = file("api"),
    settings = buildSettings ++ Seq(libraryDependencies ++= apiDeps)
  )

  val spray_client = Project(
    id = "spray-client",
    base = file("spray-client"),
    settings = buildSettings ++ Seq(libraryDependencies ++= sprayDeps)
  ).dependsOn(api)

  val root = Project(id = "heroku-scala-project", base = file("."), settings = buildSettings).aggregate(api, spray_client)


  def apiDeps = Seq(scalaTest)

  def sprayDeps = Seq(spray, sprayJson, akka, scalaTest)


  val spray = "io.spray" % "spray-client" % "1.2-20130801" % "compile"
  val sprayJson = "io.spray" %% "spray-json" % "1.2.5"
  val akka = "com.typesafe.akka" %% "akka-actor" % "2.2.0" % "compile"
  val scalaTest = "org.scalatest" %% "scalatest" % "1.9.1" % "test"

}

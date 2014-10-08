import AssemblyKeys._

scalaVersion := "2.11.1"

libraryDependencies ++= Seq (
  "org.scala-lang.modules" %% "scala-async" % "0.9.2"
  ,"com.newrelic.agent.java" % "newrelic-api" % "3.11.0"
)

assemblySettings

mainClass in assembly := Some("NewRelicAgentTest")
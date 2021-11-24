name := "complex-data-protyping"

version := "0.1"

scalaVersion := "2.13.7"

scalacOptions in Global += "-Ymacro-annotations"

resolvers ++= Seq(
  Resolver.sonatypeRepo("releases"),
  Resolver.sonatypeRepo("snapshots")
)

testFrameworks += new TestFramework("munit.Framework")


libraryDependencies ++= Seq(
  "dev.optics" %% "monocle-core"  % "3.0.0",
  "dev.optics" %% "monocle-macro" % "3.0.0",

  "com.chuusai" %% "shapeless" % "2.3.3",

  "org.apache.spark" %% "spark-core" % "3.2.0",
  "org.apache.spark" %% "spark-sql" % "3.2.0",

  "org.scalameta" %% "munit" % "0.7.29" % Test
)

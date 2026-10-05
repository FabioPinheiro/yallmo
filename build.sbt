import scala.io.Source

ThisBuild / scalaVersion := "3.9.0"
ThisBuild / organization := "app.fmgp"
ThisBuild / version := "2.0.0-SNAPSHOT"
ThisBuild / versionScheme := Some("early-semver")
ThisBuild / scalacOptions ++= Seq(
  "-encoding",
  "UTF-8",
  "-source:future-migration",
  "-deprecation",
  "-unchecked",
  "-feature",
  // "-language:implicitConversions",
  "-Wunused:all",
  "-Werror",
)
ThisBuild / libraryDependencySchemes ++= Seq(
  "dev.zio" %% "zio-json" % "always",
)
// Applied to every forked application/test JVM, not just the sbt launcher.
ThisBuild / Compile / run / javaOptions ++= Seq(
  "--enable-native-access=ALL-UNNAMED",
  "--sun-misc-unsafe-memory-access=allow",
)
ThisBuild / Test / javaOptions ++= Seq(
  "--enable-native-access=ALL-UNNAMED",
  "--sun-misc-unsafe-memory-access=allow",
)

lazy val dotenvEnvironment = taskKey[Map[String, String]]("Load the local dotenv file")

/** Loads non-empty `KEY=VALUE` entries from a local dotenv file. */
def loadDotEnv(path: File): Map[String, String] =
  if !path.exists() then Map.empty
  else
    val source = Source.fromFile(path)
    try
      source
        .getLines()
        .map(_.trim)
        .filter(line => line.nonEmpty && !line.startsWith("#"))
        .flatMap { line =>
          line.split("=", 2).toList match
            case key :: value :: Nil
                if Set("MY_MODEL_URL", "MY_MODEL_NAME", "JEV_SYSTEM_ONE_URL", "TYPESAFE_API_KEY").contains(key) =>
              val cleaned = value
                .stripPrefix("\"")
                .stripSuffix("\"")
                .stripPrefix("'")
                .stripSuffix("'")
                .trim
              if cleaned.nonEmpty then Some(key.trim -> cleaned) else None
            case key :: value :: Nil => None
            case _ => None
        }
        .toMap
    finally source.close()

ThisBuild / dotenvEnvironment := Def.uncached {
  loadDotEnv((LocalRootProject / baseDirectory).value / ".env")
}

lazy val root = project
  .in(file("."))
  .aggregate(model, core, repl)
  .settings(
    name := "yallmo",
    publish / skip := true,
  )

lazy val model = project
  .in(file("model"))
  .settings(
    name := "yallmo-model",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % "2.1.24",
      "dev.zio" %% "zio-json" % "0.9.0",
      "org.scalameta" %% "munit" % "1.2.4" % Test,
    )
  )

lazy val core = project
  .in(file("core"))
  .dependsOn(model)
  .settings(
    name := "yallmo-core",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % "2.1.24",
      "dev.zio" %% "zio-http" % "3.0.1",
      "dev.zio" %% "zio-json" % "0.9.0",
      "com.lihaoyi" %% "upickle" % "4.3.2",
      "com.microsoft.onnxruntime" % "onnxruntime" % "1.22.0",
      "com.google.genai" % "google-genai" % "1.53.0",
      // JLine 4.0.14 is also the terminal stack used by the Scala 3.9.0 REPL.
      "org.jline" % "jline-reader" % "4.0.14",
      "org.jline" % "jline-terminal-jni" % "4.0.14",
      "org.scalameta" %% "munit" % "1.2.4" % Test,
    ),
    Compile / run / fork := true,
    Test / fork := true,
    Test / envVars := sys.env ++ dotenvEnvironment.value,
  )

lazy val repl = project
  .in(file("repl"))
  .dependsOn(core)
  .aggregate(core)
  .settings(
    name := "yallmo-repl",
    Compile / mainClass := Some("fmgp.yallmo.repl.ReplMain"),
    Compile / run / fork := true,
    Compile / run / envVars := sys.env ++ dotenvEnvironment.value,
    libraryDependencies ++= Seq(
      "org.scalameta" %% "munit" % "1.2.4" % Test,
      // Keep third-party SLF4J users silent without emitting the missing-provider warning.
      "org.slf4j" % "slf4j-nop" % "2.0.13" % Runtime,
    )
  )


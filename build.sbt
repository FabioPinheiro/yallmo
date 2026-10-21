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
      "dev.zio" %% "zio-streams" % "2.1.24",
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
  )

lazy val repl = project
  .in(file("repl"))
  .dependsOn(core)
  .aggregate(core)
  .settings(
    name := "yallmo-repl",
    Compile / mainClass := Some("fmgp.yallmo.repl.ReplMain"),
    Compile / resourceGenerators += Def.task {
      val assets = (LocalRootProject / baseDirectory).value / "browser-STT-TTS"
      val output = (Compile / resourceManaged).value / "browser-STT-TTS"
      (assets ** "*").get().filter(_.isFile).flatMap { file =>
        IO.relativize(assets, file).map { relative =>
          val target = output / relative
          IO.copyFile(file, target)
          target
        }
      }
    }.taskValue,
    Compile / run / fork := true,
    libraryDependencies ++= Seq(
      "org.scalameta" %% "munit" % "1.2.4" % Test,
      // Keep third-party SLF4J users silent without emitting the missing-provider warning.
      "org.slf4j" % "slf4j-nop" % "2.0.13" % Runtime,
    )
  )


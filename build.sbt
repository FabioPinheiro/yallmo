ThisBuild / scalaVersion := "3.10.0"
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
// JVM flags must not leak into Scala.js projects or test runners.
lazy val jvmSettings = Seq(
  // JVM-only security overrides also apply to tests; these do not add dependencies to Scala.js projects.
  // Revisit/remove these when Google Auth and Netty request patched versions themselves; never suppress the scan.
  dependencyOverrides ++= Seq(
    // Google Auth requests 33.4.0-jre: CVE-2026-102554 is fixed from 33.7.2; use the current 33.x JRE release.
    "com.google.guava" % "guava" % "33.8.0-jre",
    // ZIO HTTP/Netty requests 1.84: CVE-2026-8763 and CVE-2026-13506 are fixed from 1.85.
    // Override the entire provider/PKIX/utility family together to avoid mixed-version crypto classes.
    "org.bouncycastle" % "bcprov-jdk18on" % "1.86",
    "org.bouncycastle" % "bcpkix-jdk18on" % "1.86",
    "org.bouncycastle" % "bcutil-jdk18on" % "1.86",
  ),
  Compile / run / javaOptions ++= Seq(
    "--enable-native-access=ALL-UNNAMED",
    "--sun-misc-unsafe-memory-access=allow",
  ),
  Test / javaOptions ++= Seq(
    "--enable-native-access=ALL-UNNAMED",
    "--sun-misc-unsafe-memory-access=allow",
  ),
)

/** Stage packaged JVM applications and their complete runtime classpath; no sbt is needed to launch the image. */
lazy val dockerStage = taskKey[File]("Stage server and REPL runtime JARs in target/docker/lib")

lazy val root = project
  .in(file("."))
  .aggregate(
    modelJVM,
    modelJS,
    filesystemJVM,
    filesystemJS,
    scalaCodeApiJVM,
    scalaCodeApiJS,
    coreJVM,
    coreJS,
    protocolJVM,
    protocolJS,
    rpcJVM,
    rpcJS,
    clientHarnessJVM,
    clientHarnessJS,
    repl,
    server,
    webHarness
  )
  .settings(
    name := "yallmo",
    publish / skip := true,
    dockerStage / aggregate := false,
    dockerStage := Def.taskDyn {
      // Bundle first: otherwise the server JAR can be packaged before the generated website exists.
      val _ = (server / bundle).value
      Def.task {
        Def.uncached {
          val converter = fileConverter.value
          val jars =
            (repl / Runtime / fullClasspathAsJars).value.map(entry => converter.toPath(entry.data).toFile).distinct
          val output = (LocalRootProject / baseDirectory).value / "target/docker"
          IO.delete(output)
          IO.createDirectory(output / "lib")
          jars.zipWithIndex.foreach { case (jar, index) =>
            // Prefixes avoid filename collisions between independently packaged modules/dependencies.
            IO.copyFile(jar, output / "lib" / f"$index%04d-${jar.getName}")
          }
          output
        }
      }
    }.value,
  )

// /** Isolated capture-checking experiment; production modules must not depend on this nightly-compiled project yet. */
// lazy val capabilitiesLab = project
//   .in(file("capabilities-lab"))
//   .settings(
//     name := "yallmo-capabilities-lab",
//     publish / skip := true,
//     // Match lampepfl/atc's pinned compiler rather than tracking a moving nightly.
//     scalaVersion := "3.10.2-RC1-bin-20261015-88be2f5-NIGHTLY",
//     resolvers += "Scala nightlies" at "https://repo.scala-lang.org/artifactory/maven-nightlies/",
//     scalacOptions ++= Seq(
//       "-language:experimental.captureChecking",
//       "-language:experimental.modularity",
//     ),
//     libraryDependencies += "org.scalameta" %% "munit" % "1.2.4" % Test,
//     Test / fork := true,
//   )

// Explicit shared/jvm/js source sets; retain the existing JVM task IDs (model/test, core/test, ...).
lazy val model = crossProject(JSPlatform, JVMPlatform)
  .withoutSuffixFor(JVMPlatform)
  .crossType(CrossType.Full)
  .in(file("model"))
  .settings(
    name := "yallmo-model",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % "2.1.27",
      "dev.zio" %% "zio-streams" % "2.1.27",
      "dev.zio" %% "zio-json" % "1.1.1",
      "org.scalameta" %% "munit" % "1.2.4" % Test,
    ),
  )
  .jvmSettings(jvmSettings)
  .jsSettings(
    libraryDependencies ++= Seq(
      "io.github.cquiroz" %% "scala-java-time" % "2.8.0",
      "pt.kcry" %% "sha" % "2.0.2", // This is old!
      ("org.scala-js" %% "scalajs-java-securerandom" % "1.0.0").cross(CrossVersion.for3Use2_13),
    ),
  )
lazy val modelJVM = model.jvm
lazy val modelJS = model.js

/** Runtime API for restricted Scala snippets; compilation and execution remain JVM-only. */
lazy val scalaCodeApi = crossProject(JSPlatform, JVMPlatform)
  .withoutSuffixFor(JVMPlatform)
  .crossType(CrossType.Full)
  .in(file("scalacode-api"))
  .settings(
    name := "yallmo-scalacode-api",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % "2.1.27",
      "org.scalameta" %% "munit" % "1.2.4" % Test,
    ),
  )
  .jvmSettings(libraryDependencies += "dev.zio" %% "zio-json" % "1.1.1")
  .jsSettings(
    libraryDependencies += "io.github.cquiroz" %% "scala-java-time" % "2.8.0",
  )
lazy val scalaCodeApiJVM = scalaCodeApi.jvm
lazy val scalaCodeApiJS = scalaCodeApi.js

// Rooted capabilities/JSONL helpers are shared; FileSystem's NIO implementation is JVM-only.
lazy val filesystem = crossProject(JSPlatform, JVMPlatform)
  .withoutSuffixFor(JVMPlatform)
  .crossType(CrossType.Full)
  .in(file("filesystem"))
  .settings(
    name := "yallmo-filesystem",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % "2.1.27",
      "dev.zio" %% "zio-streams" % "2.1.27",
      "dev.zio" %% "zio-json" % "1.1.1",
      "org.scalameta" %% "munit" % "1.2.4" % Test,
    ),
  )
  .jvmSettings(jvmSettings)
lazy val filesystemJVM = filesystem.jvm
lazy val filesystemJS = filesystem.js

lazy val core = crossProject(JSPlatform, JVMPlatform)
  .withoutSuffixFor(JVMPlatform)
  .crossType(CrossType.Full)
  .in(file("core"))
  .dependsOn(model, filesystem, scalaCodeApi, rpc)
  .settings(
    name := "yallmo-core",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "upickle" % "4.3.2",
      "org.scalameta" %% "munit" % "1.2.4" % Test,
    ),
  )
  .jvmSettings(jvmSettings)
  .jvmSettings(
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio-http" % "3.11.6",
      "org.yaml" % "snakeyaml" % "2.4",
      "com.microsoft.onnxruntime" % "onnxruntime" % "1.22.0",
      // 1.53.0 pulled Jackson 2.17.2 with the reported DoS/deserialization CVEs; 1.80.0 uses fixed 2.18.11.
      // Stay on the compatible 1.x SDK, retaining its aligned Jackson modules instead of forcing individual JARs.
      "com.google.genai" % "google-genai" % "1.80.0",
    ),
    Compile / run / fork := true,
    Test / fork := true,
  )
lazy val coreJVM = core.jvm
lazy val coreJS = core.js

// Only domain values and wire codecs: no runtime, transport, credentials or HTTP dependencies.
lazy val protocol = crossProject(JSPlatform, JVMPlatform)
  .withoutSuffixFor(JVMPlatform)
  .crossType(CrossType.Full)
  .in(file("protocol"))
  .dependsOn(model)
  .settings(
    name := "yallmo-protocol",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio-json" % "1.1.1",
      "org.scalameta" %% "munit" % "1.2.4" % Test,
    ),
  )
  .jvmSettings(jvmSettings)
lazy val protocolJVM = protocol.jvm
lazy val protocolJS = protocol.js

/** Standalone typed RPC experiment: no orchestration/domain dependencies. */
lazy val rpc = crossProject(JSPlatform, JVMPlatform)
  .withoutSuffixFor(JVMPlatform)
  .crossType(CrossType.Full)
  .in(file("rpc"))
  .settings(
    name := "yallmo-rpc",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % "2.1.27",
      "dev.zio" %% "zio-streams" % "2.1.27",
      "dev.zio" %% "zio-json" % "1.1.1",
      "org.scalameta" %% "munit" % "1.2.4" % Test,
    ),
  )
  .jvmSettings(jvmSettings)
  .jvmSettings(
    libraryDependencies += "dev.zio" %% "zio-http" % "3.11.6",
    Test / fork := true,
  )
lazy val rpcJVM = rpc.jvm
lazy val rpcJS = rpc.js

/** ClientHarness: shared client interaction policy, independent of transports and server runtime. */
lazy val clientHarness = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Full)
  .in(file("client-harness"))
  .settings(
    name := "yallmo-client-harness",
    libraryDependencies += "org.scalameta" %% "munit" % "1.2.4" % Test,
  )
lazy val clientHarnessJVM = clientHarness.jvm
lazy val clientHarnessJS = clientHarness.js

lazy val repl = project
  .in(file("repl"))
  .dependsOn(coreJVM, server, clientHarnessJVM)
  .settings(jvmSettings)
  .settings(
    name := "yallmo-repl",
    Compile / mainClass := Some("fmgp.yallmo.repl.ReplMain"),
    Compile / resourceGenerators += Def.task {
      Def.uncached {
        val root = (LocalRootProject / baseDirectory).value
        val assets = root / "browser-STT-TTS"
        val output = (Compile / resourceManaged).value / "browser-STT-TTS"
        val sharedOutput = (Compile / resourceManaged).value / "web-harness"
        // Both source directories are external inputs; remove obsolete assets after moves.
        IO.delete(output)
        IO.delete(sharedOutput)
        val pageResources = (assets ** "*").get().filter(_.isFile).flatMap { file =>
          IO.relativize(assets, file).map { relative =>
            val target = output / relative
            IO.copyFile(file, target)
            target
          }
        }
        val audioResources = Seq("browser-audio.js", "audio-core.js", "capture-worklet.js").map { name =>
          val target = sharedOutput / "vite" / name
          IO.copyFile(root / "web-harness" / "vite" / name, target)
          target
        }
        pageResources ++ audioResources
      }
    }.taskValue,
    Compile / run / fork := true,
    libraryDependencies ++= Seq(
      // JLine 4.0.14 is also the terminal stack used by the Scala 3.9.0 REPL.
      "org.jline" % "jline-reader" % "4.0.14",
      "org.jline" % "jline-terminal-jni" % "4.0.14",
      "org.scalameta" %% "munit" % "1.2.4" % Test,
      // Keep third-party SLF4J users silent without emitting the missing-provider warning.
      "org.slf4j" % "slf4j-nop" % "2.0.13" % Runtime,
    )
  )

/** Explicit release tasks: ordinary backend compilation never invokes npm or the Scala.js linker. */
lazy val bundleFrontend = taskKey[Unit]("Link and bundle the view SPA (requires npm ci in web-harness/vite)")
lazy val bundle = taskKey[Unit]("Package the view server with the production SPA")

lazy val webHarness = project
  .in(file("web-harness"))
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(protocolJS, clientHarnessJS)
  .settings(
    name := "yallmo-web-harness",
    scalaJSUseMainModuleInitializer := true,
    scalaJSLinkerConfig ~= (_.withModuleKind(org.scalajs.linker.interface.ModuleKind.ESModule)),
    Compile / fastLinkJS / scalaJSLinkerOutputDirectory := baseDirectory.value / "target/scalajs-dev",
    Compile / fullLinkJS / scalaJSLinkerOutputDirectory := baseDirectory.value / "target/scalajs-prod",
    // sbt 2's %% includes the Scala.js platform suffix for this project.
    libraryDependencies ++= Seq(
      "com.raquo" %% "laminar" % "17.2.1",
      "dev.zio" %% "zio" % "2.1.27",
      "dev.zio" %% "zio-streams" % "2.1.27",
      "dev.zio" %% "zio-json" % "1.1.1",
      "org.scalameta" %% "munit" % "1.2.4" % Test,
    ),
    bundleFrontend := Def.uncached {
      val _ = (Compile / fullLinkJS).value
      val directory = baseDirectory.value / "vite"
      val result = scala.sys.process.Process(Seq("npm", "run", "build"), directory).!
      if (result != 0) sys.error("SPA bundling failed. Run npm ci in web-harness/vite first.")
      ()
    },
  )

lazy val server = project
  .in(file("server"))
  .dependsOn(coreJVM, protocolJVM)
  .settings(jvmSettings)
  .settings(
    name := "yallmo-server",
    Compile / mainClass := Some("fmgp.yallmo.server.OrchestratorServerMain"),
    Compile / run / fork := true,
    Test / fork := true,
    libraryDependencies += "org.scalameta" %% "munit" % "1.2.4" % Test,
    Compile / resourceGenerators += Def.task {
      Def.uncached {
        val assets = (LocalRootProject / baseDirectory).value / "web-harness/vite/dist"
        val output = (Compile / resourceManaged).value / "web-harness"
        // External Vite output is not an sbt input; inspect it on every resource pass.
        // Remove stale fingerprinted assets, including when dist has been removed.
        IO.delete(output)
        (assets ** "*").get().filter(_.isFile).flatMap { file =>
          IO.relativize(assets, file).map { relative =>
            val target = output / relative
            IO.copyFile(file, target)
            target
          }
        }
      }
    }.taskValue,
    bundle := Def.taskDyn {
      val _ = (webHarness / bundleFrontend).value
      Def.task {
        val _ = (Compile / packageBin).value
        ()
      }
    }.value,
  )

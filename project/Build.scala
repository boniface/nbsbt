import sbt._
import sbt.Keys._
import sbt.ScriptedPlugin._
import sbtrelease.ReleasePlugin._
import com.typesafe.sbt.SbtScalariform._

object Build extends Build {

  lazy val root = Project(
    "nbsbt",
    file("."),
    aggregate = Seq(nbsbtCore, nbsbtPlugin),
    settings = commonSettings ++ Seq(
      publishArtifact := false
    )
  )

  lazy val nbsbtCore = Project(
    "nbsbt-core",
    file("nbsbt-core"),
    settings = commonSettings ++ Seq(
      libraryDependencies ++= Seq(
        "org.scalaz"  %% "scalaz-core"  % "7.0.2",
        "org.scalaz" %% "scalaz-effect" % "7.0.2")
    )
  )

  lazy val nbsbtPlugin = Project(
    "nbsbt-plugin",
    file("nbsbt-plugin"),
    dependencies = Seq(nbsbtCore),
    settings = commonSettings
  )

  def commonSettings =
    Defaults.defaultSettings ++
  scalariformSettings ++
  scriptedSettings ++
  releaseSettings ++
  Seq(
    organization := "org.netbeans.nbsbt",
    // version is defined in version.sbt in order to support sbt-release
    scalacOptions ++= Seq("-unchecked", "-deprecation"),
    publishTo <<= isSnapshot { isSnapshot =>
      val id = if (isSnapshot) "snapshots" else "releases"
      val uri = "http://repo.scala-sbt.org/scalasbt/sbt-plugin-" + id
      Some(Resolver.url("sbt-plugin-" + id, url(uri))(Resolver.ivyStylePatterns))
    },
    sbtPlugin := true,
    publishMavenStyle := false,
    sbtVersion in GlobalScope <<= (sbtVersion in GlobalScope) { sbtVersion =>
      System.getProperty("sbt.build.version", sbtVersion)
    },
    // sbtBinaryVersion in GlobalScope <<= (sbtVersion in GlobalScope) { sbtVersion =>
    //   // This isn't needed once we start using SBT 0.13 to build
    //   if (CrossVersion.isStable(sbtVersion)) CrossVersion.binarySbtVersion(sbtVersion) else sbtVersion
    // },
    scalaVersion <<= (sbtVersion in GlobalScope) {
      case sbt013 if sbt013.startsWith("0.13.") => "2.10.3"
      case sbt012 if sbt012.startsWith("0.12.") => "2.9.3"
      case _ => "2.9.3"
    },
    sbtDependency in GlobalScope <<= (sbtDependency in GlobalScope, sbtVersion in GlobalScope) { (dep, sbtVersion) =>
      dep.copy(revision = sbtVersion)
    },
    publishArtifact in (Compile, packageDoc) := false,
    publishArtifact in (Compile, packageSrc) := false,
    scriptedLaunchOpts ++= List("-Xmx1024m", "-XX:MaxPermSize=256M")
  )
}

/*
 * Copyright 2011 Typesafe Inc.
 *
 * This work is based on the original contribution of WeigleWilczek.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.netbeans.nbsbt.core

import sbt.{
  Configuration,
  Configurations,
  File,
  Plugin,
  ProjectRef,
  ResolvedProject,
  Setting,
  SettingKey,
  State,
  TaskKey
}
import sbt.Keys.{ baseDirectory, commands }
import scala.util.control.Exception
import scala.xml.{ Attribute, Elem, MetaData, Node, Null, Text }
import scala.xml.transform.RewriteRule

object NetBeansPlugin extends NetBeansPlugin

trait NetBeansPlugin {

  def netbeansSettings: Seq[Setting[_]] = {
    import NetBeansKeys._
    Seq(
      commandName := "netbeans",
      commands <+= (commandName)(NetBeans.netbeansCommand))
  }

  object NetBeansKeys {
    import NetBeansOpts._

    val executionEnvironment: SettingKey[Option[NetBeansExecutionEnvironment.Value]] = SettingKey(
      prefix(ExecutionEnvironment),
      "The optional NetBeans execution environment.")

    val skipParents: SettingKey[Boolean] = SettingKey(
      prefix(SkipParents),
      "Skip creating NetBeans files for parent project?")

    val withSource: SettingKey[Boolean] = SettingKey(
      prefix(WithSource),
      "Download and link sources for library dependencies?")

    val useProjectId: SettingKey[Boolean] = SettingKey(
      prefix(UseProjectId),
      "Use the sbt project id as the NetBeans project name?")

    @deprecated("Use classpathTransformerFactories instead!", "2.1.0")
    val classpathEntryTransformerFactory: SettingKey[NetBeansTransformerFactory[Seq[NetBeansClasspathEntry] => Seq[NetBeansClasspathEntry]]] = SettingKey(
      prefix("classpathEntryTransformerFactory"),
      "Creates a transformer for classpath entries.")

    val classpathTransformerFactories: SettingKey[Seq[NetBeansTransformerFactory[RewriteRule]]] = SettingKey(
      prefix("classpathTransformerFactory"),
      "Factories for a rewrite rule for the .classpath file.")

    val projectTransformerFactories: SettingKey[Seq[NetBeansTransformerFactory[RewriteRule]]] = SettingKey(
      prefix("projectTransformerFactory"),
      "Factories for a rewrite rule for the .project file.")

    val commandName: SettingKey[String] = SettingKey(
      prefix("command-name"),
      "The name of the command.")

    val configurations: SettingKey[Set[Configuration]] = SettingKey(
      prefix("configurations"),
      "The configurations to take into account.")

    val createSrc: SettingKey[NetBeansCreateSrc.ValueSet] = SettingKey(
      prefix("create-src"),
      "The source kinds to be included.")

    val projectFlavor: SettingKey[NetBeansProjectFlavor.Value] = SettingKey(
      prefix("project-flavor"),
      "The flavor of project (Scala or Java) to build.")

    val netbeansOutput: SettingKey[Option[String]] = SettingKey(
      prefix("netbeans-output"),
      "The optional output for NetBeans.")

    val preTasks: SettingKey[Seq[TaskKey[_]]] = SettingKey(
      prefix("pre-tasks"),
      "The tasks to be evaluated prior to creating the NetBeans project definition.")

    val relativizeLibs: SettingKey[Boolean] = SettingKey(
      prefix("relativize-libs"),
      "Relativize the paths to the libraries?")

    val skipProject: SettingKey[Boolean] = SettingKey(
      prefix("skipProject"),
      "Skip creating NetBeans files for a given project?")

    private def prefix(key: String) = "netbeans-" + key
  }

  object NetBeansExecutionEnvironment extends Enumeration {

    val JavaSE17 = Value("JavaSE-1.7")

    val JavaSE16 = Value("JavaSE-1.6")

    val J2SE15 = Value("J2SE-1.5")

    val J2SE14 = Value("J2SE-1.4")

    val J2SE13 = Value("J2SE-1.3")

    val J2SE12 = Value("J2SE-1.2")

    val JRE11 = Value("JRE-1.1")

    val valueSeq: Seq[Value] = JavaSE17 :: JavaSE16 :: J2SE15 :: J2SE14 :: J2SE13 :: J2SE12 :: JRE11 :: Nil
  }

  sealed trait NetBeansClasspathEntry {
    def toXml: Node
    def toXmlNetBeans: Node
  }

  object NetBeansClasspathEntry {

    case class Src(scope: String, path: String, output: String, managed: Boolean) extends NetBeansClasspathEntry {
      override def toXml = <classpathentry kind="src" path={ path } output={ output }/>
      override def toXmlNetBeans = <classpathentry kind="src" path={ path } output={ output } scope={ scope } managed={ managed.toString }/>
    }

    case class Link(scope: String, name: String, output: String, managed: Boolean) extends NetBeansClasspathEntry {
      override def toXml = <classpathentry kind="link" name={ name } output={ output }/>
      override def toXmlNetBeans = <classpathentry kind="src" name={ name } output={ output } scope={ scope } managed={ managed.toString }/>
    }

    case class Lib(scope: String, path: String, sourcePath: Option[String] = None) extends NetBeansClasspathEntry {
      override def toXml =
        sourcePath.foldLeft(<classpathentry kind="lib" path={ path }/>)((xml, sp) =>
          xml % Attribute("sourcepath", Text(sp), Null))
      override def toXmlNetBeans =
        sourcePath.foldLeft(<classpathentry kind="lib" path={ path } scope={ scope }/>)((xml, sp) =>
          xml % Attribute("sourcepath", Text(sp), Null))
    }

    case class Project(scope: String, name: String, path: String, output: String) extends NetBeansClasspathEntry {
      override def toXml =
        <classpathentry kind="src" path={ "/" + name } exported="true" combineaccessrules="false"/>
      override def toXmlNetBeans =
        <classpathentry kind="src" path={ name } exported="true" combineaccessrules="false" base={ path } output={ output } scope={ scope }/>
    }

    case class AggProject(name: String, path: String) extends NetBeansClasspathEntry {
      override def toXml =
        <classpathentry kind="agg" path={ "/" + name }/>
      override def toXmlNetBeans =
        <classpathentry kind="agg" path={ name } base={ path }/>
    }

    case class Con(path: String) extends NetBeansClasspathEntry {
      override def toXml = <classpathentry kind="con" path={ path }/>
      override def toXmlNetBeans = <classpathentry kind="con" path={ path }/>
    }

    case class Output(path: String) extends NetBeansClasspathEntry {
      override def toXml = <classpathentry kind="output" path={ path }/>
      override def toXmlNetBeans = <classpathentry kind="output" path={ path }/>
    }
  }

  object NetBeansCreateSrc extends Enumeration {

    val Unmanaged = Value

    val Managed = Value

    val Source = Value

    val Resource = Value

    val Default = ValueSet(Unmanaged, Source)

    val AllSources = ValueSet(Unmanaged, Managed, Source)

    val All = ValueSet(Unmanaged, Managed, Source, Resource)
  }

  object NetBeansProjectFlavor extends Enumeration {

    val Scala = Value

    val Java = Value
  }

  trait NetBeansTransformerFactory[A] {
    def createTransformer(ref: ProjectRef, state: State): Validation[A]
  }

  object NetBeansClasspathEntryTransformerFactory {

    object Identity extends NetBeansTransformerFactory[Seq[NetBeansClasspathEntry] => Seq[NetBeansClasspathEntry]] {
      import scalaz.Scalaz._
      override def createTransformer(
        ref: ProjectRef,
        state: State): Validation[Seq[NetBeansClasspathEntry] => Seq[NetBeansClasspathEntry]] = {
        val transformer = (entries: Seq[NetBeansClasspathEntry]) => entries
        transformer.success
      }
    }
  }

  object NetBeansRewriteRuleTransformerFactory {

    object IdentityRewriteRule extends RewriteRule {
      override def transform(node: Node): Node = node
    }

    object ClasspathDefaultRule extends RewriteRule {

      private val CpEntry = "classpathentry"

      private val ScalaContainer = "org.scala-ide.sdt.launching.SCALA_CONTAINER"

      private val ScalaCompilerContainer = "org.scala-ide.sdt.launching.SCALA_COMPILER_CONTAINER"

      override def transform(node: Node): Seq[Node] = node match {
        case Elem(pf, CpEntry, attrs, scope, child @ _*) if isScalaLibrary(attrs) =>
          Elem(pf, CpEntry, container(ScalaContainer), scope, child: _*)
        case Elem(pf, CpEntry, attrs, scope, child @ _*) if isScalaCompiler(attrs) =>
          Elem(pf, CpEntry, container(ScalaCompilerContainer), scope, child: _*)
        case other =>
          other
      }

      private def container(name: String) =
        Attribute("kind", Text("con"), Attribute("path", Text(name), Null))

      private def isScalaLibrary(metaData: MetaData) =
        metaData("kind") == Text("lib") &&
          (Option(metaData("path").text) map (_ contains "scala-library.jar") getOrElse false)

      private def isScalaCompiler(metaData: MetaData) =
        metaData("kind") == Text("lib") &&
          (Option(metaData("path").text) map (_ contains "scala-compiler.jar") getOrElse false)
    }

    object Identity extends NetBeansTransformerFactory[RewriteRule] {
      import scalaz.Scalaz._
      override def createTransformer(ref: ProjectRef, state: State): Validation[RewriteRule] =
        IdentityRewriteRule.success
    }

    object ClasspathDefault extends NetBeansTransformerFactory[RewriteRule] {
      import scalaz.Scalaz._
      override def createTransformer(ref: ProjectRef, state: State): Validation[RewriteRule] =
        ClasspathDefaultRule.success
    }
  }
}

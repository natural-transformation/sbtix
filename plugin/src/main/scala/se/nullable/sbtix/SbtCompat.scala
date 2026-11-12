package se.nullable.sbtix

import java.io.File
import java.util.concurrent.ConcurrentSkipListSet

import scala.jdk.CollectionConverters._

import sbt.{Classpaths, Configuration, Credentials, Resolver}
import sbt.Keys.Classpath
import sbt.internal.librarymanagement.ivy.IvyCredentials
import sbt.librarymanagement.UpdateReport

private[sbtix] object SbtCompat {
  type ResolverAuthentication = coursier.core.Authentication

  final case class DirectCredential(userName: String, passwd: String, realm: String)

  def uncached[A](body: => A): A =
    sbt.Def.uncached(body)

  def ivyLocalResolver(name: String, baseDirectory: File): Resolver =
    Resolver.file(name, baseDirectory)(using Resolver.ivyStylePatterns)

  def concurrentSetToSet[A](set: ConcurrentSkipListSet[A]): Set[A] =
    set.asScala.toSet

  def managedJars(
      config: Configuration,
      jarTypes: Set[String],
      report: UpdateReport
  )(implicit converter: xsbti.FileConverter): Classpath =
    Classpaths.managedJars(config, jarTypes, report, converter)

  def credentialForHost(credentials: Seq[Credentials], host: String): Option[DirectCredential] =
    IvyCredentials
      .forHost(credentials, host)
      .map(c => DirectCredential(c.userName, c.passwd, c.realm))

  def resolverAuthentication(
      user: String,
      password: String,
      optional: Boolean,
      realmOpt: Option[String],
      httpsOnly: Boolean,
      passOnRedirect: Boolean
  ): ResolverAuthentication =
    coursier.core.Authentication(
      user = user,
      passwordOpt = Some(password),
      httpHeaders = Nil,
      optional = optional,
      realmOpt = realmOpt,
      httpsOnly = httpsOnly,
      passOnRedirect = passOnRedirect
    )
}

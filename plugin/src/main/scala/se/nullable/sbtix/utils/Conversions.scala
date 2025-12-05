package se.nullable.sbtix.utils

object Conversions {
  implicit def convert(auth: lmcoursier.definitions.Authentication): coursier.core.Authentication =
    coursier.core.Authentication(
      user = auth.user,
      password = auth.password,
      optional = auth.optional,
      realmOpt = auth.realmOpt,
      httpsOnly = auth.httpsOnly,
      passOnRedirect = auth.passOnRedirect
    )

  /**
   * @param repository
   *   of type 'Any' because the shaded types are not accessible during
   *   compilation
   */
  def convertRepository(repository: Any): coursier.core.Repository = {
    /* sbt ships shaded coursier repositories; reflect over them and rebuild public instances
     * so the 2.1.24 API surface can consume them.
     */
    /**
     * Needs reflection because the sbt shading hides these classes from the
     * classpath:
     */
    val className = repository.getClass.getName
    if (className.endsWith("SbtMavenRepository")) {
      val reflectiveRepo = repository.asInstanceOf[{
        val root: String
        val authentication: Option[Any]
      }]
      coursier.maven.SbtMavenRepository(
        reflectiveRepo.root,
        reflectiveRepo.authentication.map(convertAuthentication)
      )
    } else if (className.endsWith("IvyRepository")) {
      val reflectiveIvyRepo = repository.asInstanceOf[{
        val pattern: Any
        val metadataPatternOpt: Option[Any]
        val changingOpt: Option[Boolean]
        val withChecksums: Boolean
        val withSignatures: Boolean
        val withArtifacts: Boolean
        val dropInfoAttributes: Boolean
        val authentication: Option[Any]
        val versionsCheckHasModule: Boolean
      }]
      coursier.ivy.IvyRepository(
        convertPattern(reflectiveIvyRepo.pattern),
        reflectiveIvyRepo.metadataPatternOpt.map(convertPattern),
        reflectiveIvyRepo.changingOpt,
        reflectiveIvyRepo.withChecksums,
        reflectiveIvyRepo.withSignatures,
        reflectiveIvyRepo.withArtifacts,
        reflectiveIvyRepo.dropInfoAttributes,
        reflectiveIvyRepo.authentication.map(convertAuthentication),
        reflectiveIvyRepo.versionsCheckHasModule
      )
    } else if (className.endsWith("MavenRepository")) {
      val reflectiveMavenRepo = repository.asInstanceOf[{
        val root: String
        val authentication: Option[Any]
      }]
      coursier.maven.MavenRepository(
        reflectiveMavenRepo.root,
        reflectiveMavenRepo.authentication.map(convertAuthentication)
      )
    } else {
      throw new IllegalStateException(s"Unhandled repository type: $className")
    }
  }

  private def convertAuthentication(authentication: Any): coursier.core.Authentication = {
    val reflectiveAuth = authentication.asInstanceOf[{
      val user: String
      val passwordOpt: Option[String]
      val httpHeaders: Seq[(String, String)]
      val optional: Boolean
      val realmOpt: Option[String]
      val httpsOnly: Boolean
      val passOnRedirect: Boolean
    }]
    coursier.core.Authentication(
      reflectiveAuth.user,
      reflectiveAuth.passwordOpt,
      reflectiveAuth.httpHeaders,
      reflectiveAuth.optional,
      reflectiveAuth.realmOpt,
      reflectiveAuth.httpsOnly,
      reflectiveAuth.passOnRedirect
    )
  }

  private def convertPattern(pattern: Any): coursier.ivy.Pattern = {
    def convertChunk(chunk: Any): coursier.ivy.Pattern.Chunk =
      if (chunk.getClass.getName.endsWith("Var"))
        coursier.ivy.Pattern.Chunk.Var(chunk.asInstanceOf[{ val name: String }].name)
      else if (chunk.getClass.getName.endsWith("Opt"))
        coursier.ivy.Pattern.Chunk.Opt(chunk.asInstanceOf[{ val content: Seq[Any] }].content.map(convertChunk))
      else if (chunk.getClass.getName.endsWith("Const"))
        coursier.ivy.Pattern.Chunk.Const(chunk.asInstanceOf[{ val value: String }].value)
      else
        throw new IllegalArgumentException(s"Could not convert chunk $chunk of type ${chunk.getClass.getName}")

    coursier.ivy.Pattern(pattern.asInstanceOf[{ val chunks: Seq[Any] }].chunks.map(convertChunk))
  }

  implicit def convertOrganization(org: lmcoursier.definitions.Organization): coursier.core.Organization =
    coursier.core.Organization(value = org.value)

  implicit def convertModuleName(name: lmcoursier.definitions.ModuleName): coursier.core.ModuleName =
    coursier.core.ModuleName(value = name.value)

  implicit def convertConfiguration(conf: lmcoursier.definitions.Configuration): coursier.core.Configuration =
    coursier.core.Configuration(value = conf.value)

  implicit def convertType(t: lmcoursier.definitions.Type): coursier.core.Type =
    coursier.core.Type(value = t.value)

  implicit def convertClassifier(clas: lmcoursier.definitions.Classifier): coursier.core.Classifier =
    coursier.core.Classifier(value = clas.value)

  implicit def convertExtension(ext: lmcoursier.definitions.Extension): coursier.core.Extension =
    coursier.core.Extension(value = ext.value)

  implicit def convert(mod: lmcoursier.definitions.Module): coursier.core.Module =
    coursier.core.Module(
      organization = mod.organization,
      name = mod.name,
      attributes = mod.attributes
    )

  implicit def convert(pub: lmcoursier.definitions.Publication): coursier.core.Publication =
    coursier.core.Publication(
      name = pub.name,
      `type` = pub.`type`,
      ext = pub.ext,
      classifier = pub.classifier
    )

  implicit def convert(dep: lmcoursier.definitions.Dependency): coursier.core.Dependency =
    coursier.core.Dependency(
      module = dep.module,
      version = dep.version,
      configuration = dep.configuration,
      exclusions = dep.exclusions.map { case (org, name) =>
        (convertOrganization(org), convertModuleName(name))
      },
      publication = dep.publication,
      optional = dep.optional,
      transitive = dep.transitive
    )

}
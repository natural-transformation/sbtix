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

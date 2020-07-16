package se.nullable.sbtix

import java.io.{IOException}
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Semaphore

import sbt.Logger

import scala.util.{Failure, Success, Try}

object FindArtifactsOfRepo {
  private val semaphore = new Semaphore(4, false)

  def fetchChecksum(originalUrl: String,
                    artifactType: String,
                    url: URL): Try[String] = {
    semaphore.acquireUninterruptibly()
    val checksum = calculateChecksum(url)
    semaphore.release()

    checksum
  }

  private def calculateChecksum(url: URL): Try[String] = {
    try {
      val hash = MessageDigest getInstance "SHA-256"
      val is = url.openConnection.getInputStream()
      val buf = Array.ofDim[Byte](1048576)
      try {
        var readLen: Int = 0
        do {
          hash.update(buf, 0, readLen)
          readLen = is.read(buf)
        } while (readLen != -1)
      } finally {
        is.close()
      }
      val stringHash = hash.digest().map("%02X".format(_)).mkString
      // TODO: Replace with logger
      // println(s"sbtix: hash of $url => $stringHash")
      Success(stringHash)
    } catch {
      case e: IOException =>
        Failure(e)
    }
  }
}

class FindArtifactsOfRepo(repoName: String, root: String) {

  def findArtifacts(logger: Logger,
                    modules: Set[GenericModule]): Set[NixArtifact] =
    modules.flatMap { ga =>
      val rootUrl = new URL(root)

      val authedRootURI = ga.authed(rootUrl) //authenticated version of the rootUrl

      val allArtifacts = recursiveListFiles(ga.localSearchLocation)
      //get list of files at location
      val targetArtifacts = allArtifacts.filter(
        f =>
          """.*(\.jar|\.pom|ivy.xml)$""".r
            .findFirstIn(f.getName)
            .isDefined) //filter for interesting files

      targetArtifacts.map { artifactLocalFile =>
        val calcUrl = ga.calculateURI(artifactLocalFile).toURL

        NixArtifact(
          repoName,
          calcUrl.toString.replace(authedRootURI.toString, "").stripPrefix("/"),
          calcUrl.toString,
          FindArtifactsOfRepo
            .fetchChecksum(calcUrl.toString,
                           "Artifact",
                           artifactLocalFile.toURI.toURL)
            .get
        )
      }
    }

  def findMetaArtifacts(logger: Logger,
                        metaArtifacts: Set[MetaArtifact]): Set[NixArtifact] = {

    val targetMetaArtifacts = metaArtifacts.filter(f =>
      """.*(\.jar|\.pom|ivy.xml)$""".r.findFirstIn(f.artifactUrl).isDefined)

    targetMetaArtifacts.map { meta =>
      NixArtifact(
        repoName = repoName,
        relative = meta.artifactUrl.replace(root, "").stripPrefix("/"),
        url = meta.artifactUrl,
        sha256 = meta.checkSum
      )
    }
  }

}

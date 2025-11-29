package sbtix.internal

import java.io.{File, PrintWriter}
// import coursier.core.Artifact // Previous attempt
import coursier.util.Artifact // Correct import based on v2.1.17 source
import coursier.core.{Authentication, Attributes, Type, Classifier}
import upickle.default._
import upickle.default.{ReadWriter => RW, macroRW => MMW} // Alias for brevity below if needed

object ArtifactWriter {

  // Define ReadWriters for Coursier value classes (assuming they wrap String)
  implicit val typeRW: RW[Type] = readwriter[String].bimap[Type](_.value, Type(_))
  implicit val classifierRW: RW[Classifier] = readwriter[String].bimap[Classifier](_.value, Classifier(_))

  // Define custom serialization for Authentication
  implicit val authRW: RW[Authentication] = readwriter[ujson.Value].bimap[Authentication](
    auth => {
      ujson.Obj(
        "user" -> writeJs(auth.user),
        "password" -> writeJs(auth.passwordOpt.getOrElse("")),
        "httpHeaders" -> writeJs(auth.httpHeaders.toList),
        "optional" -> writeJs(auth.optional),
        "realm" -> writeJs(auth.realmOpt.getOrElse("")),
        "httpsOnly" -> writeJs(auth.httpsOnly),
        "passOnRedirect" -> writeJs(auth.passOnRedirect)
      )
    },
    json => {
      val obj = json.obj
      Authentication(
        user = read[String](obj("user")),
        passwordOpt = if (obj.contains("password") && !obj("password").str.isEmpty) Some(read[String](obj("password"))) else None,
        realmOpt = if (obj.contains("realm") && !obj("realm").str.isEmpty) Some(read[String](obj("realm"))) else None,
        optional = read[Boolean](obj("optional")),
        httpHeaders = read[List[(String, String)]](obj("httpHeaders")).toSeq,
        httpsOnly = read[Boolean](obj("httpsOnly")),
        passOnRedirect = read[Boolean](obj("passOnRedirect"))
      )
    }
  )

  // Attributes RW might not be directly needed for util.Artifact, but keep for potential nested use in Auth
  implicit val attrRW: RW[Attributes] = MMW[Attributes]

  // Define ReadWriter for coursier.util.Artifact
  // Based on error messages, assumes util.Artifact has url, changing, authentication, extra
  implicit val artifactRW: RW[Artifact] = readwriter[ujson.Value].bimap[Artifact](
    artifact => {
      // Convert Artifact to a ujson.Obj
      val obj = ujson.Obj(
        "url" -> writeJs(artifact.url),
        "changing" -> writeJs(artifact.changing),
        "extra" -> ujson.Obj() // Represent empty extra for now
      )
      
      // Only include authentication if it exists
      artifact.authentication match {
        case Some(auth) => obj("authentication") = writeJs(auth)
        case None => obj("authentication") = ujson.Null
      }
      
      obj
    },
    json => {
      // Convert ujson.Value back to Artifact
      val obj = json.obj
      // Match the full constructor signature with defaults for missing fields
      coursier.util.Artifact(
        url = read[String](obj("url")),
        checksumUrls = Map.empty[String, String], // Provide default
        extra = Map.empty[String, Artifact],      // Provide default with correct type
        changing = read[Boolean](obj("changing")),
        optional = false,                         // Provide default
        authentication = if (obj("authentication").isNull) None else read[Option[Authentication]](obj("authentication"))
      )
    }
  )


  /**
   * Writes artifact metadata to a file in JSON format with indentation.
   *
   * @param artifact The artifact whose metadata will be written.
   * @param target The file to write the JSON metadata to.
   */
  def write(artifact: Artifact, target: File): Unit = {
    val parentDir = target.getParentFile
    if (parentDir != null && !parentDir.exists()) {
      parentDir.mkdirs() // Create parent directories if they don't exist
    }

    val writer = new PrintWriter(target)
    try {
      // Serialize the artifact to ujson.Value first
      val jsonValue = upickle.default.writeJs(artifact)
      // Use ujson.write for pretty printing
      val jsonString = ujson.write(jsonValue, indent = 2)
      writer.print(jsonString)
    } finally {
      writer.close()
    }
  }
} 
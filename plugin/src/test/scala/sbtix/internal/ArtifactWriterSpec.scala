package sbtix.internal

import java.io.File
import java.nio.file.Files

import coursier.util.Artifact
import coursier.core.{Attributes, Authentication}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default._

class ArtifactWriterSpec extends AnyFlatSpec with Matchers {

  // Helper to read JSON from a file and parse it
  def readJsonFromFile(file: File): ujson.Value = {
    val content = new String(Files.readAllBytes(file.toPath))
    upickle.default.read[ujson.Value](content)
  }

  "ArtifactWriter" should "write artifact metadata correctly to a JSON file" in {
    val tempFile = Files.createTempFile("artifact-writer-test", ".json").toFile
    tempFile.deleteOnExit()

    val sampleArtifact = Artifact(
      url = "http://example.com/artifact.jar",
      checksumUrls = Map.empty[String, String],
      extra = Map.empty[String, Artifact],
      changing = true,
      optional = false,
      authentication = Some(Authentication("user", "password"))
    )

    ArtifactWriter.write(sampleArtifact, tempFile)

    val jsonOutput = readJsonFromFile(tempFile)

    // Verify the JSON content
    jsonOutput("url").str shouldBe sampleArtifact.url
    jsonOutput("changing").bool shouldBe sampleArtifact.changing

    // Verify authentication object
    val authObj = jsonOutput("authentication").obj
    authObj("user").str shouldBe "user"
    authObj("password").str shouldBe "password"

    // Verify 'extra' field IS present (as empty object for now)
    jsonOutput("extra").obj.isEmpty shouldBe true
  }

  it should "create parent directories if they do not exist" in {
    val tempDir = Files.createTempDirectory("artifact-writer-parent-test").toFile
    tempDir.deleteOnExit()
    val targetFile = new File(tempDir, "subdir/artifact.meta.json")
    targetFile.getParentFile.delete() // Ensure parent does not exist initially
    targetFile.deleteOnExit()
    targetFile.getParentFile.deleteOnExit()

    val sampleArtifact = Artifact(
      url = "http://example.com/minimal.jar",
      checksumUrls = Map.empty[String, String],
      extra = Map.empty[String, Artifact],
      changing = false,
      optional = false,
      authentication = None
    )

    noException should be thrownBy ArtifactWriter.write(sampleArtifact, targetFile)

    targetFile.exists() should be (true)
    targetFile.getParentFile.exists() should be (true)

    // Verify basic JSON structure for the minimal artifact
    val jsonOutput = readJsonFromFile(targetFile)
    jsonOutput("url").str shouldBe sampleArtifact.url
    jsonOutput("changing").bool shouldBe false
    jsonOutput("authentication").isNull shouldBe true
    jsonOutput("extra").obj.isEmpty shouldBe true // Check extra is present and empty
  }
} 
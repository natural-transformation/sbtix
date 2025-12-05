package se.nullable.sbtix

import java.io.{File, FileOutputStream, FileInputStream}
import java.net.{HttpURLConnection, URL}
import java.nio.channels.Channels
import java.nio.file.{Files, Path}
import java.security.{DigestInputStream, MessageDigest}
import scala.annotation.tailrec
import scala.util.{Failure, Success, Try}

/**
 * Utility methods for sbtix
 */
object Utils {
  /**
   * Download a file from a URL to a temporary file
   */
  def downloadFile(url: String): File = {
    val tempFile = File.createTempFile("sbtix", ".tmp")
    tempFile.deleteOnExit()
    
    val connection = new URL(url).openConnection().asInstanceOf[HttpURLConnection]
    connection.setRequestMethod("GET")
    connection.setConnectTimeout(5000)
    connection.setReadTimeout(5000)
    
    val in = connection.getInputStream
    val out = new FileOutputStream(tempFile)
    val channel = Channels.newChannel(in)
    
    try {
      out.getChannel.transferFrom(channel, 0, Long.MaxValue)
      tempFile
    } finally {
      out.close()
      in.close()
    }
  }
  
  /**
   * Computes the SHA256 hash of a file.
   *
   * @param file The file to hash
   * @return The hex-encoded SHA256 hash
   */
  def computeSha256(file: File): String = {
    val md = MessageDigest.getInstance("SHA-256")
    val is = new FileInputStream(file)
    
    try {
      val dis = new DigestInputStream(is, md)
      val buffer = new Array[Byte](8192)
      
      while (dis.read(buffer) != -1) {}
      
      dis.close()
      
      md.digest().map("%02x".format(_)).mkString
    } finally {
      is.close()
    }
  }
  
  /**
   * Convert byte array to hex string
   */
  def bytesToHex(bytes: Array[Byte]): String = {
    val hexChars = Array('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f')
    val result = new StringBuilder(bytes.length * 2)
    
    for (b <- bytes) {
      result.append(hexChars((b & 0xF0) >> 4))
      result.append(hexChars(b & 0x0F))
    }
    
    result.toString
  }
  
  /**
   * Retry an operation with exponential backoff
   */
  @tailrec
  def retry[T](maxRetries: Int, delayMs: Long)(fn: => T): T = {
    Try(fn) match {
      case Success(result) => result
      case Failure(e) if maxRetries > 0 =>
        Thread.sleep(delayMs)
        retry(maxRetries - 1, delayMs * 2)(fn)
      case Failure(e) => throw e
    }
  }
  
  /**
   * Clean a URL for use as a filename or identifier
   */
  def urlToName(url: String): String = {
    url.replaceAll("[^a-zA-Z0-9.-]", "_")
  }
  
  /**
   * Extract artifact name from URL
   */
  def artifactNameFromUrl(url: String): String = {
    val lastSlash = url.lastIndexOf('/')
    if (lastSlash >= 0 && lastSlash < url.length - 1) {
      url.substring(lastSlash + 1)
    } else {
      urlToName(url)
    }
  }
} 
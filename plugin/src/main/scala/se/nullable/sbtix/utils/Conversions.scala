package se.nullable.sbtix.utils

/**
 * Utility methods for converting between types.
 */
object Conversions {
  /**
   * Convert coursier Authentication to coursier.core Authentication
   */
  def convertAuthentication(auth: Any): coursier.core.Authentication = {
    val user = "anonymous"
    val password = ""
    coursier.core.Authentication(user, password)
  }
} 
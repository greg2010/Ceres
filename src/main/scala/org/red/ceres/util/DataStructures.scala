package org.red.ceres.util


import moe.pizza.eveapi.ApiKey
import org.red.iris.PermissionBit

import scala.language.implicitConversions


sealed trait CeresCredentials

case class CeresLegacyCredentials(apiKey: ApiKey,
                                  characterId: Option[Long],
                                  name: Option[String]) extends CeresCredentials

case class CeresSSOCredentials(refreshToken: String, accessToken: Option[String]) extends CeresCredentials

case class SSOAuthCode(code: String)

case class PermissionBitEntry(name: String, bit_position: Int, description: String) {
  def toPermissionBit: PermissionBit = {
    PermissionBit(
      name = name,
      bitPosition = bit_position,
      description = description
    )
  }
}
package org.red.ceres.util


import moe.pizza.eveapi.ApiKey
import org.red.iris.PermissionBit

import scala.language.implicitConversions


sealed trait CeresCredential

case class CeresLegacyCredential(apiKey: ApiKey,
                                 characterId: Option[Long],
                                 name: Option[String]) extends CeresCredential

case class CeresSSOCredential(refreshToken: String, accessToken: String) extends CeresCredential

case class PermissionBitEntry(name: String, bit_position: Int, description: String) {
  def toPermissionBit: PermissionBit = {
    PermissionBit(
      name = name,
      bitPosition = bit_position,
      description = description
    )
  }
}
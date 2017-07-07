package org.red.ceres.util


import moe.pizza.eveapi.ApiKey


sealed trait CeresCredentials

case class CeresLegacyCredentials(apiKey: ApiKey,
                                  characterId: Option[Long],
                                  name: Option[String]) extends CeresCredentials

case class CeresSSOCredentials(refreshToken: String, accessToken: Option[String]) extends CeresCredentials

case class SSOAuthCode(code: String)

case class PermissionBitEntry(name: String, bit_position: Int, description: String)

package org.red.ceres.util.converters

import org.red.db.models.Coalition.EveUserViewRow
import org.red.iris.EveUserData

import scala.language.implicitConversions

package object db {

  implicit class RichEveUserData(val eveUserData: EveUserData.type ) extends AnyVal {
    implicit def apply(usersViewRow: EveUserViewRow): EveUserData = {
      EveUserData(
        characterId = usersViewRow.characterId.get,
        characterName = usersViewRow.characterName.get,
        corporationId = usersViewRow.corporationId.get,
        corporationName = usersViewRow.corporationName.get,
        corporationTicker = usersViewRow.corporationTicker.get,
        allianceId = usersViewRow.allianceId,
        allianceName = usersViewRow.allianceName,
        allianceTicker = usersViewRow.allianceTicker
      )
    }
  }
}

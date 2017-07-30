package org.red.ceres.util.converters

import org.red.ceres.util.PermissionBitEntry
import org.red.db.models.Coalition
import org.red.db.models.Coalition.EveUserViewRow
import org.red.iris.{EveUserData, User, UserMini}

import scala.language.implicitConversions

package object db {

  /*implicit class RichUserMini(val userMini: UserMini.type) extends AnyVal {
    implicit def apply(usersRow: Coalition.UsersRow, permissions: Seq[PermissionBitEntry]): UserMini = {
      UserMini (
        name = usersRow.name,
        id = usersRow.id,
        characterId = usersRow.characterId,
        permissions = permissions.map(_.toPermissionBit)
      )
    }
  }*/

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

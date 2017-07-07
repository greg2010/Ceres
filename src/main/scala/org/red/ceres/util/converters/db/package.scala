package org.red.ceres.util.converters

import org.red.db.models.Coalition
import org.red.iris.{EveUserData, User, UserMini}

import scala.language.implicitConversions

package object db {

  implicit class RichUserMini(val userMini: UserMini.type) extends AnyVal {
    implicit def apply(usersRow: Coalition.UsersRow): UserMini = {
      UserMini (
        name = usersRow.name,
        id = usersRow.id,
        characterId = usersRow.characterId
      )
    }
  }

  implicit class RichUser(val user: User.type) extends AnyVal {
    implicit def apply(usersViewRow: Coalition.UsersViewRow): User = {
      // TODO: raise postgres exception on failed get
      val eveUserData = EveUserData(
        characterId = usersViewRow.characterId.get,
        characterName = usersViewRow.characterName.get,
        corporationId = usersViewRow.corporationId.get,
        corporationName = usersViewRow.corporationName.get,
        corporationTicker = usersViewRow.corporationTicker.get,
        allianceId = usersViewRow.allianceId,
        allianceName = usersViewRow.allianceName,
        allianceTicker = usersViewRow.allianceTicker
      )
      User(
        eveUserData = eveUserData,
        userId = usersViewRow.userId.get,
        email = usersViewRow.email.get,
        password = usersViewRow.password,
        salt = usersViewRow.salt,
        isBanned = usersViewRow.banned.get,
        creationTime = usersViewRow.creationTime.get.toString,
        lastLoggedIn = usersViewRow.lastLoggedIn.toString,
        languageCode = usersViewRow.languageCode.get
      )
    }
  }
}

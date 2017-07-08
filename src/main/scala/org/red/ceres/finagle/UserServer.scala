package org.red.ceres.finagle

import moe.pizza.eveapi.ApiKey
import org.red.ceres.controllers.UserController
import org.red.ceres.util.{CeresLegacyCredentials, CeresSSOCredentials}
import org.red.iris._

import scala.concurrent.ExecutionContext
import com.twitter.util.{Future => TFuture}
import com.twitter.bijection.twitter_util.UtilBijections.twitter2ScalaFuture
import com.twitter.bijection.Conversion.asMethod
import org.red.ceres.external.auth.EveApiClient

class UserServer(userController: => UserController, eveApiClient: => EveApiClient)
                (implicit ec: ExecutionContext) extends UserService[TFuture] {
  override def getEveUser(credentials: LegacyCredentials): TFuture[EveUserDataList] = {
    val ceresCreds = CeresLegacyCredentials(
      ApiKey(credentials.keyId, credentials.vCode),
      credentials.characterId,
      credentials.name)
    eveApiClient.fetchUser(ceresCreds).map(nel => EveUserDataList(nel.head, nel.tail)).as[TFuture[EveUserDataList]]
  }

  override def createLegacyUser(email: String,
                                credentials: LegacyCredentials,
                                password: String): TFuture[UserMini] = {
    val ceresCreds =
      CeresLegacyCredentials(
        ApiKey(
          credentials.keyId,
          credentials.vCode
        ),
        credentials.characterId,
        credentials.name)
    userController.createUser(email, Some(password), ceresCreds).as[TFuture[UserMini]]
  }

  override def createSSOUser(email: String, credentials: SSOCredentials, password: Option[String]): TFuture[UserMini] = {
    val ceresCreds = CeresSSOCredentials(credentials.refreshToken, credentials.accessToken)
    userController.createUser(email, password, ceresCreds).as[TFuture[UserMini]]
  }

  override def verifyUserLegacy(nameOrEmail: String, password: String): TFuture[UserMini] = {
    userController.verifyUser(nameOrEmail, password).as[TFuture[UserMini]]
  }

  override def verifyUserSSO(ssoToken: String): TFuture[UserMini] = {
    userController.verifyUser(ssoToken).as[TFuture[UserMini]]
  }

  override def getUser(userId: Int): TFuture[User] = {
    userController.getUser(userId).as[TFuture[User]]
  }

  override def getUserMini(userId: Int): TFuture[UserMini] = {
    userController.getUserMini(userId).as[TFuture[UserMini]]
  }

  override def triggerUserUpdate(userId: Int): TFuture[Unit] = {
    userController.updateUser(userId).as[TFuture[Unit]]
  }

  override def updateUserData(eveUserData: EveUserData): TFuture[Unit] = {
    userController.updateEveData(eveUserData).as[TFuture[Unit]]
  }

  override def updatePassword(userId: Int, newPassword: String): TFuture[Unit] = {
    userController.updatePassword(userId, newPassword).as[TFuture[Unit]]
  }

  override def requestPasswordReset(email: String): TFuture[Unit] = {
    userController.requestPasswordReset(email).map(_ => ()).as[TFuture[Unit]]
  }

  override def completePasswordReset(email: String, token: String, newPassword: String): TFuture[Unit] = {
    userController.completePasswordReset(email, token, newPassword).as[TFuture[Unit]]
  }
}

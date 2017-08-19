package org.red.ceres.finagle

import moe.pizza.eveapi.ApiKey
import org.red.ceres.controllers.UserController
import org.red.ceres.util.{CeresLegacyCredential, CeresSSOCredential}
import org.red.iris._

import scala.concurrent.ExecutionContext
import com.twitter.util.{Future => TFuture}
import com.twitter.bijection.twitter_util.UtilBijections.twitter2ScalaFuture
import com.twitter.bijection.Conversion.asMethod
import org.red.ceres.external.auth.EveApiClient

class UserServer(userController: => UserController, eveApiClient: => EveApiClient)
                (implicit ec: ExecutionContext) extends UserService[TFuture] {
  override def getEveUser(credentials: LegacyCredentials): TFuture[EveUserDataList] = {
    val ceresCreds = CeresLegacyCredential(
      ApiKey(credentials.keyId, credentials.vCode),
      credentials.characterId,
      credentials.name)
    eveApiClient.fetchUser(ceresCreds).map(nel => EveUserDataList(nel.head, nel.tail)).as[TFuture[EveUserDataList]]
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

  override def loginSSO(authCode: String): TFuture[SuccessfulLoginResponse] = {
    userController.loginSSO(authCode).as[TFuture[SuccessfulLoginResponse]]
  }
}

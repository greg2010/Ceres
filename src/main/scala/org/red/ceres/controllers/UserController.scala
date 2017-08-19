package org.red.ceres.controllers

import java.sql.Timestamp
import java.util.UUID

import cats.data.NonEmptyList

import scala.languageFeature.implicitConversions
import com.roundeights.hasher.Implicits._
import org.red.ceres.util.converters.db._
import com.typesafe.scalalogging.LazyLogging
import org.matthicks.mailgun.MessageResponse
import org.red.ceres.exceptions._
import org.red.ceres.external.auth._
import org.red.ceres.finagle.SuccessfulLoginResponse
import org.red.ceres.util._
import org.red.db.models.Coalition
import org.red.db.models.Coalition._
import org.red.iris._
import org.red.iris.finagle.clients.TeamspeakClient
import slick.dbio.Effect
import slick.jdbc.JdbcBackend
import slick.jdbc.PostgresProfile.api._
import slick.sql.FixedSqlAction

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Random, Success}


trait UserService {
  /*def createUser(email: String, password: Option[String], credentials: CeresCredentials): Future[UserMini]
  def verifyUser(nameOrEmail: String, Password: String): Future[UserMini]
  def verifyUser(ssoToken: String): Future[UserMini]*/
  def getOwnedCharacters(userId: Int): Future[NonEmptyList[EveUserData]]
  def loginSSO(authToken: String): Future[SuccessfulLoginResponse]
  def getUser(userId: Int): Future[User]
  def getUserMini(userId: Int): Future[UserMini]
  def updateUser(userId: Int): Future[Unit]
  def updateEveData(eveUserData: EveUserData): Future[Unit]
  /*def updatePassword(userId: Int, newPassword: String): Future[Unit]
  def requestPasswordReset(email: String): Future[MessageResponse]
  def completePasswordReset(email: String, token: String, newPassword: String): Future[Unit]*/
}


class UserController(permissionController: => PermissionController,
                     eveApiClient: => EveApiClient,
                     teamspeakClient: => TeamspeakClient)
                    (implicit dbAgent: JdbcBackend.Database, ec: ExecutionContext)
  extends UserService with LazyLogging {

  def getOwnedCharacters(userId: Int): Future[NonEmptyList[EveUserData]] = {
    val q = EveApi.filter(_.ownedBy === userId).join(EveUserView)
      .on((api, view) => api.characterId === view.characterId)
    val f = dbAgent.run(q.result).map { resp =>
      val userData = resp.map { ownedCharacterRow =>
        EveUserData.apply(ownedCharacterRow._2)
      }
      NonEmptyList.fromList(userData.toList) match {
        case Some(nonEmptyList) => nonEmptyList
        case None => throw ResourceNotFoundException(s"User $userId doesn't own any characters")
      }
    }
    f.onComplete {
      case Success(resp) =>
        logger.info(s"Got owned characters for " +
          s"userId=$userId " +
          s"characterIds=${resp.toList.mkString(",")} " +
          s"event=user.owned.get.success")
      case Failure(ex) =>
        logger.info(s"Failed to get owned characters for " +
          s"userId=$userId " +
          s"event=user.owned.get.failure", ex)
    }
    f
  }

  /*
    private val rsg: Stream[Char] = Random.alphanumeric

    private def hasher(password: String, salt: String): String = {
      (password + salt).sha512.hex
    }
    override def createUser(email: String,
                            password: Option[String],
                            credentials: CeresCredentials): Future[UserMini] = {
      val currentTimestamp = new Timestamp(System.currentTimeMillis())
      // Prepares `coalition.users` table insert query
      def insertToUsersQuery(eveUserData: EveUserData): FixedSqlAction[Int, NoStream, Effect.Write] = {
        val passwordWithSalt = password match {
          case Some(pwd) =>
            val s = generateSalt
            (Some(hasher(pwd, s)), Some(s))
          case None => (None, None)
        }
        Coalition.Users
          .map(u => (u.characterId, u.name, u.email, u.password, u.salt))
          .returning(Coalition.Users.map(_.id)) +=
          (eveUserData.characterId, eveUserData.characterName, email, passwordWithSalt._1, passwordWithSalt._2)
      }

      // Prepares `coalition.eve_api` table insert query
      def credsQuery(userId: Int, eveUserData: EveUserData) = credentials match {
        case legacy: CeresLegacyCredentials =>
          Coalition.EveApi.map(c => (c.userId, c.characterId, c.keyId, c.verificationCode)) +=
            (userId, eveUserData.characterId, Some(legacy.apiKey.keyId), Some(legacy.apiKey.vCode))
        case sso: CeresSSOCredentials => Coalition.EveApi.map(_.evessoRefreshToken) += Some(sso.refreshToken)
      }

      // Queries eve XML/ESI API to confirm that user indeed owns the account
      eveApiClient.fetchUser(credentials).flatMap { eveUserData =>
        val action = (for {
          _ <- updateUserDataQuery(eveUserData.head)
          userId <- insertToUsersQuery(eveUserData.head)
          _ <- credsQuery(userId, eveUserData.head)
        } yield userId).transactionally
        val f = dbAgent.run(action)
          .flatMap(getUserMini)
          .recoverWith(ExceptionHandlers.dbExceptionHandler)
        f.onComplete {
          case Success(res) =>
            logger.info(s"Created new user " +
              s"userId=$res " +
              s"event=users.create.success")
          case Failure(ex) =>
            logger.error(s"Failed to create new user " +
              s"event=users.create.failure", ex)
        }
        f
      }
    }
  */
/*
  override def verifyUser(nameOrEmail: String, providedPassword: String): Future[UserMini] = {
    val query = Coalition.UsersView
      .filter(u => u.email === nameOrEmail || u.characterName === nameOrEmail)
      .take(1)

    val f = dbAgent.run(query.result).flatMap {
      _.headOption match {
        case Some(r) =>
          permissionController.calculateAclPermissionsByUserId(r.userId.get)
            .map { perms =>
              User.apply(r, perms) match {
                case User(eveUserData, userId, _, Some(password), Some(salt), isBanned, _, _, _, _)
                  if hasher(providedPassword, salt) == password && !isBanned =>
                  UserMini(eveUserData.characterName, userId, eveUserData.characterId, perms.map(_.toPermissionBit))
                case User(eveUserData, userId, _, Some(password), Some(salt), isBanned, _, _, _, _)
                  if hasher(providedPassword, salt) == password && isBanned =>
                  logger.warn(s"Banned user attempted to login nameOrLogin=$nameOrEmail reason=banned event=user.login.banned")
                  throw AuthenticationException("User is banned", "")
                case User(eveUserData, userId, _, Some(password), Some(salt), isBanned, _, _, _, _)
                  if hasher(providedPassword, salt) != password =>
                  logger.warn(s"User failed to login nameOrLogin=$nameOrEmail reason=badPassword event=user.login.failure")
                  throw AuthenticationException("User with corresponding login and password not found", "")
                case _ =>
                  logger.error(s"Unknown error during login nameOrLogin=$nameOrEmail reason=unknown event=user.login.failure")
                  throw new InternalError("User with corresponding login and password not found")
              }
            }
        case None =>
          logger.warn(s"User failed to login nameOrLogin=$nameOrEmail reason=badLogin event=user.login.failure")
          Future.failed(AuthenticationException("User with corresponding login and password not found", ""))
      }

    }
    f.onComplete {
      case Success(res) =>
        logger.info(s"Logged in user using legacy flow " +
          s"userId=${res.id} " +
          s"characterId=${res.characterId} " +
          s"event=users.login.legacy.success")
      case Failure(ex: AuthenticationException) =>
        logger.error(s"Bad login or password for nameOrLogin=$nameOrEmail event=users.login.legacy.failure")
      case Failure(ex) =>
        logger.error(s"Failed to log in user using legacy flow " +
          s"event=users.login.legacy.failure", ex)
    }
    f
  }
*/

  def getUserByCharacterId(characterId: Long): Future[User] = {
    val q = EveApi.filter(_.characterId === characterId).map(_.ownedBy)
    val f = dbAgent.run(q.result).flatMap { resp =>
      resp.headOption match {
        case Some(userId) => this.getUser(userId)
        case None => Future.failed(ResourceNotFoundException(s"No user owns characterId $characterId"))
      }
    }
    f.onComplete {
      case Success(resp) =>
        logger.info(s"Got user by " +
          s"characterId=$characterId " +
          s"userId=${resp.userId} " +
          s"event=user.full.byCharacterId.get.success")
      case Failure(ex: ResourceNotFoundException) =>
        logger.warn(s"No user owns user with characterId=$characterId " +
          s"event=user.full.byCharacterId.get.failure")
      case Failure(ex) =>
        logger.error(s"Failed to get user by characterId=$characterId " +
          s"event=user.full.byCharacterId.get.failure")
    }
    f
  }

  def getUserMiniByCharacterId(characterId: Long): Future[UserMini] = {
    val q = EveApi.filter(_.characterId === characterId).map(_.ownedBy)
    val f = dbAgent.run(q.result).flatMap { resp =>
      resp.headOption match {
        case Some(userId) => this.getUserMini(userId)
        case None => Future.failed(ResourceNotFoundException(s"No user owns characterId $characterId"))
      }
    }
    f.onComplete {
      case Success(resp) =>
        logger.info(s"Got user by " +
          s"characterId=$characterId " +
          s"userId=${resp.id} " +
          s"event=user.mini.byCharacterId.get.success")
      case Failure(ex: ResourceNotFoundException) =>
        logger.warn(s"No user owns user with characterId=$characterId " +
          s"event=user.mini.byCharacterId.get.failure")
      case Failure(ex) =>
        logger.error(s"Failed to get user by characterId=$characterId " +
          s"event=user.mini.byCharacterId.get.failure")
    }
    f
  }

  def getUser(userId: Int): Future[User] = {

    val f = for {
      user <- dbAgent.run(Coalition.Users.filter(_.id === userId).take(1).result).map { resp =>
        resp.headOption match {
          case Some(user) => user
          case None => throw ResourceNotFoundException(s"User with userId $userId doesn't exist")
        }
      }
      ownedCharacters <- this.getOwnedCharacters(userId)
      permissions <- permissionController.getPermissions(userId)
      res <- Future {
        User(
          userId = userId,
          isBanned = user.banned,
          lastLoggedIn = user.lastLoggedIn.map(_.toString),
          languageCode = user.languageCode,
          permissions = permissions,
          eveUserDataList = EveUserDataList(head = ownedCharacters.head, tail = ownedCharacters.tail)
        )
      }
    } yield res

    f.onComplete {
      case Success(res) =>
        logger.info(s"Got user object by " +
          s"userId=$userId " +
          s"event=user.full.getById.success")
      case Failure(ex) =>
        logger.error("Failed to get user object by " +
          s"userId=$userId " +
          s"event=user.full.getById.failure", ex)
    }
    f
  }

  def getUserMini(userId: Int): Future[UserMini] = {
    val f = permissionController.getPermissions(userId).map(p => UserMini(userId, p))
    f.onComplete {
      case Success(res) =>
        logger.info(s"Got user object by " +
          s"userId=$userId " +
          s"event=user.mini.getById.success")
      case Failure(ex) =>
        logger.error("Failed to get user object by " +
          s"userId=$userId " +
          s"event=user.mini.getById.failure", ex)
    }
    f
  }

  def updateUser(userId: Int): Future[Unit] = {
    def triggerUpdates(user: User): Future[Unit] = {
      teamspeakClient.syncTeamspeakUser(user)
    }

    val r = for {
      user <- this.getUser(userId)
      res <- triggerUpdates(user)
    } yield res
    r.onComplete {
      case Success(_) =>
        logger.info(s"Updated user " +
          s"userId=$userId " +
          s"event=user.update.success")
      case Failure(ex) =>
        logger.error("Failed to update user " +
          s"userId=$userId " +
          s"event=user.update.failure", ex)
    }
    r
  }

  override def updateEveData(eveUserData: EveUserData): Future[Unit] = {
    val res = dbAgent.run(updateUserDataQuery(eveUserData))
    res.onComplete {
      case Success(_) =>
        logger.info(s"Updated eve user info for user " +
          s"characterId=${eveUserData.characterId} " +
          s"characterName=${eveUserData.characterName} " +
          s"event=user.eveData.update.success")
      case Failure(ex) =>
        logger.info(s"Failed to update eve user info " +
          s"characterId=${eveUserData.characterId} " +
          s"characterName=${eveUserData.characterName} " +
          s"event=user.eveData.update.failure")
    }
    res
  }

  override def loginSSO(authToken: String): Future[SuccessfulLoginResponse] = {
    def createUserRowQuery = Users.map(_ => ()).returning(Users.map(_.id)) += ()
    def insertEveSSORowQuery(userId: Int, characterId: Long, accessToken: String, refreshToken: String) = {
      EveApi.map(r => (r.ownedBy, r.characterId, r.evessoAccessToken, r.evessoRefreshToken)) +=
        (userId, characterId, accessToken, refreshToken)
    }

    val f = for {
      credential <- eveApiClient.exchangeAuthCode(authToken)
      eveUserData <- eveApiClient.fetchUser(credential).map(_.head)
      userMini <- this.getUserMiniByCharacterId(eveUserData.characterId)
        .recoverWith {
          case ex: ResourceNotFoundException =>
            logger.warn(s"No account exists for " +
              s"characterId=${eveUserData.characterId} " +
              s"event=user.login.create")
            for {
              _ <- this.updateEveData(eveUserData)
              userId <- dbAgent.run(createUserRowQuery)
              _ <- dbAgent.run(
                insertEveSSORowQuery(userId, eveUserData.characterId, credential.accessToken, credential.refreshToken))
              _ <- this.updateEveData(eveUserData)
              userMini <- this.getUserMini(userId)
            } yield userMini
        }
      response <- Future {
        SuccessfulLoginResponse(
          userMini = userMini,
          currentUser = eveUserData.characterId
        )
      }
    } yield response

    // Trigger user update asynchronously
    f.flatMap(u => this.updateUser(u.userMini.id))
    f.onComplete {
      case Success(res) =>
        logger.info(s"User logged in succesfully " +
          s"userId=${res.userMini.id} " +
          s"characterId=${res.currentUser} " +
          s"event=user.login.success")
      case Failure(ex) =>
        logger.error("Failed to log in user event=user.login.failure", ex)
    }

    f
  }


  private def updateUserDataQuery(eveUserData: EveUserData): DBIOAction[Unit, NoStream, Effect.Write with Effect.Write with Effect.Write with Effect.Transactional] = {
    val currentTimestamp = new Timestamp(System.currentTimeMillis())
    val charQuery = Coalition.Character
      .insertOrUpdate(
        Coalition.CharacterRow(
          eveUserData.characterId,
          eveUserData.characterName,
          eveUserData.corporationId,
          currentTimestamp))

    val corpQuery =
      Coalition.Corporation
        .insertOrUpdate(
          Coalition.CorporationRow(
            eveUserData.corporationId,
            eveUserData.corporationName,
            eveUserData.corporationTicker,
            eveUserData.allianceId,
            currentTimestamp))

    val allianceQuery = (eveUserData.allianceId, eveUserData.allianceName, eveUserData.allianceTicker) match {
      case (Some(aId), Some(aName), Some(aTicker)) =>
        Coalition.Alliance.insertOrUpdate(Coalition.AllianceRow(aId, aName, aTicker, currentTimestamp))
      case (None, None, None) => DBIO.successful {}
      case _ => throw CCPException("Alliance ID or name is present, but not both")
    }

    (for {
      _ <- allianceQuery
      _ <- corpQuery
      _ <- charQuery
    } yield ()).transactionally
  }
}

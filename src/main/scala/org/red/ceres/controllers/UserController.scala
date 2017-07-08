package org.red.ceres.controllers

import java.sql.Timestamp
import java.util.UUID

import scala.languageFeature.implicitConversions
import com.roundeights.hasher.Implicits._
import org.red.ceres.util.converters.db._
import com.typesafe.scalalogging.LazyLogging
import org.matthicks.mailgun.MessageResponse
import org.red.ceres.exceptions._
import org.red.ceres.external.auth._
import org.red.ceres.util._
import org.red.db.models.Coalition
import org.red.db.models.Coalition.{PasswordResetRequestsRow, UsersRow, UsersViewRow}
import org.red.iris._
import slick.dbio.Effect
import slick.jdbc.JdbcBackend
import slick.jdbc.PostgresProfile.api._
import slick.sql.FixedSqlAction

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Random, Success}


trait UserService {
  def createUser(email: String, password: Option[String], credentials: CeresCredentials): Future[UserMini]
  def verifyUser(nameOrEmail: String, Password: String): Future[UserMini]
  def verifyUser(ssoToken: String): Future[UserMini]
  def getUser(userId: Int): Future[User]
  def getUserMini(userId: Int): Future[UserMini]
  def updateUser(userId: Int): Future[Unit]
  def updateEveData(eveUserData: EveUserData): Future[Unit]
  def updatePassword(userId: Int, newPassword: String): Future[Unit]
  def requestPasswordReset(email: String): Future[MessageResponse]
  def completePasswordReset(email: String, token: String, newPassword: String): Future[Unit]
}


class UserController(permissionController: => PermissionController,
                     emailController: => EmailController,
                     eveApiClient: => EveApiClient)
                    (implicit dbAgent: JdbcBackend.Database, ec: ExecutionContext)
  extends UserService with LazyLogging {

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

  // TODO: implement SSO login flow
  override def verifyUser(ssoToken: String): Future[UserMini] = ???


  def getUser(userId: Int): Future[User] = {

    val f = for {
      userInfoOption <- dbAgent.run(Coalition.UsersView.filter(_.userId === userId).take(1).result).map(_.headOption)
      userInfo <- userInfoOption match {
        case Some(uRow) => Future(uRow)
        case None => Future.failed(throw ResourceNotFoundException(s"No user found with id $userId"))
      }
      userPermissions <- permissionController
        .calculateBinPermission(EveUserData.apply(userInfo))
        .map(permissionController.getAclPermissions)
      res <- Future(User.apply(userInfo, userPermissions))
    } yield res

    f.onComplete {
      case Success(res) =>
        logger.info(s"Got user object by " +
          s"userId=$userId " +
          s"characterName=${res.eveUserData.characterName} " +
          s"characterId=${res.eveUserData.characterId} " +
          s"event=user.full.getById.success")
      case Failure(ex) =>
        logger.error("Failed to get user object by " +
          s"userId=$userId " +
          s"event=user.full.getById.failure", ex)
    }
    f
  }

  def getUserMini(userId: Int): Future[UserMini] = {
    val f = for {
      userInfo <- dbAgent.run(Coalition.Users.filter(_.id === userId).take(1).result)
      userPermissions <- permissionController.calculateAclPermissionsByUserId(userId)
      res <- userInfo.headOption match {
        case Some(uRow) => Future(UserMini.apply(uRow, userPermissions))
        case None => Future.failed(ResourceNotFoundException(s"No user found with id $userId"))
      }
    } yield res

    f.onComplete {
      case Success(res) =>
        logger.info(s"Got user object by " +
          s"userId=$userId " +
          s"characterName=${res.name} " +
          s"characterId=${res.characterId} " +
          s"event=user.mini.getById.success")
      case Failure(ex) =>
        logger.error("Failed to get user object by " +
          s"userId=$userId " +
          s"event=user.mini.getById.failure", ex)
    }
    f
  }

  def updateUser(userId: Int): Future[Unit] = {
    def triggerUpdates(userId: Int): Future[Unit] = {
      //teamspeakController.syncTeamspeakUser(userId)
      Future {}
    }

    val q = Coalition.Users.filter(_.id === userId).map(_.characterId)
    val userData = dbAgent.run(q.result).flatMap { r =>
      r.headOption match {
        case Some(chId) => eveApiClient.fetchUserByCharacterId(chId)
        case None => throw ResourceNotFoundException(s"User with id $userId doesn't exist")
      }
    }
    val r = for {
      data <- userData
      _ <- updateEveData(data)
      res <- triggerUpdates(userId)
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


  def updatePassword(userId: Int, newPassword: String): Future[Unit] = {
    val salt = generateSalt
    val hashedPwd = hasher(newPassword, salt)
    val q = Coalition.Users.filter(_.id === userId)
      .map(r => (r.password, r.salt))
      .update((Some(hashedPwd), Some(salt)))
    val f = dbAgent.run(q).map {
      case 0 => throw ResourceNotFoundException(s"No user with userId $userId exists")
      case 1 =>
        emailController.sendPasswordChangeEmail(userId)
        () // Executing email send async
      case n if n > 1 => throw new RuntimeException(s"$n users were updated!")
    }

    f.onComplete {
      case Success(_) =>
        logger.info(s"Password for user was updated userId=$userId event=user.password.update.success")
      case Failure(ex) =>
        logger.error(s"Failed to update password for user userId=$userId event=user.password.update.failure", ex)
    }
    f
  }

  def requestPasswordReset(email: String): Future[MessageResponse] = {
    def insertAndSendToken(usersRow: UsersRow, obsoleteRequestId: Option[Int]): Future[(UsersRow, MessageResponse)] = {
      val deleteObsolete = obsoleteRequestId match {
        case Some(id) => dbAgent.run(deletePasswordResetRequestQuery(id))
        case None => Future {
          0
        }
      }
      val token = hasher(UUID.randomUUID().toString, usersRow.id.toString)
      val q = Coalition.PasswordResetRequests.map(r => (r.email, r.token, r.timeCreated)) +=
        (email, token, new Timestamp(System.currentTimeMillis()))

      for {
        _ <- deleteObsolete
        sendEmail <- emailController.sendPasswordResetEmail(usersRow.id, token)
        insertToken <- dbAgent.run(q)
      } yield (usersRow, sendEmail)
    }

    val q = Coalition.Users.filter(_.email === email).take(1)
      .joinLeft(Coalition.PasswordResetRequests).on((u, p) => u.email === p.email)
    val f = dbAgent.run(q.result).flatMap { r =>
      val rOpt = r.headOption
      (rOpt.map(_._1), rOpt.flatMap(_._2)) match {
        case (Some(usersRow), None) =>
          logger.info(s"Password reset request for " +
            s"email=$email " +
            s"userId=${usersRow.id} " +
            s"characterId=${usersRow.characterId} " +
            s"event=user.passwordReset.create")
          insertAndSendToken(usersRow, None)
        case (Some(usersRow), Some(passwordResetRequestsRow)) =>
          val difference = (System.currentTimeMillis() - passwordResetRequestsRow.timeCreated.getTime).millis
          if (difference < 15.minutes)
            Future.failed(new IllegalStateException("This user has already requested password reset"))
          else insertAndSendToken(usersRow, Some(passwordResetRequestsRow.id))
        case (None, _) => Future.failed(ResourceNotFoundException(s"No user with email $email exists"))
      }
    }

    f.onComplete {
      case Success(res) =>
        logger.info(s"Successfully created password reset request " +
          s"userId=${res._1.id} " +
          s"characterId=${res._1.characterId} " +
          s"name=${res._1.name} " +
          s"email=${res._1.email} " +
          s"mailgunMessageId=${res._2.id} " +
          s"event=user.passwordReset.create.success")
      case Failure(ex) =>
        logger.info(s"Failed create password reset request " +
          s"email=$email " +
          s"event=user.passwordReset.create.failure", ex)
    }

    f.map(r => r._2)
  }

  def completePasswordReset(email: String, token: String, newPassword: String): Future[Unit] = {
    def testToken(passwordResetRequestsRow: PasswordResetRequestsRow, userId: Int): Boolean = {
      val dbHashedToken = hasher(passwordResetRequestsRow.token, userId.toString)
      val difference = (System.currentTimeMillis() - passwordResetRequestsRow.timeCreated.getTime).millis
      (dbHashedToken == token) && difference < 15.minutes
    }

    val q = Coalition.Users.filter(_.email === email)
      .joinLeft(Coalition.PasswordResetRequests).on((u, p) => u.email === p.email)
    val f = dbAgent.run(q.result).flatMap { r =>
      val rOpt = r.headOption
      (rOpt.map(_._1), rOpt.flatMap(_._2)) match {
        case (Some(usersRow), Some(passwordResetRequestsRow))
          if testToken(passwordResetRequestsRow, usersRow.id) =>
          for {
            upd <- this.updatePassword(usersRow.id, newPassword)
            del <- dbAgent.run(deletePasswordResetRequestQuery(passwordResetRequestsRow.id))
          } yield usersRow
        case _ =>
          Future.failed(ResourceNotFoundException("No user found for this email, token expired or token doesn't match"))
      }
    }
    f.onComplete {
      case Success(res) =>
        logger.info(s"Completed password reset for user userId=${res.id} email=${res.email} event=user.passwordReset.complete.success")
      case Failure(ex) =>
        logger.error(s"Failed to update password for user email=$email event=user.passwordReset.complete.failure", ex)
    }
    f.map(_ => ())
  }

  /* dead? TODO: figure out what to do with the function
  def checkInUser(userId: Int): Future[Unit] = {
    val currentTimestamp = Some(new Timestamp(System.currentTimeMillis()))
    val query = Coalition.Users
      .filter(_.id === userId)
      .map(_.lastLoggedIn).update(currentTimestamp)
    val f = dbAgent.run(query).flatMap {
      case 0 => Future.failed(ResourceNotFoundException("No user was updated!"))
      case 1 => Future.successful {}
      case n => Future.failed(new RuntimeException("More than 1 user was updated!"))
    }.recoverWith(ExceptionHandlers.dbExceptionHandler)
    f.onComplete {
      case Success(_) =>
        logger.info(s"Successfully checked in user asynchronously " +
          s"userId=$userId " +
          s"event=users.checkinAsync.success")
      case Failure(ex) =>
        logger.error(s"Failed to check in user asynchronously " +
          s"userId=$userId " +
          s"event=users.checkinAsync.failure", ex)
    }
    f
  }*/


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

  private def deletePasswordResetRequestQuery(id: Int): FixedSqlAction[Int, NoStream, Effect.Write] =
    Coalition.PasswordResetRequests.filter(_.id === id).delete

  def generateSalt: String = rsg.take(4).mkString
}

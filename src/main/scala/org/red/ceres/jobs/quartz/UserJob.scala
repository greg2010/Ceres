package org.red.ceres.jobs.quartz

import com.typesafe.scalalogging.LazyLogging
import org.quartz.{Job, JobExecutionContext}
import org.red.ceres.controllers.UserController
import org.red.ceres.exceptions.ExceptionHandlers

import scala.concurrent.ExecutionContext


class UserJob extends Job with LazyLogging {
  override def execute(context: JobExecutionContext): Unit = {
    try {
      implicit val ec = context.getScheduler.getContext.get("ec").asInstanceOf[ExecutionContext]
      val userController = context.getScheduler.getContext.get("userController").asInstanceOf[UserController]
      val userId = context.getMergedJobDataMap.getInt("userId")
      userController.updateUser(userId)
    } catch {
      ExceptionHandlers.jobExceptionHandler
    }
  }
}

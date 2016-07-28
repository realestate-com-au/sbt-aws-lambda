package com.gilt.aws.lambda

import sbt._

import scala.util.{Either, Failure, Success}

object AwsLambdaPlugin extends AutoPlugin {

  object autoImport {
    val createLambda = taskKey[Map[String, LambdaARN]]("Create a new AWS Lambda function from the current project")
    val updateLambda = taskKey[Map[String, LambdaARN]]("[Deprecated: use deployLambda] Package and deploy the current project to an existing AWS Lambda")
    val deployLambda = taskKey[Map[String, LambdaARN]]("Package and deploy the current project to AWS Lambda")

    val s3Bucket = settingKey[Option[String]]("ID of the S3 bucket where the jar will be uploaded")
    val s3KeyPrefix = settingKey[String]("The prefix to the S3 key where the jar will be uploaded")
    val lambdaName = settingKey[Option[String]]("Name of the AWS Lambda to update")
    val handlerName = settingKey[Option[String]]("Name of the handler to be executed by AWS Lambda")
    val roleArn = settingKey[Option[String]]("ARN of the IAM role for the Lambda function")
    val region = settingKey[Option[String]]("Name of the AWS region to connect to")
    val awsLambdaTimeout = settingKey[Option[Int]]("The Lambda timeout length in seconds (1-300)")
    val awsLambdaMemory = settingKey[Option[Int]]("The amount of memory in MB for the Lambda function (128-1536, multiple of 64)")
    val lambdaHandlers = settingKey[Seq[(String, String)]]("A sequence of pairs of Lambda function names to handlers (for multiple handlers in one jar)")
  }

  import autoImport._

  override def requires = sbtassembly.AssemblyPlugin

  override lazy val projectSettings = Seq(
    updateLambda := {
      println("Deprecated: Use deployLambda.")
      deployLambda.value
    },
    createLambda := {
      println("Deprecated: Use deployLambda.")
      deployLambda.value
    },
    deployLambda := doDeployLambda(
      region = region.value,
      jar = sbtassembly.AssemblyKeys.assembly.value,
      s3Bucket = s3Bucket.value,
      s3KeyPrefix = s3KeyPrefix.?.value,
      lambdaName = lambdaName.value,
      handlerName = handlerName.value,
      lambdaHandlers = lambdaHandlers.value,
      roleArn = roleArn.value,
      timeout = awsLambdaTimeout.value,
      memory = awsLambdaMemory.value
    ),
    s3Bucket := None,
    lambdaName := Some(sbt.Keys.name.value),
    handlerName := None,
    lambdaHandlers := List.empty[(String, String)],
    roleArn := None,
    region := None,
    awsLambdaMemory := None,
    awsLambdaTimeout := None
  )

  private def doDeployLambda(region: Option[String], jar: File, s3Bucket: Option[String], s3KeyPrefix: Option[String], lambdaName: Option[String],
                             handlerName: Option[String], lambdaHandlers: Seq[(String, String)], roleArn: Option[String], timeout: Option[Int], memory: Option[Int]): Map[String, LambdaARN] = {
    val resolvedRegion = resolveRegion(region)
    val resolvedBucketId = resolveBucketId(s3Bucket)
    val resolvedS3KeyPrefix = resolveS3KeyPrefix(s3KeyPrefix)
    val resolvedLambdaHandlers = resolveLambdaHandlers(lambdaName, handlerName, lambdaHandlers)

    val resolvedRoleName = resolveRoleARN(roleArn)
    val resolvedTimeout = resolveTimeout(timeout)
    val resolvedMemory = resolveMemory(memory)

    AwsS3.pushJarToS3(jar, resolvedBucketId, resolvedS3KeyPrefix) match {
      case Success(s3Key) =>
        for ((resolvedLambdaName, resolvedHandlerName) <- resolvedLambdaHandlers) yield {

          AwsLambda.deployLambda(resolvedRegion, jar, resolvedLambdaName, resolvedHandlerName, resolvedRoleName, resolvedBucketId, s3Key, resolvedTimeout, resolvedMemory) match {
            case Success(Left(createFunctionCodeResult)) =>
              resolvedLambdaName.value -> LambdaARN(createFunctionCodeResult.getFunctionArn)
            case Success(Right(updateFunctionCodeResult)) =>
              resolvedLambdaName.value -> LambdaARN(updateFunctionCodeResult.getFunctionArn)
            case Failure(exception) =>
              sys.error(s"Failed to create lambda function: ${exception.getLocalizedMessage}\n${exception.getStackTraceString}")
          }
        }
      case Failure(exception) =>
        sys.error(s"Error upload jar to S3 lambda: ${exception.getLocalizedMessage}\n${exception.getStackTraceString}")
    }
  }

  private def resolveRegion(sbtSettingValueOpt: Option[String]): Region =
    sbtSettingValueOpt orElse sys.env.get(EnvironmentVariables.region) map Region getOrElse promptUserForRegion()

  private def resolveBucketId(sbtSettingValueOpt: Option[String]): S3BucketId =
    sbtSettingValueOpt orElse sys.env.get(EnvironmentVariables.bucketId) map S3BucketId getOrElse promptUserForS3BucketId()

  private def resolveS3KeyPrefix(sbtSettingValueOpt: Option[String]): String =
    sbtSettingValueOpt orElse sys.env.get(EnvironmentVariables.s3KeyPrefix) getOrElse ""

  private def resolveLambdaHandlers(lambdaName: Option[String], handlerName: Option[String],
      lambdaHandlers: Seq[(String, String)]): Map[LambdaName, HandlerName] = {
    val lhs = if (lambdaHandlers.nonEmpty) lambdaHandlers.iterator else {
      val l = lambdaName.getOrElse(sys.env.getOrElse(EnvironmentVariables.lambdaName, promptUserForFunctionName()))
      val h = handlerName.getOrElse(sys.env.getOrElse(EnvironmentVariables.handlerName, promptUserForHandlerName()))
      Iterator(l -> h)
    }
    lhs.map { case (l, h) => LambdaName(l) -> HandlerName(h) }.toMap
  }

  private def resolveRoleARN(sbtSettingValueOpt: Option[String]): RoleARN =
    sbtSettingValueOpt orElse sys.env.get(EnvironmentVariables.roleArn) map RoleARN getOrElse promptUserForRoleARN()

  private def resolveTimeout(sbtSettingValueOpt: Option[Int]): Option[Timeout] =
    sbtSettingValueOpt orElse sys.env.get(EnvironmentVariables.timeout).map(_.toInt) map Timeout

  private def resolveMemory(sbtSettingValueOpt: Option[Int]): Option[Memory] =
    sbtSettingValueOpt orElse sys.env.get(EnvironmentVariables.memory).map(_.toInt) map Memory

  private def promptUserForRegion(): Region = {
    val inputValue = readInput(s"Enter the name of the AWS region to connect to. (You also could have set the environment variable: ${EnvironmentVariables.region} or the sbt setting: region)")

    Region(inputValue)
  }

  private def promptUserForS3BucketId(): S3BucketId = {
    val inputValue = readInput(s"Enter the AWS S3 bucket where the lambda jar will be stored. (You also could have set the environment variable: ${EnvironmentVariables.bucketId} or the sbt setting: s3Bucket)")
    val bucketId = S3BucketId(inputValue)

    AwsS3.getBucket(bucketId) map (_ => bucketId) getOrElse {
      val createBucket = readInput(s"Bucket $inputValue does not exist. Create it now? (y/n)")

      if(createBucket == "y") {
        AwsS3.createBucket(bucketId) match {
          case Success(createdBucketId) =>
            createdBucketId
          case Failure(th) =>
            println(s"Failed to create S3 bucket: ${th.getLocalizedMessage}")
            promptUserForS3BucketId()
        }
      }
      else promptUserForS3BucketId()
    }
  }

  private def promptUserForFunctionName(): String =
    readInput(s"Enter the name of the AWS Lambda. (You also could have set the environment variable: ${EnvironmentVariables.lambdaName} or the sbt setting: lambdaName)")

  private def promptUserForHandlerName(): String =
    readInput(s"Enter the name of the AWS Lambda handler. (You also could have set the environment variable: ${EnvironmentVariables.handlerName} or the sbt setting: handlerName)")

  private def promptUserForRoleARN(): RoleARN = {
    AwsIAM.basicLambdaRole() match {
      case Some(basicRole) =>
        val reuseBasicRole = readInput(s"IAM role '${AwsIAM.BasicLambdaRoleName}' already exists. Reuse this role? (y/n)")
        
        if(reuseBasicRole == "y") RoleARN(basicRole.getArn)
        else readRoleARN()
      case None =>
        val createDefaultRole = readInput(s"Default IAM role for AWS Lambda has not been created yet. Create this role now? (y/n)")
        
        if(createDefaultRole == "y") {
          AwsIAM.createBasicLambdaRole() match {
            case Success(createdRole) =>
              createdRole
            case Failure(th) =>
              println(s"Failed to create role: ${th.getLocalizedMessage}")
              promptUserForRoleARN()
          }
        } else readRoleARN()
    }
  }

  private def readRoleARN(): RoleARN = {
    val inputValue = readInput(s"Enter the ARN of the IAM role for the Lambda. (You also could have set the environment variable: ${EnvironmentVariables.roleArn} or the sbt setting: roleArn)")
    RoleARN(inputValue)
  }

  private def readInput(prompt: String): String =
    SimpleReader.readLine(s"$prompt\n") getOrElse {
      val badInputMessage = "Unable to read input"

      val updatedPrompt = if(prompt.startsWith(badInputMessage)) prompt else s"$badInputMessage\n$prompt"

      readInput(updatedPrompt)
    }
}

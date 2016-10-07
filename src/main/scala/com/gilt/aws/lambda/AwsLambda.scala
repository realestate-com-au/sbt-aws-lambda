package com.gilt.aws.lambda

import com.amazonaws.regions.RegionUtils
import com.amazonaws.{AmazonServiceException, AmazonClientException}
import com.amazonaws.services.lambda.AWSLambdaClient
import com.amazonaws.services.lambda.model._
import sbt._

import scala.util.{Try, Failure, Success}

private[lambda] object AwsLambda {
  def updateLambda(region: Region, lambdaName: LambdaName, bucketId: S3BucketId, s3Key: S3Key): Try[UpdateFunctionCodeResult] = {
    try {
      val client = new AWSLambdaClient(AwsCredentials.provider)
      client.setRegion(RegionUtils.getRegion(region.value))

      val request = {
        val r = new UpdateFunctionCodeRequest()
        r.setFunctionName(lambdaName.value)
        r.setS3Bucket(bucketId.value)
        r.setS3Key(s3Key.value)

        r
      }

      val updateResult = client.updateFunctionCode(request)

      println(s"Updated lambda ${updateResult.getFunctionArn}")
      Success(updateResult)
    }
    catch {
      case ex @ (_ : AmazonClientException |
                 _ : AmazonServiceException) =>
        Failure(ex)
    }
  }

  def createLambda(region: Region,
                   jar: File,
                   functionName: LambdaName,
                   handlerName: HandlerName,
                   roleName: RoleARN,
                   s3BucketId: S3BucketId,
                   s3Prefix: String,
                   timeout:  Option[Timeout],
                   memory: Option[Memory]
                    ): Try[CreateFunctionResult] = {
    try {
      val client = new AWSLambdaClient(AwsCredentials.provider)
      client.setRegion(RegionUtils.getRegion(region.value))

      val request = {
        val r = new CreateFunctionRequest()
        r.setFunctionName(functionName.value)
        r.setHandler(handlerName.value)
        r.setRole(roleName.value)
        r.setRuntime(com.amazonaws.services.lambda.model.Runtime.Java8)
        if(timeout.isDefined) r.setTimeout(timeout.get.value)
        if(memory.isDefined)  r.setMemorySize(memory.get.value)

        val functionCode = {
          val c = new FunctionCode
          c.setS3Bucket(s3BucketId.value)
          c.setS3Key(s3Prefix + jar.getName)
          c
        }

        r.setCode(functionCode)

        r
      }

      val createResult = client.createFunction(request)

      println(s"Created Lambda: ${createResult.getFunctionArn}")
      Success(createResult)
    }
    catch {
      case ex @ (_ : AmazonClientException |
                 _ : AmazonServiceException) =>
        Failure(ex)
    }
  }

}

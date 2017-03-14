package com.reagroup.lambda

import java.io.RandomAccessFile
import java.nio.ByteBuffer

import com.amazonaws.auth.DefaultAWSCredentialsProviderChain
import com.amazonaws.regions.{Region, RegionUtils}
import com.amazonaws.services.lambda.AWSLambdaClient
import com.amazonaws.services.lambda.model._
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.{CannedAccessControlList, PutObjectRequest}
import sbt._

import scala.util.Either

import collection.JavaConverters._

object AwsLambdaPlugin extends AutoPlugin {

  object autoImport {
    val deployLambda = taskKey[Map[String, String]]("Package and deploy the current project to AWS Lambda")

    val deployMethod = settingKey[Option[String]](" `Direct`: directly upload a jar file, `S3`: upload the jar to S3 bucket. Optional, defaults to Direct")
    val s3Bucket = settingKey[Option[String]]("ID of the S3 bucket where the jar will be uploaded. Required if deployMethod = S3)")
    val s3KeyPrefix = settingKey[Option[String]]("The prefix to the S3 key where the jar will be uploaded. Applicable but optional if deployMethod = S3, default is empty string")
    val roleArn = settingKey[String]("ARN of the IAM role for the Lambda function. Required.")
    val region = settingKey[Option[String]]("Name of the AWS region to connect to. Optional but if not specified env var AWS_DEFAULT_REGION must be set. Cannot be changed updated set.")
    val awsLambdaTimeout = settingKey[Option[Int]]("Optional Lambda timeout length in seconds (1-300). Optional, defaults to AWS default")
    val awsLambdaMemory = settingKey[Option[Int]]("Optional memory in MB for the Lambda function (128-1536, multiple of 64). Optional, defaults to AWS default")
    val lambdaHandlers = settingKey[Map[String, String]]("Map of Lambda function names to handlers. Required")
    val awsLambdaVpcConfig = settingKey[Option[(List[String], List[String])]]("Pair of lists, the first containing a list of subnet IDs the lambda needs to access, " +
      "the second a list of security groups IDs in the VPC the lambda accesses. Optional")
  }

  import autoImport._

  override def requires = sbtassembly.AssemblyPlugin

  override lazy val projectSettings = Seq(
    deployMethod := None,
    s3Bucket := None,
    s3KeyPrefix := None,
    awsLambdaTimeout := None,
    awsLambdaMemory := None,
    awsLambdaVpcConfig := None,
    lambdaHandlers := Map.empty,
    deployLambda := deployMethodSetting.value.deploy(
      sbtassembly.AssemblyKeys.assembly.value,
      lambdaHandlers.value,
      deployParamsSetting.value
    )
  )

  val DeployDirect = "Direct"
  val DeployS3 = "S3"

  //from http://eed3si9n.com/4th-dimension-with-sbt-013
  def deployMethodSetting = Def.setting(deployMethod.value.fold[DeployMethod](Direct){
    case DeployDirect =>
      Direct
    case DeployS3 =>
      require(s3Bucket.value.isDefined, "s3Bucket setting must be defined if deployMethod is S3")
      S3(s3Bucket.value.get, s3KeyPrefix.value.getOrElse(""))
    case other =>
      throw new IllegalArgumentException(s"Invalid deployMethod $other. Valid values: $DeployDirect, $DeployS3")
  })

  def regionSetting = Def.setting(
    RegionUtils.getRegion((region.value orElse sys.env.get("AWS_DEFAULT_REGION")).getOrElse(
      throw new IllegalArgumentException("Neither region nor AWS_DEFAULT_REGION set")))
  )


  def deployParamsSetting = Def.setting(LambdaDeployParams(
    lambdaHandlers.value,
    regionSetting.value,
    roleArn.value,
    awsLambdaTimeout.value,
    awsLambdaMemory.value,
    awsLambdaVpcConfig.value
  ))

  def lambdaExists(functionName: String, lambdaClient: AWSLambdaClient) = try {
    val getRequest = new GetFunctionRequest
    getRequest.setFunctionName(functionName)
    lambdaClient.getFunction(getRequest)
    true
  }
  catch {
    case _: ResourceNotFoundException => false
  }

}

sealed trait DeployMethod {
  val credentialsProvider = new DefaultAWSCredentialsProviderChain()
  val lambdaClient = new AWSLambdaClient(credentialsProvider)

  def deploy(jar: File,
             lambdaHandlers: Map[String, String],
             params: LambdaDeployParams): Map[String, String] = {
    prepare(jar)
    for ((functionName, handlerName) <- lambdaHandlers) yield {

      deployLambda(jar, functionName, handlerName, params) match {
        case Left(createFunctionCodeResult) =>
          functionName -> createFunctionCodeResult.getFunctionArn
        case Right(updateFunctionCodeResult) =>
          functionName -> updateFunctionCodeResult.getFunctionArn
      }
    }
  }

  def prepare(jar: File): Unit


  def deployLambda(jar: File,
                   functionName: String,
                   handlerName: String,
                   params: LambdaDeployParams
                  ): Either[CreateFunctionResult, UpdateFunctionCodeResult] = {
    lambdaClient.setRegion(params.region)



    if (AwsLambdaPlugin.lambdaExists(functionName, lambdaClient)) {
      val request = createUpdateFunctionCodeRequest(jar, functionName)
      val updateResult = lambdaClient.updateFunctionCode(request)
      val configRequest = createUpdateFunctionConfigRequest(functionName, handlerName, params)
      val updateConfigResult = lambdaClient.updateFunctionConfiguration(configRequest)

      println(s"Updated lambda ${updateResult.getFunctionArn}")
      Right(updateResult)
    }
    else {
      val request = {
        val r = new CreateFunctionRequest()
        r.setFunctionName(functionName)
        r.setHandler(handlerName)
        r.setRole(params.roleArn)
        r.setRuntime(com.amazonaws.services.lambda.model.Runtime.Java8)
        params.timeout.foreach(r.setTimeout(_))
        params.memory.foreach(r.setMemorySize(_))
        params.vpcConfig.foreach(value => {
          val c = new VpcConfig()
          c.setSubnetIds(value._1.asJavaCollection)
          c.setSecurityGroupIds(value._2.asJavaCollection)
          r.setVpcConfig(c)
        })

        val functionCode = createFunctionCode(jar)
        r.setCode(functionCode)
        r
      }

      val createResult = lambdaClient.createFunction(request)

      println(s"Created Lambda: ${createResult.getFunctionArn}")
      Left(createResult)
    }
  }

  def createUpdateFunctionCodeRequest(jar: File, lambdaName: String): UpdateFunctionCodeRequest

  def createFunctionCode(jar: File): FunctionCode


  def createUpdateFunctionConfigRequest(functionName: String, handlerName: String, params: LambdaDeployParams) = {
    val r = new UpdateFunctionConfigurationRequest()
    r.setFunctionName(functionName)
    r.setHandler(handlerName)
    params.memory.foreach(r.setMemorySize(_))
    params.timeout.foreach(r.setTimeout(_))
    params.vpcConfig.foreach {
      case (subnetIds, securityGroupIds) => r.setVpcConfig(
        new VpcConfig().withSubnetIds(subnetIds.asJavaCollection).
          withSecurityGroupIds(securityGroupIds.asJavaCollection))
    }
    r
  }

}
case object Direct extends DeployMethod {

  def prepare(jar: File): Unit = {}

  def createUpdateFunctionCodeRequest(jar: File, lambdaName: String): UpdateFunctionCodeRequest = {
    val r = new UpdateFunctionCodeRequest()
    r.setFunctionName(lambdaName)
    val buffer = getJarBuffer(jar)
    r.setZipFile(buffer)
    r
  }

  def createFunctionCode(jar: File): FunctionCode = {
    val c = new FunctionCode
    val buffer = getJarBuffer(jar)
    c.setZipFile(buffer)
    c
  }

  def getJarBuffer(jar: File): ByteBuffer = {
    val buffer = ByteBuffer.allocate(jar.length().toInt)
    val aFile = new RandomAccessFile(jar, "r")
    val inChannel = aFile.getChannel()
    while (inChannel.read(buffer) > 0) {}
    inChannel.close()
    buffer.rewind()
    buffer
  }

}
case class S3(bucketName: String, bucketPath: String) extends DeployMethod {
  val s3Client = new AmazonS3Client(credentialsProvider)


  def prepare(jar: File): Unit = {
    val objectRequest = new PutObjectRequest(bucketName, bucketPath + jar.getName, jar)
    objectRequest.setCannedAcl(CannedAccessControlList.AuthenticatedRead)
    s3Client.putObject(objectRequest)
  }

  def createUpdateFunctionCodeRequest(jar: File, lambdaName: String): UpdateFunctionCodeRequest = {
    val r = new UpdateFunctionCodeRequest()
    r.setFunctionName(lambdaName)
    r.setS3Bucket(bucketName)
    r.setS3Key(bucketPath + jar.getName)
    r
  }

  def createFunctionCode(jar: File): FunctionCode = {
    val c = new FunctionCode
    c.setS3Bucket(bucketName)
    c.setS3Key(bucketPath + jar.getName)
    c
  }
}

case class LambdaDeployParams(lambdaHandlers: Map[String, String],
                                             region: Region,
                                             roleArn: String,
                                             timeout: Option[Int],
                                             memory: Option[Int],
                                             vpcConfig: Option[(List[String], List[String])]
                             ) {

  timeout.foreach(value =>
    require(value > 0 && value <= 300, "Lambda timeout must be between 1 and 300 seconds"))
  memory.foreach(value => {
    require(value >= 128 && value <= 1536, "Lambda memory must be between 128 and 1536 MBs")
    require(value % 64 == 0, "Lambda memory MBs must be multiple of 64")
  })
  vpcConfig.foreach(value => {
    require(value._1.nonEmpty, "If VPC Config is specified, at least one Subnet ID must be provided")
    require(value._2.nonEmpty, "If VPC Config is specified, at least one Security Group ID must be provided")
  })
}

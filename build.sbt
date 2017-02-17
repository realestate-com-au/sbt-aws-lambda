name := "simple-sbt-aws-lambda"

organization := "com.rea-group"

sbtPlugin := true

addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "0.14.4")

val awsSdkVersion = "1.11.92"

libraryDependencies ++= Seq(
  "com.amazonaws"  % "aws-java-sdk-iam"    % awsSdkVersion,
  "com.amazonaws"  % "aws-java-sdk-lambda" % awsSdkVersion,
  "com.amazonaws"  % "aws-java-sdk-s3"     % awsSdkVersion
)


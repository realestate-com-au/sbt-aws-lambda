# simple-sbt-aws-lambda

SBT plugin to easily deploy Scala code to named AWS Lambda endpoints. The plugin aims to complement rather than duplication the capabilities
of cloudformation.


##Installation

Add the following to your `project/plugins.sbt` file:

```scala
addSbtPlugin("com.rea-group" % "simple-sbt-aws-lambda" % "0.2")
```

Add the `AwsLambdaPlugin` auto-plugin to your build.sbt:

```scala
enablePlugins(AwsLambdaPlugin)
```

##Usage

###Minimal Example Config

```scala
lambdaHandlers = Map("myLambda" -> "com.mycompany.MyObject::myMethod")

roleARN = "arn:aws:iam::xxxx:role/lambda_basic_exec"

region := Some("ap-southeast-2")
```

###Specifying Lambda Handlers

You must specify a `Map` of lambda handlers; each associates
a lambda name against a method on a scala object, which will be invoked by the lambda.
```scala
lambdaHandlers = Map("myLambda" -> "com.mycompany.MyObject::myMethod")
```

###Deploy Command

`deployLambda` creates or updates one or more AWS Lambda function from the current project.

For each lambda handler entry, it searches for an existing handler using [`GetFunctionRequest`](http://docs.aws.amazon.com/AWSJavaSDK/latest/javadoc/com/amazonaws/services/lambda/model/GetFunctionRequest.html).
If the named lambda exists, then its code is updated using [`UpdateFunctionCodeRequest`](http://docs.aws.amazon.com/AWSJavaSDK/latest/javadoc/com/amazonaws/services/lambda/model/UpdateFunctionCodeRequest.html).
Otherwise, the lambda is created using [`CreateFunctionRequest`](http://docs.aws.amazon.com/AWSJavaSDK/latest/javadoc/com/amazonaws/services/lambda/model/CreateFunctionRequest.html)

*Note: if you remove an existing handler from the settings and run `deployLambda`, the corresponding lambda will not be cleaned up.*

`deployPrebuiltLambda` deploys an existing jar file to AWS directly without packaging. It requires the `prebuiltPath` setting
to be defined.

###Deployment Methods

The application will be packaged into a "fat" jar (ie including all dependent libraries) using [sbt-assembly](https://github.com/sbt/sbt-assembly).

Two deployment pathways are supported:

*Direct (default):* Zips the application bytecode in a memory `ByteBuffer` and directly uploads to lambda via the [Java SDK API](http://docs.aws.amazon.com/lambda/latest/dg/API_FunctionCode.html).
Specify `"Direct"` to use this method.

*Via S3: * Uploading via an S3 bucket. Specify `"S3"` to use this method, and the `s3Bucket` (and optionally `s3KeyPrefix`) settings
should be provided to indicate the bucket location to use.

###The Role ARN Setting

You must specify a [`roleArn`](http://docs.aws.amazon.com/lambda/latest/dg/intro-permission-model.html#lambda-intro-execution-role),
which identifies the AWS role the lambda will run as.

###Specifying Region

The AWS Region the API calls must be specified either via the `region` setting, or if unspecified via the environment variable [`AWS_DEFAULT_REGION`](http://docs.aws.amazon.com/cli/latest/userguide/cli-chap-getting-started.html#cli-environment)

###Authentication

When making API calls, this plugin uses the Java SDKs [DefaultAWSCredentialsProviderChain](http://docs.aws.amazon.com/AWSJavaSDK/latest/javadoc/com/amazonaws/auth/DefaultAWSCredentialsProviderChain.html)
which searches for credentials in standard locations; see linked documentation for details.


##SBT Settings Reference

| sbt setting   |    Description |
|:--------------|:-------------:|
| lambdaHandlers |Map of Lambda function names to handlers. Required |
| deployMethod | `Direct`: directly upload a jar file, `S3`: upload the jar to S3 bucket. Optional, defaults to Direct |
| s3Bucket |ID of the S3 bucket where the jar will be uploaded. Required if deployMethod = S3) |
| s3KeyPrefix |The prefix to the S3 key where the jar will be uploaded. Applicable but optional if deployMethod = S3, default is empty string |
| roleArn |ARN of the IAM role for the Lambda function. Required. |
| region |Name of the AWS region to connect to. Optional but if not specified env var AWS_DEFAULT_REGION must be set |
| awsLambdaTimeout |Optional Lambda timeout length in seconds (1-300). Optional, defaults to AWS default |
| awsLambdaMemory |Optional memory in MB for the Lambda function (128-1536, multiple of 64). Optional, defaults to AWS default |
| awsLambdaVpcConfig |Pair of lists, the first containing a list of subnet IDs the lambda needs to access, the second a list of security groups IDs in the VPC the lambda accesses. Optional |
| prebuiltPath |Local path to an existing lambda jar file. Only required by `deployPrebuiltLambda` task.  |

##Change Log

| Version  | When  |    Description |
|:--------------|:-------------:|
| 0.1 | Feb 2017 |Initial release |
| 0.2 | Mar 2017 |Support VpcConfig settings on lambda create|
| 0.3 | Mar 2017 |Support VpcConfig settings on lambda update|
| 0.3 | Aug 2017 |Support deployPrebuiltLambda task|

##Credits & changes vs `sbt-aws-lambda` plugin

The plugin derives from the [plugin by Gilt](https://github.com/gilt/sbt-aws-lambda). Thanks & credits to the Gilt developers for leading the way.

Changes relative to `sbt-aws-lambda` as at Feb 2017:

- Support VpcConfig
- Simplify commands and configuration settings, remove multiple conflicting ways to specify the lambda handlers
- Incorporate unmerged fixes from @benhutchison
- Incorporate valuable unmerged features from @silvaren (Direct deployment) and @hussachai (Combine create/update operations into deploy)
- Greatly simplify code structure and reduce lines of code making contribution & maintenance easier. Involved removing some abstraction that IMO wasn't really earning it's place.
- Remove non-core feature: read settings from environment. It can be readily achieved by eg `roleARN := sys.env.getOrElse("AWS_ROLE", default = "a role arn...")`
- Remove non-core features around creating AWS roles. Use cloudformation or the CLI for this.
- Remove non-core features around prompting user for settings. Script it.
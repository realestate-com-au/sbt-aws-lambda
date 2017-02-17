
publishMavenStyle := true

publishTo := {
  val nexus = "https://oss.sonatype.org/"
  if (isSnapshot.value)
    Some("snapshots" at nexus + "content/repositories/snapshots")
  else
    Some("releases"  at nexus + "service/local/staging/deploy/maven2")
}

publishArtifact in Test := false

pomIncludeRepository := { _ => false }

sonatypeProfileName := "com.rea-group"

pomExtra := {
  <url>https://github.com/realestate-com-au</url>
  <scm>
    <url>git@github.com:realestate-com-au/simple-sbt-aws-lambda</url>
    <connection>scm:git:git@github.com:realestate-com-au/simple-sbt-aws-lambda.git</connection>
  </scm>
  <licenses>
    <license>
      <name>Apache 2</name>
      <url>http://www.apache.org/licenses/LICENSE-2.0.txt</url>
    </license>
  </licenses>
  <developers>
    <developer>
      <id>benhutchison</id>
      <name>Ben Hutchison</name>
      <url>https://github.com/benhutchison</url>
    </developer>
  </developers>
}

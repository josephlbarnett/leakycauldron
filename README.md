[![CircleCI](https://circleci.com/gh/josephlbarnett/leakycauldron.svg?style=svg)](https://circleci.com/gh/josephlbarnett/leakycauldron)
[![codecov](https://codecov.io/gh/josephlbarnett/leakycauldron/branch/main/graph/badge.svg?token=wqKHFsgfdx)](https://codecov.io/gh/josephlbarnett/leakycauldron)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.josephlbarnett/leakycauldron)](https://central.sonatype.com/artifact/io.github.josephlbarnett/leakycauldron/)

Joe's Fork of [Tribe Dynamics' Leaky Cauldron](https://github.com/trib3/leakycauldron)
=======

Description
-----------
Leaky Cauldron contains a collection of libraries used for building kotlin services.

It's where [drop]wizards get their guice!

Versioning
----------
Release version are of the form `X.Y.Z` where:

* `X`: Major version, incremented when a subjectively "major" change happens
* `Y`: Minor version, incremented when a breaking, backwards incompatible change happens
* `Z`: Build version, incremented for every build

To uptake all new features and bugfixes without breaking changes, downstream projects can depend on a version range of
the form `[X.Y.1,X.W-SNAPSHOT)`, where `W = Y + 1`
(eg. `[1.7.1,1.8-SNAPSHOT)` for `1.7.*`).

Getting Started
---------------

#### Install jdk and maven

* Install openjdk 17 or newer
* Install maven 3.9.2 or newer (or use included ./mvnw script instead of mvn)

#### Build and run tests

* Building with maven will run tests

```
mvn clean install
```

* View test coverage reports for each module at ${module}/target/site/jacoco/index.html

* To use the Leaky Cauldron in a service of your own,
  see [this example](https://github.com/trib3/example-cauldron-service)
  for a recommended project skeleton

Project Layout
--------------
Note that most modules make use of [Guice](https://github.com/google/guice) for dependency injection of configured
instances.

* [`/build-resources`](https://github.com/josephlbarnett/leakycauldron/tree/HEAD/build-resources)

This contains resources for use during a maven build. If depended upon, it should only be brought into test scope.

* [`/parent-pom`](https://github.com/josephlbarnett/leakycauldron/tree/HEAD/parent-pom)

This contains the parent pom to be used for all maven projects at Tribe.

* [`/json`](https://github.com/josephlbarnett/leakycauldron/tree/HEAD/json)

This contains classes and [Guice](https://github.com/google/guice) modules for configuring
[Jackson](https://github.com/FasterXML/jackson) objects to work with Tribe codebases.

* [`/testing`](https://github.com/josephlbarnett/leakycauldron/tree/HEAD/testing)

This contains classes that enhance writing unit tests.

* [`/config`](https://github.com/josephlbarnett/leakycauldron/tree/HEAD/config)

This contains classes for parsing [HOCON](https://github.com/lightbend/config) application.conf files with support for
environmental overrides and AWS KMS-based encrypted values.

* [`/db`](https://github.com/josephlbarnett/leakycauldron/tree/HEAD/db)

This contains classes for injecting configured [jOOQ](https://www.jooq.org) `DSLContext`s and JDBC `DataSource`s into
application data access code.

* [`/server`](https://github.com/josephlbarnett/leakycauldron/tree/HEAD/server)

This contains classes for setting up a configurable [Dropwizard](https://dropwizard.io) based application.

* [`/graphql`](https://github.com/josephlbarnett/leakycauldron/tree/HEAD/graphql)

This contains classes for adding [GraphQL](https://graphql.org) support to an application.

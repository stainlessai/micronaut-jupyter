micronaut-jupyter
===

[![Build Status](https://travis-ci.org/stainlessai/micronaut-jupyter.svg?branch=master)](https://travis-ci.org/stainlessai/micronaut-jupyter)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/ai.stainless/micronaut-jupyter/badge.svg)](https://maven-badges.herokuapp.com/maven-central/ai.stainless/micronaut-jupyter)

A Micronaut configuration that integrates your app with an existing Jupyter
installation.

## Features

#### Micronaut kernel
A special Micronaut kernel is provided to Jupyter that can be used to run
notebooks. This kernel can:
- Execute Groovy code
- Import classes on your Micronaut app's classpath 
- Access Micronaut beans
- Use Micronaut Data repositories
- Use GORM Data Services and dynamic finders
- Access functionality available to the
[BeakerX Groovy](https://nbviewer.jupyter.org/github/twosigma/beakerx/blob/master/StartHere.ipynb)
kernel
  - **Note:** This requires that the `beakerx` Python package (and possibly
    other Jupyter packages) be installed on the system separately.

## Setup

#### Add Build Dependency (Gradle)
Ensure the following repositories are added to your gradle build:
```Groovy
repositories {
    mavenCentral()
    maven { url 'https://jitpack.io' }
}
```
Add the following dependency to your gradle build:
```Groovy
dependencies {
    implementation "ai.stainless:micronaut-jupyter:0.2.2"
}
```

#### Configure your Jupyter Kernels Directory
Ensure that your app can write to a directory where Jupyter will search for
kernels.

##### Option 1 - Easiest (**Possible security vulnerability!**)
The default directory is `/usr/local/share/jupyter/kernels`, so the following
is sufficient:
```bash
chmod 777 /usr/local/share/jupyter/kernels
``` 

##### Option 2 - May be More Secure 
Create a separate directory, say at `/opt/jupyter-alt/kernels`, that your app
can write to. Then, configure Jupyter to search this directory using
[JUPYTER_PATH](https://jupyter.readthedocs.io/en/latest/projects/jupyter-directories.html#envvar-JUPYTER_PATH).
Lastly, add the following config to your application.yml:
```yml
jupyter:
  kernel:
    location: /opt/jupyter-alt/kernels
```

## Usage
When your app starts up, this configuration will make any necessary
configurations to Jupyter. Once both your app and Jupyter are running, login to
Jupyter to start using the features. 

See the [/examples](examples/) directory for example apps that use this
configuration along with sample Jupyter notebooks that take advantage of the
available features.

## Development

#### Branches
The `master` branch contains the latest production release. The `develop` branch contains
the latest stable build. It is recommended that most PRs be submitted
to the `develop` branch in order to ensure that they are based on the most
recent version of the code. Most PRs that are submitted to `master` will be
rebased onto `develop`. Exceptions to this would include things like critical
bugfixes that need to be pushed ahead of the next planned release.

#### Testing

##### Docker Required
In order to run the tests, you must have Docker installed on your dev
environment. The tests use a library called
[Testcontainers](https://www.testcontainers.org/supported_docker_environment/),
so you'll need to meet its system requirements (see link) in order to run the
tests. The tests run in Travis CI out of the box; on a local machine,
installing Docker Desktop should be sufficient.

##### Running the Tests
Run all the tests:
```bash
./gradlew test
```

Run integration and unit tests separately
```bash
./gradlew integrationTest
./gradlew unitTest
```

Gradle's `--tests` option won't apply to subtasks, so use `-Ptests` to filter
the tests:
```bash
./gradlew test -Ptests=*BasicGroovy*
./gradlew integrationTest -Ptests=*Finder*
```

#### Building
```bash
./gradlew build
```

#### Planned Features
- [x] Import classes on app classpath in Jupyter script
- [x] Access GORM methods in Jupyter scripts
- [x] Access beans in Jupyter script
- [ ] Ability to add custom methods and properties to scope of Jupyter script
- [x] Access GORM Data Services from Jupyter script
- [x] Access Micronaut Data repositories from Jupyter script

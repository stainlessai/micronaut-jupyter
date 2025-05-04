micronaut-jupyter
===

[![Build Status](https://travis-ci.org/stainlessai/micronaut-jupyter.svg?branch=master)](https://travis-ci.org/stainlessai/micronaut-jupyter)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/ai.stainless/micronaut-jupyter/badge.svg)](https://maven-badges.herokuapp.com/maven-central/ai.stainless/micronaut-jupyter)
[![](https://jitpack.io/v/stainlessai/micronaut-jupyter.svg)](https://jitpack.io/#stainlessai/micronaut-jupyter)

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

### JupyterLab

This kernel has been tested against the following configuration (it may work with later versions as well):

```
ipykernel==6.29.5
notebook==6.5.7
tornado==6.4.1
jupyter-client==7.4.9
jupyterlab==3.5.3
```

### Micronaut 4 Compatibility

At the time of this release the tested versions are:

```
micronautVersion=4.7.1
groovyVersion=4.0.26
micronautDataVersion=4.12.0
```

The project has been updated to support Micronaut 4.0+, however, it requires BeakerX 2.0
libraries which have not yet been published by the BeakerX project team at the time of this writing. To build the required
libraries locally, follow these steps:

```
git clone --recurse-submodules https://github.com/stainlessai/beakerx-jlab2  

cd beakerx-jlab2/beakerx_kernel_base
./gradlew install -xtest        

cd ../beakerx_kernel_groovy
./gradlew install -xtest
```              

The beakerx-*-2.0-SNAPSHOT jars should now be in your local maven cache, e.g.:
```
~/.m2/repository/com/twosigma/beakerx-kernel-base/2.0-SNAPSHOT/beakerx-kernel-base-2.0-SNAPSHOT.jar 
``` 

### Building
Build the library and publish to local maven:
```bash
./gradlew publishToMavenLocal
```                          

### Usage
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
    implementation "ai.stainless:micronaut-jupyter:0.2.4"
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



#### Planned Features
- [x] Import classes on app classpath in Jupyter script
- [x] Access GORM methods in Jupyter scripts
- [x] Access beans in Jupyter script
- [ ] Ability to add custom methods and properties to scope of Jupyter script
- [x] Access GORM Data Services from Jupyter script
- [x] Access Micronaut Data repositories from Jupyter script

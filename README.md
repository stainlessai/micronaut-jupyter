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
- Do anything the
[BeakerX Groovy](https://nbviewer.jupyter.org/github/twosigma/beakerx/blob/master/StartHere.ipynb)
kernel can
- Import classes on your Micronaut app's classpath 

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
    implementation "ai.stainless:micronaut-jupyter"
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

#### Test and Build
```bash
./gradlew test
./gradlew build
```

#### Planned Features
- [x] Import classes on app classpath in Jupyter script
- [ ] Access GORM methods in Jupyter scripts
- [ ] Access beans in Jupyter script
- [ ] Ability to add custom methods and properties to scope of Jupyter script
- [ ] Access GORM Data Services from Jupyter script
- [ ] Access Micronaut Data repositories from Jupyter script

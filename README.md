# Container Linux Jenkins Projects

The Container Linux Jenkins jobs are implemented as Pipeline projects. The Groovy code that runs the automated builds and tests for the Container Linux operating system is found here.

## Installation

This repository is currently provided as a reference. In order to run these jobs at another site, they will need to be customized to use different storage locations, node tags, credentials, etc.

To get started, a fresh Jenkins server can be run in a container.

```sh
docker run -p 8080:8080 -p 50000:50000 jenkins
```

When the server is accessible, go to *Manage Jenkins* and *Script Console*. The contents of the file `init.groovy` can be pasted directly into the text box on this page to install all of the Container Linux OS jobs.

To initialize all job properties (parameters, timers, etc.) from the Groovy scripts, the following should each be built once manually:

  - `mantle/master-builder` provides the binary artifacts for all OS jobs.
  - `os/glsa` compares security advisories against current upstream Gentoo.
  - `os/nightly-build` triggers every other OS job.

### Nodes

For ARM64 nodes the JDK must be installed manually by extracting the ARM64 JDK tarball on the node.  The JDK must either be installed to one of the Jenkins JDK search paths, `/home/$USER/jdk` for example, or the node environment variable `$JAVA_HOME` must be set.

## Usage

By default, the nightly build and other OS job parameters always build from the `master` branches of both this and the manifest repositories. To build a Container Linux release, build the `os/manifest` job and set the `MANIFEST_REF` parameter to the release tag. To pull all Groovy scripts for an OS build from a different branch of this repository, set the `PIPELINE_BRANCH` parameter on the `os/manifest` job.

## Development

The jobs are configured to allow copying the entire OS folder and making modifications to the copy. Clicking *New Item* on the main Jenkins page and typing `os` in the *Copy from* field will recursively copy all OS jobs to a new folder with the given name. When these jobs run and build their downstream jobs, they will all be run from the copied folder. This allows running a complete OS build with modified components independent of the original `os` jobs.

Note that there is no dependency on job types, so the copied jobs can be changed from pulling Groovy from Git to directly pasting a Groovy script in the web form, or they can even be completely replaced with e.g. a freestyle project. Be aware that each of the copied jobs will create its own workspace, greatly increasing disk usage on the Jenkins workers.

When using copied jobs, the `manifest` Groovy code should be modified to replace the prefix of `COREOS_BUILD_ID` and possibly `MANIFEST_URL` to avoid conflicts. The default value of its `PIPELINE_BRANCH` parameter can also be changed to switch source branches of this repository for all of the copied OS build jobs.

## Bugs

Please use the [CoreOS issue tracker][bugs] to report all bugs, issues, and feature requests.

[bugs]: https://github.com/coreos/bugs/issues/new?labels=component/other

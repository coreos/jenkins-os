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

## Usage

By default, the nightly build and other OS job parameters always build from the `master` branches of both this and the manifest repositories. To build a Container Linux release, build the `os/manifest` job and set the `MANIFEST_REF` parameter to the release tag. To pull all Groovy scripts for an OS build from a different branch of this repository, set the `PIPELINE_BRANCH` parameter on the `os/manifest` job.

## Bugs

Please use the [CoreOS issue tracker][bugs] to report all bugs, issues, and feature requests.

[bugs]: https://github.com/coreos/bugs/issues/new?labels=component/other

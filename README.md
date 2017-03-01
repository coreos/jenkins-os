# Container Linux Jenkins Projects

The Jenkins jobs for the Container Linux continuous delivery system are implemented as Pipeline projects. The Groovy code for those is found here.

This repository is currently provided as a reference.

## Jenkins Setup

To get started, a fresh Jenkins server can be run in a container.

```sh
docker run -p 8080:8080 -p 50000:50000 jenkins
```

### Plugins

At a minimum, install the following Jenkins plugins:

  - Config File Provider
  - Folders
  - Git
  - Pipeline
  - Slack (Optional, install to get slack notifications.)

### Config

Create Jenkins credentials of type and ID:

| Credential Type               | Credential ID                  | Use |
| ----------------------------- | ------------------------------ | --- |
| Secret file                   | GPG_SECRET_KEY_FILE            | Private key to sign build artifacts. |
| Secret file                   | GOOGLE_APPLICATION_CREDENTIALS | Credentials for upload to Google Storage. See [OAuth2](https://developers.google.com/identity/protocols/OAuth2). |
| SSH Username with private key | MANIFEST_BUILDS_KEY            | Push to manifest-build git repository. |

Using the Jenkins *Config File Provider* plugin, create Jenkins configuration files of type and ID:

| File Type   | File ID        | Use |
| ----------- | -------------- | --- |
| Groovy file | JOB_CONFIG     | Job config file.  See [*sample-job-config.groovy*](./sample-job-config.groovy) |
| Custom file | GPG_VERIFY_KEY | Public key of the GPG_SECRET_KEY_FILE credential. |

### Nodes

For ARM64 nodes the JDK must be installed manually by extracting the ARM64 JDK tarball on the node.  The JDK must either be installed to one of the Jenkins JDK search paths, `/home/$USER/jdk` for example, or the node environment variable `$JAVA_HOME` must be set.

#### Labels

Add labels to the Jenkins executors as follows:

| Label          | Use |
| -------------- | --- |
| amd64, arm64   | Machine architecture. |
| coreos, ubuntu | Machine host operating system. |
| docker         | Jenkins account can run Docker. |
| gce            | Machine is Google Compute Engine. |
| kvm            | Jenkins account can run KVM. |
| sudo           | Jenkins account has sudo privileges. |

## Job Installation

Go to *Manage Jenkins* and *Script Console*. The contents of the file `init.groovy` can be pasted directly into the text box on this page to install all of the Container Linux OS jobs.

To initialize all job properties (parameters, timers, etc.) from the Groovy scripts, the following should each be built once manually:

  - `mantle/master-builder` provides the binary artifacts for all OS jobs.
  - `os/glsa` compares security advisories against current upstream Gentoo.
  - `os/nightly-build` triggers every other OS job.

### Job Hierarchy

```
    mantle/master-builder
    └── os/manifest
        ├── os/sdk
        └── os/toolchains
            └── os/board/packages-matrix
                └── os/board/image-matrix
                    └── os/board/sign-image (if COREOS_OFFICIAL == 1)
                        └── os/board/vm-matrix
                            ├── os/kola/qemu
                            └── os/kola/gce
```

## Usage

By default, the nightly build and other OS job parameters always build from the `master` branches of both this and the manifest repositories. To build a Container Linux release, build the `os/manifest` job and set the `MANIFEST_REF` parameter to the release tag. To pull all Groovy scripts for an OS build from a different branch of this repository, set the `PIPELINE_BRANCH` parameter on the `os/manifest` job.

## Development

The jobs are configured to allow copying the entire OS folder and making modifications to the copy. Clicking *New Item* on the main Jenkins page and typing `os` in the *Copy from* field will recursively copy all OS jobs to a new folder with the given name. When these jobs run and build their downstream jobs, they will all be run from the copied folder. This allows running a complete OS build with modified components independent of the original `os` jobs.

Note that there is no dependency on job types, so the copied jobs can be changed from pulling Groovy from Git to directly pasting a Groovy script in the web form, or they can even be completely replaced with e.g. a freestyle project. Be aware that each of the copied jobs will create its own workspace, greatly increasing disk usage on the Jenkins workers.

When using copied jobs, the `manifest` Groovy code should be modified to replace the prefix of `COREOS_BUILD_ID` and possibly `MANIFEST_URL` to avoid conflicts. The default value of its `PIPELINE_BRANCH` parameter can also be changed to switch source branches of this repository for all of the copied OS build jobs.

## Bugs

Please use the [CoreOS issue tracker][bugs] to report all bugs, issues, and feature requests.

[bugs]: https://github.com/coreos/bugs/issues/new?labels=component/other

# Container Linux Jenkins Projects

The Jenkins jobs that build and test Container Linux are implemented as Pipeline projects. The Groovy code that defines these jobs, the initialization procedure, and utility library functions is found here.

## Overview

Each Container Linux build starts from the `os/manifest` job with a manifest defined in a [Git repository][manifest]. Jenkins takes one of these manifests, possibly modifies it for development builds, and uploads the final version to a [Git repository of build manifests][manifest-builds].

After a manifest is pushed, it is read by all subsequent jobs to build the operating system. First, development files such as compiler toolchain packages and the SDK are built. Next, the full set of packages is built, then a raw disk image is built from these packages, and that image is converted to all of the platform-specific formats. As the build progresses, the produced files are signed and uploaded to Google Storage buckets.

Once working disk images are uploaded, they are used by automated tests on various hosting platforms.

## Usage examples

By default, the nightly build and other OS job parameters always build from the `master` branch of the manifest repository. To build a Container Linux release, build the `os/manifest` job and set the `MANIFEST_REF` parameter to the release tag.

For time-critical security releases, a previous release version can be given to the `os/manifest` job with its `RELEASE_BASE` parameter. This will skip building development files and reuse unchanged binary packages from that version rather than building everything from scratch again, greatly reducing build time.

To test pull requests in the various CoreOS repositories, the `os/manifest` job has the `LOCAL_MANIFEST` parameter to override defined projects. For example, in the following XML, replace `NUMBER` with an overlay pull request number to build and test it.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<manifest>
 <remove-project name="coreos/coreos-overlay"/>
 <project name="coreos/coreos-overlay"
          path="src/third_party/coreos-overlay"
          revision="refs/pull/NUMBER/head"/>
</manifest>
```

## Pipeline development

To pull all Groovy scripts used by an OS build from a different branch of this repository, set the `PIPELINE_BRANCH` parameter on the `os/manifest` job. This value is propagated to all downstream jobs.

For testing larger structural changes, the jobs are configured to allow copying the entire OS folder and making modifications to the copy. Clicking *New Item* on the main Jenkins page and typing `os` in the *Copy from* field will recursively copy all OS jobs to a new folder with the given name. When these jobs run and build their downstream jobs, they will all be run from the copied folder. This allows running a complete OS build with modified components independent of the original `os` jobs.

Note that there is no dependency on job types, so the copied jobs can be changed from pulling Groovy from Git to directly pasting a Groovy script in the web form, or they can even be completely replaced e.g. with a freestyle project. Be aware that each of the copied jobs will create its own workspace, greatly increasing disk usage on the Jenkins workers.

## Configure a Jenkins instance to build Container Linux

This section will walk through the initial installation and general configuration of a Jenkins instance to prepare it to duplicate the Container Linux build process used at CoreOS. The next section covers site-specific customizations.

### Start a new Jenkins master

The Container Linux projects can be added into an existing Jenkins instance, or a new master server can be started to run them. For example, a new Jenkins server can run in a container.

```sh
docker run -p 8080:8080 -p 50000:50000 jenkins
```

Complete any initial site-specific configuration steps, such as user authentication and permissions. Plugins, credentials, and projects for Container Linux will be installed in the following steps.

### Configure worker nodes

At least one worker should be configured. The recommended configuration for workers is using the `ssh-slaves` plugin to connect to Container Linux on a bare metal AMD64 system with KVM support, with a `jenkins` user in the `docker` and `sudo` groups. The working directory must be on a read/write partition, e.g. at `/opt/jenkins`.

Workers should have labels applied from the following list.

  - `amd64` (or other Gentoo-style architecture keywords) declares the worker's CPU architecture.
  - `coreos` declares the worker's operating system is Container Linux by CoreOS.
  - `docker` declares the Jenkins user has permission to start Docker containers.
  - `kvm` declares `/dev/kvm` exists on the worker and it is accessible by the Jenkins user.
  - `sudo` declares the Jenkins user has permission to run the `sudo` command.

Most projects require `coreos && amd64 && sudo` to run the SDK environment. The mantle project requires `amd64 && docker` to build in a container. The QEMU-based tests require `amd64 && kvm` for faster VMs.

### Create the operating system projects

Go to *Manage Jenkins*, then *Script Console*. The contents of the file `init.groovy` can be pasted directly into the text box on this page to create the OS projects.

If any required plugins are missing, the script will first install them and restart Jenkins to enable them. In this case, navigate back to *Script Console* once the server has finished restarting, and run `init.groovy` again to create the OS projects.

The `init.groovy` script defines the projects with placeholder properties which will be replaced when they are first built. At this point, the following projects can be built once manually to properly initialize them.

  - `mantle/master-builder` builds the CoreOS mantle project to provide current binaries to the OS jobs. After running the first time, it will automatically rebuild when there are new changes in the mantle repository.
  - `os/keyring` creates a GPG keyring of public keys used to verify signed tags and binaries. The first run will only include the CoreOS buildbot key, which has signed all official Container Linux release files. This job is customized in later steps.

### Add a trusted Pipeline library

Operations defined under the `vars` directory may be forbidden by the Groovy sandbox. However, when the Container Linux projects call them from a trusted Pipeline library, they are exempt from sandbox restrictions. In this configuration, no sandbox exceptions are required by the Container Linux projects.

Go to *Manage Jenkins*, then *Configure System*, and scroll to *Global Pipeline Libraries*. Add a new library named after this repository, e.g. `coreos-jenkins-os`. For security reasons, this library should be fixed at a known-good commit hash for its default version. Using `master` will always pull the latest code. The projects currently expect this library to be loaded implicitly, so ensure that setting is enabled. Select to retrieve from *Modern SCM*, *Git*, and give it this repository's URL. Save this configuration.

## Write a build profile

After completing the above section, Jenkins only needs to be given site-specific credentials and paths, defined in swappable profiles under `resources`.

The Container Linux build process will attempt to load the file `resources/com/coreos/profiles/default.json` by default, but this file does not exist. It should be provided by a site-specific Pipeline library that will be created in this section. The easiest way to write this file is to copy `developer.json` to `default.json`, and replace its values as they are generated in the following steps.

Note that when creating Jenkins credentials below, they can be scoped under the `os` folder to restrict which projects can access them.

### Choose manifest settings

The profile defines a few settings that can be customized to define how development build manifests are created. Change the following as needed.

  - `BUILD_ID_PREFIX` is the tag prefix that gets pushed to the build manifest repository.
  - `GIT_AUTHOR_NAME` and `GIT_AUTHOR_EMAIL` provide authorship information in the manifest commit.
  - `MANIFEST_URL` is the source repository of manifest files. If the [CoreOS manifests][manifest] will be the basis of every Container Linux build on this Jenkins instance, this does not need to be changed.

While not necessarily manifest-related, the profile's `GROUP` setting defines which update group the produced images should use by default. This should normally be `developer` in the default profile.

### Create a repository for build manifests

Jenkins must have SSH access to push manifest commits to a Git repository. If the repository is not publicly readable, its authentication method for cloning also must be SSH.

Generate SSH keys to grant write access to the repository.

```sh
ssh-keygen -b 4096 -f git.deploy -N '' -t rsa
```

Authorize the public key `git.deploy.pub` on whatever server hosts the Git repository. When hosting with GitHub, add a deploy key under the project's *Settings*, then paste the contents of `git.deploy.pub` and select to give it write access.

Create new *SSH Username with private key* credentials in Jenkins, and paste the contents of `git.deploy` in the private key field. When using GitHub, the SSH user is `git`. Define the following profile settings accordingly.

  - `BUILDS_CLONE_URL` is a readable URL for this repository.
  - `BUILDS_CLONE_CREDS` can be omitted or blank if the repository is public. Otherwise, set this to the credentials ID of the SSH private key.
  - `BUILDS_PUSH_URL` is the SSH URL of this repository. If it is hosted on GitHub, the value should begin with `ssh://git@github.com/`.
  - `BUILDS_PUSH_CREDS` is the credentials ID of the SSH private key.

### Generate GPG keys for signing everything

All Container Linux files and Git tags created by Jenkins will be signed to verify that they have not been altered when they are downloaded later.

Choose a sensible e-mail address for this Jenkins instance, and set the variable `SIGNING_USER` to it in the following commands.

```sh
GNUPGHOME="$PWD/gpg" ; export GNUPGHOME
mkdir --mode=0700 "$GNUPGHOME"
gpg2 --batch --passphrase '' --quick-gen-key --yes "$SIGNING_USER"
gpg2 --armor --export --output pub.asc "$SIGNING_USER"
gpg2 --armor --export-secret-keys --output gpg.asc "$SIGNING_USER"
```

Create new *Secret file* credentials in Jenkins, and upload `gpg.asc`. Specify the following profile settings.

  - `SIGNING_USER` is the e-mail address used when generating the keys.
  - `SIGNING_CREDS` is the credentials ID of the GPG private key, prefixed with `file:`.

The profile's `VERIFY_KEYRING` value can be defined in one of two ways.

 1. If this Jenkins instance will only run builds for development and testing, the entire contents of `pub.asc` can be the value of `VERIFY_KEYRING` as a multi-line string. (The JSON parser is not very strict.)
 2. To support building releases with manifest versions signed by developers, set `VERIFY_KEYRING` to `artifact:/os/keyring:keyring.asc`. This special format will use the keyring from an artifact, which allows generating it with multiple developers' keys while automatically updating them from key servers. Build the `os/keyring` job with the contents of `pub.asc` given as the `IMPORT_KEYRING` parameter to include it, and specify any developers' key IDs in the `FINGERPRINTS` parameter.

### Set up Google Storage buckets and service accounts

Jenkins differentiates between development and release files, and it can upload them to different storage buckets. For simplicity, both file types can be uploaded to the same location, too.

On the [Google Cloud Platform web console][google-console], select *IAM & Admin*, then go to the *Service accounts* page. Create a service account here, and give it the *Storage Object Creator* and *Compute Instance Admin (v1)* roles. Create a key on this account, and download it in JSON format.

Still on the web console, select *Storage*, and go to *Browser*. Create a new storage bucket, or choose an existing bucket for Jenkins to use. Give the service account *Writer* permission on this storage bucket.

Create new *Secret file* credentials in Jenkins, and upload the service account's JSON key file. Specify the following profile settings.

  - `GS_DEVEL_ROOT`, `GS_RELEASE_ROOT`, and `GS_RELEASE_DOWNLOAD_ROOT` can all be set to `gs://`, followed by the bucket name, optionally followed by a path prefix to store Container Linux files.
  - `GS_DEVEL_CREDS` and `GS_RELEASE_CREDS` should be the credentials ID of the service account's JSON key.

### Create a Pipeline library to store the new profile

At this point, all profile settings should be ready. The profile can be made available as a Pipeline library resource by committing it to any Git repository with the path `resources/com/coreos/profiles/default.json`. The repository may also need a `src` or `vars` directory for Jenkins to identify it as a Pipeline library. If a library is only used for resources, it does not need to be trusted, so it can be configured on the `os` folder to limit its scope.

When the Pipeline library is configured, the `os/manifest` job can be run to build Container Linux, and it should use the new `default` profile automatically. The `PROFILE` parameter can specify a different profile to load, which is given as the base name of any JSON file under `resources/com/coreos/profiles`. Note that a profile can inherit and override settings from another profile with the `PARENT` JSON key.

## Bugs

Please use the [CoreOS issue tracker][bugs] to report all bugs, issues, and feature requests.

[bugs]: https://github.com/coreos/bugs/issues/new?labels=component/other
[google-console]: https://console.cloud.google.com/
[manifest]: https://github.com/coreos/manifest
[manifest-builds]: https://github.com/coreos/manifest-builds

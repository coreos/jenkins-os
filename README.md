# Container Linux Jenkins Projects

The Container Linux Jenkins jobs are implemented as Pipeline projects. The Groovy code that runs the automated builds and tests for the Container Linux operating system is found here.

## Usage

This repository is currently provided as a reference. In order to run these jobs at another site, they will need to be customized to use different storage locations, node tags, credentials, etc.

To get started, a fresh Jenkins server can be run in a container.

```sh
docker run -p 8080:8080 -p 50000:50000 jenkins
```

When the server is accessible, go to *Manage Jenkins* and *Script Console*. The contents of the file `init.groovy` can be pasted directly into the text box on this page to install all of the Container Linux OS jobs.

## Bugs

Please use the [CoreOS issue tracker][bugs] to report all bugs, issues, and feature requests.

[bugs]: https://github.com/coreos/bugs/issues/new?labels=component/other

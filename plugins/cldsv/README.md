# Container Linux Downstream View - Jenkins Plugin

This plugin adds a sidebar link to each build of a job under the `os` directory that generates a page displaying the tree of all related runs in a Container Linux build. It shows the status, name, number, and distinguishing job parameter values (`MANIFEST_REF` or `BOARD`) of each build. When following a build's sidebar link, that build will be highlighted in the tree. Any rebuilds will also be included in the tree.

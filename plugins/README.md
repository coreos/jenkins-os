# Container Linux Jenkins Plugins

Each subdirectory here is the source tree for a Jenkins plugin that must be built with Gradle. If Gradle is not installed, run the following to use a container as a substitute.

```sh
alias gradle='docker run --rm -u "$(id -u):$(id -g)" -v "$PWD:/project" -w /project gradle gradle'
```

Change into a plugin's directory and run `gradle jpi` to build it. The plugin `.jpi` file will be written under `build/libs`, which can be uploaded at `${JENKINS_URL}/pluginManager/advanced` to install it.

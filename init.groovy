/*
 * Create all OS projects on a new Jenkins server.
 *
 * This entire script can be pasted directly into the text box found at
 * ${JENKINS_URL}/script to populate the server with OS jobs.  It will
 * define everything based on the contents of this repository.
 *
 * If any required plugins are not installed when this script is run,
 * they will be downloaded and installed automatically, and Jenkins will
 * be restarted to enable them.  In this case, this script must be run
 * again after the restart to create the jobs.
 *
 * Note that settings such as user permissions and secret credentials
 * are not handled by this script.
 */

/* Install required plugins and restart Jenkins, if necessary.  */
final List<String> REQUIRED_PLUGINS = [
    "aws-credentials",
    "copyartifact",
    "git",
    "ssh-agent",
    "tap",
    "workflow-aggregator",
]
if (Jenkins.instance.pluginManager.plugins.collect {
        it.shortName
    }.intersect(REQUIRED_PLUGINS).size() != REQUIRED_PLUGINS.size()) {
    REQUIRED_PLUGINS.collect {
        Jenkins.instance.updateCenter.getPlugin(it).deploy()
    }.each {
        it.get()
    }
    Jenkins.instance.restart()
    println 'Run this script again after restarting to create the jobs!'
    throw new RestartRequiredException(null)
}

/* Define what to clone.  */
final String REPO_URL = 'https://github.com/coreos/jenkins-os.git'
final String REPO_BRANCH = 'master'

/*
 * Create a new folder project under the given parent model.
 */
Actionable createFolder(String name,
                        ModifiableTopLevelItemGroup parent = Jenkins.instance,
                        String description = '') {
    parent.createProjectFromXML(name, new ByteArrayInputStream("""\
<?xml version="1.0" encoding="UTF-8"?>
<com.cloudbees.hudson.plugins.folder.Folder plugin="cloudbees-folder">
  <description>${description}</description>
  <healthMetrics>
    <com.cloudbees.hudson.plugins.folder.health.WorstChildHealthMetric>
      <nonRecursive>true</nonRecursive>
    </com.cloudbees.hudson.plugins.folder.health.WorstChildHealthMetric>
  </healthMetrics>
</com.cloudbees.hudson.plugins.folder.Folder>
""".bytes))
}

/*
 * Create a new pipeline project under the given parent model.
 *
 * This XML template assumes all jobs will pull the Groovy source from
 * the repository and that the source has a properties step to overwrite
 * the initial parameter definitions.
 */
Job createPipeline(String name,
                   ModifiableTopLevelItemGroup parent = Jenkins.instance,
                   String description = '',
                   String repo = REPO_URL,
                   String branch = REPO_BRANCH,
                   String script = 'Jenkinsfile',
                   String defaultPipelineBranch = REPO_BRANCH) {
    parent.createProjectFromXML(name, new ByteArrayInputStream("""\
<?xml version="1.0" encoding="UTF-8"?>
<flow-definition plugin="workflow-job">
  <description>${description}</description>
  <properties>
    <hudson.model.ParametersDefinitionProperty>
      <parameterDefinitions>
        <hudson.model.StringParameterDefinition>
          <name>PIPELINE_BRANCH</name>
          <description>Branch to use for fetching the pipeline jobs</description>
          <defaultValue>${defaultPipelineBranch}</defaultValue>
        </hudson.model.StringParameterDefinition>
      </parameterDefinitions>
    </hudson.model.ParametersDefinitionProperty>
  </properties>
  <definition class="org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition" plugin="workflow-cps">
    <scm class="hudson.plugins.git.GitSCM" plugin="git">
      <configVersion>2</configVersion>
      <userRemoteConfigs>
        <hudson.plugins.git.UserRemoteConfig>
          <url>${repo}</url>
        </hudson.plugins.git.UserRemoteConfig>
      </userRemoteConfigs>
      <branches>
        <hudson.plugins.git.BranchSpec>
          <name>*/${branch}</name>
        </hudson.plugins.git.BranchSpec>
      </branches>
      <extensions>
        <hudson.plugins.git.extensions.impl.CleanBeforeCheckout/>
      </extensions>
    </scm>
    <scriptPath>${script}</scriptPath>
  </definition>
</flow-definition>
""".bytes))
}

/* Create a temporary directory for cloning the pipeline repository.  */
def proc = ['/bin/mktemp', '--directory'].execute()
proc.waitFor()
if (proc.exitValue() != 0)
    throw new Exception('Could not create a temporary directory')
final String REPO_PATH = proc.text.trim()

/* Fetch all the OS Groovy pipeline scripts.  */
proc = ['/usr/bin/git', 'clone', "--branch=${REPO_BRANCH}", '--depth=1', REPO_URL, REPO_PATH].execute()
proc.waitFor()
if (proc.exitValue() != 0)
    throw new Exception("Could not clone ${REPO_URL} into ${REPO_PATH}")

/* List every OS pipeline and directory in the repository.  */
proc = ['/usr/bin/find', "${REPO_PATH}/os", '-type', 'f', '-name', '*.groovy', '-o', '-type', 'd'].execute()
proc.waitFor()
if (proc.exitValue() != 0)
    throw new Exception("Could not walk ${REPO_PATH}")

/* Create projects mirroring the repository layout.  */
def dirStack = [REPO_PATH]
def folderStack = [Jenkins.instance]
proc.text.eachLine { path ->
    while (!path.startsWith("${dirStack[-1]}/")) {
        dirStack.pop()
        folderStack.pop()
    }
    if (path.endsWith('.groovy')) {
        String branch = REPO_BRANCH
        if (new File(path).text.contains('PIPELINE_BRANCH'))
            branch = '${PIPELINE_BRANCH}'
        createPipeline(path[dirStack[-1].length() + 1 .. -8],
                       folderStack[-1],
                       '',
                       REPO_URL,
                       branch,
                       path.substring(REPO_PATH.length() + 1),
                       REPO_BRANCH)
    } else {
        dirStack.push(path)
        folderStack.push(createFolder(path.split('/')[-1], folderStack[-1]))
    }
}

/* Also add a mantle job using the Groovy script from its own repository.  */
createPipeline('master-builder',
               createFolder('mantle'),
               'Build mantle from master for the other jobs.',
               'https://github.com/coreos/mantle.git',
               'cl',
               'Jenkinsfile',
               'master')

/* Clean up the temporary repository.  */
proc = ['/bin/rm', '--force', '--recursive', REPO_PATH].execute()
proc.waitFor()
if (proc.exitValue() != 0)
    throw new Exception("Could not remove ${REPO_PATH}")

println 'OS jobs were successfully created.'

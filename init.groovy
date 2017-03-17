/*
 * Create all OS projects on a new Jenkins server.
 *
 * This entire script can be pasted directly into the text box found at
 * ${JENKINS_URL}/script to populate the server with OS jobs.  It will
 * define everything based on the contents of this repository.
 *
 * Note that settings such as user permissions and secret credentials
 * are not handled by this script.  For Jenkins configuration info see
 * the README file at https://github.com/coreos/jenkins-os.
 */

import com.cloudbees.hudson.plugins.folder.Folder
import org.jenkinsci.plugins.workflow.job.WorkflowJob

/* Define what to clone.  */
final String JOB_REPO_URL = 'https://github.com/coreos/jenkins-os.git'
final String JOB_REPO_BRANCH = 'master'
final String MANTLE_REPO_URL = 'https://github.com/coreos/mantle.git'

/*
 * Create a new folder project under the given parent model.
 */
Folder createFolder(String name,
                    ModelObjectWithChildren parent = Jenkins.instance,
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
WorkflowJob createPipeline(String name,
                           ModelObjectWithChildren parent = Jenkins.instance,
                           String description = '',
                           String repo = JOB_REPO_URL,
                           String branch = JOB_REPO_BRANCH,
                           String script = 'Jenkinsfile',
                           String defaultPipelineBranch = JOB_REPO_BRANCH) {
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
proc = ['/usr/bin/git', 'clone', "--branch=${JOB_REPO_BRANCH}", '--depth=1', JOB_REPO_URL, REPO_PATH].execute()
proc.waitFor()
if (proc.exitValue() != 0)
    throw new Exception("Could not clone ${JOB_REPO_URL} into ${REPO_PATH}")

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
        String branch = JOB_REPO_BRANCH
        if (new File(path).text.contains('PIPELINE_BRANCH'))
            branch = '${PIPELINE_BRANCH}'
        createPipeline(path[dirStack[-1].length() + 1 .. -8],
                       folderStack[-1],
                       '',
                       JOB_REPO_URL,
                       branch,
                       path.substring(REPO_PATH.length() + 1),
                       JOB_REPO_BRANCH)
    } else {
        dirStack.push(path)
        folderStack.push(createFolder(path.split('/')[-1], folderStack[-1]))
    }
}

/* Also add a mantle job using the Groovy script from its own repository.  */
createPipeline('master-builder',
               createFolder('mantle'),
               'Build mantle from master for the other jobs.',
               MANTLE_REPO_URL,
               'master',
               'Jenkinsfile',
               'master')

/* Clean up the temporary repository.  */
proc = ['/bin/rm', '--force', '--recursive', REPO_PATH].execute()
proc.waitFor()
if (proc.exitValue() != 0)
    throw new Exception("Could not remove ${REPO_PATH}")

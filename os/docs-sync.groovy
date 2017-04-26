#!groovy

properties([
    parameters([
        string(name: 'VERSION',
               defaultValue: '',
               description: 'Which OS version to sync'),
        choice(name: 'CHANNEL',
               choices: "alpha\nbeta\nstable",
               description: 'Which release channel to use'),
    ])
])

/* The following might want to be configured.  */
def loginCreds = 'bf35c69b-a706-43be-8d59-fed1a1055b7e'
def sshCreds = 'f40af2c1-0f07-41c4-9aad-5014dd213a3e'
def gsCreds = '9b77e4af-a9cb-47c8-8952-f375f0b48596'

def docsProject = 'coreos-inc/coreos-pages'
def pagesProject = 'coreos-inc/pages'

def gitAuthor = 'Jenkins OS'
def gitEmail = 'team-cl@coreos.com'

def gcRepo = 'https://packages.cloud.google.com/yum/repos/cloud-sdk-el7-x86_64'

/* Generate all of the actual required strings from the above values.  */
def channel = params.CHANNEL
def version = params.VERSION
def branch = "build-${version.split(/\./)[0]}"

def username = 'coreosbot'
withCredentials([usernamePassword(credentialsId: loginCreds,
                                  passwordVariable: 'password',
                                  usernameVariable: 'nameFromCreds')]) {
    username = nameFromCreds  // Extract the actual user name from credentials.
}
def forkProject = "${username}/${docsProject.split('/')[-1]}"

def docsUrl = "ssh://git@github.com/${docsProject}.git"
def forkUrl = "ssh://git@github.com/${forkProject}.git"
def pagesUrl = "ssh://git@github.com/${pagesProject}.git"

node('amd64 && docker') {
    sshagent([sshCreds]) {
        /* The git step ignores the sshagent environment, so script it.  */
        stage('SCM') {
            sh """#!/bin/bash -ex
rm -fr coreos-pages pages
git clone ${docsUrl} coreos-pages
git clone --depth=1 ${pagesUrl} pages
"""  /* Editor quote safety: " */
        }

        stage('Build') {
            sh '''#!/bin/bash -ex
docker run --rm -i \
    -v "$PWD/pages/sync:/source" \
    -w /source \
    scorpil/rust:stable \
    /bin/bash -ex << EOF
apt-get update
apt-get -y install g++ make
cargo install -f
cp /root/.cargo/bin/sync .
chown -R $(id -u):$(id -g) .
EOF
'''  /* Editor quote safety: ' */
        }

        stage('Sync') {
            withCredentials([
                [$class: 'FileBinding',
                 credentialsId: gsCreds,
                 variable: 'GOOGLE_APPLICATION_CREDENTIALS']
            ]) {
                sh """#!/bin/bash -ex
git -C coreos-pages config user.name '${gitAuthor}'
git -C coreos-pages config user.email '${gitEmail}'
git -C coreos-pages checkout -B ${branch}

cp "\${GOOGLE_APPLICATION_CREDENTIALS}" account.json
docker run --rm -i \
    -v "\$PWD:/workspace" \
    -w /workspace/coreos-pages \
    fedora:latest \
    /bin/bash -ex << EOF
trap 'shred -u /workspace/account.json' EXIT
dnf --repofrompath='gcloud,${gcRepo}' -y install git google-cloud-sdk jq which
gcloud auth activate-service-account --key-file=/workspace/account.json
../pages/scripts/sync-release ${channel} ${version}
chown -R \$(id -u):\$(id -g) .
EOF

(cd pages/sync && exec ./sync -p os -r ${version})

git -C coreos-pages add .
git -C coreos-pages commit -am 'os: sync ${version}'
git -C coreos-pages push -f ${forkUrl} ${branch}
"""  /* Editor quote safety: " */
            }
        }
    }

    stage('PR') {
        withCredentials([usernamePassword(credentialsId: loginCreds,
                                          passwordVariable: 'password',
                                          usernameVariable: 'username')]) {
            createPullRequest(username: username,
                              password: password,
                              upstreamProject: docsProject,
                              sourceBranch: branch,
                              title: "os: sync ${version}",
                              message: "From: ${env.BUILD_URL}")
        }
    }
}

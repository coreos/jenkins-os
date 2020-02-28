#!groovy

properties([
    parameters([
        string(name: 'VERSION',
               defaultValue: '',
               description: 'Which OS version to sync'),
        choice(name: 'CHANNEL',
               choices: "alpha\nbeta\nstable",
               description: 'Which release channel to use'),
        string(name: 'BASE_VERSION',
               defaultValue: '',
               description: '''Copy documentation from this release if \
given, otherwise sync documentation from Git'''),
        choice(name: 'LATEST',
               choices: "auto\nyes\nno",
               description: '''Whether to set the "latest" directory \
to this version, where "auto" enables it only for "alpha" releases'''),
    ])
])

/* The following might want to be configured.  */
def username = 'coreosbot'
def loginCreds = '2b710187-52cc-4e5b-a020-9cae59519baa'
def sshCreds = 'f40af2c1-0f07-41c4-9aad-5014dd213a3e'
def gsCreds = '9b77e4af-a9cb-47c8-8952-f375f0b48596'

def docsProject = 'coreos-inc/coreos-pages'
def pagesProject = 'coreos-inc/pages'

def gitAuthor = 'Jenkins OS'
def gitEmail = 'team-os@coreos.com'

/* Generate all of the actual required strings from the above values.  */
def base = params.BASE_VERSION
def channel = params.CHANNEL
def version = params.VERSION
def branch = "build-${version.split(/\./)[0]}"
def latest = [auto: channel == 'alpha'].withDefault{it == 'yes'}[params.LATEST]

if ((channel != 'alpha' || version =~ '\\d\\.[1-9]\\d*\\.\\d+') && base == '') {
    currentBuild.result = 'FAILURE'
    return
}

def forkProject = "${username}/${docsProject.split('/')[-1]}"

def docsUrl = "ssh://git@github.com/${docsProject}.git"
def forkUrl = "ssh://git@github.com/${forkProject}.git"
def pagesUrl = "ssh://git@github.com/${pagesProject}.git"

node('amd64 && coreos && sudo') {
    stage('SDK') {
        copyArtifacts fingerprintArtifacts: true,
                      projectName: '/mantle/master-builder',
                      selector: lastSuccessful()
        copyArtifacts filter: 'keyring.asc',
                      fingerprintArtifacts: true,
                      projectName: '/os/keyring',
                      selector: lastSuccessful()
        sh '''#!/bin/bash -ex
export GIT_AUTHOR_EMAIL="team-os@coreos.com"
export GIT_AUTHOR_NAME="Jenkins OS"
export GIT_COMMITTER_EMAIL="team-os@coreos.com"
export GIT_COMMITTER_NAME="Jenkins OS"

# Set up GPG for verifying manifest tags.
export GNUPGHOME="${PWD}/gnupg"
rm -fr "${GNUPGHOME}"
trap 'rm -fr "${GNUPGHOME}"' EXIT
mkdir --mode=0700 "${GNUPGHOME}"
gpg --import keyring.asc

# Find the most recent alpha version.
bin/gangue get --json-key=/dev/null \
    gs://alpha.release.core-os.net/amd64-usr/current/version.txt
. version.txt

# Create the SDK used by the most recent alpha without updating.
bin/cork create \
    --replace --verify --verify-signature --verbose \
    --manifest-branch=refs/tags/v${COREOS_VERSION} \
    --manifest-name=release.xml
'''  /* Editor quote safety: ' */
    }

    sshagent([sshCreds]) {
        /* The git step ignores the sshagent environment, so script it.  */
        stage('SCM') {
            sh """#!/bin/bash -ex
export GIT_AUTHOR_EMAIL="team-os@coreos.com"
export GIT_AUTHOR_NAME="Jenkins OS"
export GIT_COMMITTER_EMAIL="team-os@coreos.com"
export GIT_COMMITTER_NAME="Jenkins OS"

rm -fr coreos-pages pages
git clone ${docsUrl} coreos-pages
test -d coreos-pages/_os/${base ?: '.'}  # sanity check
git clone --depth=1 ${pagesUrl} pages
# this should be redundant with the above, but just leave it to be safe
git -C coreos-pages config user.name '${gitAuthor}'
git -C coreos-pages config user.email '${gitEmail}'
git -C coreos-pages checkout -B ${branch}
"""  /* Editor quote safety: " */
        }

        stage('Build') {
            if (base == '')
                sh '''#!/bin/bash -ex
export GIT_AUTHOR_EMAIL="team-os@coreos.com"
export GIT_AUTHOR_NAME="Jenkins OS"
export GIT_COMMITTER_EMAIL="team-os@coreos.com"
export GIT_COMMITTER_NAME="Jenkins OS"

bin/cork enter --bind-gpg-agent=false -- \
    cargo build --package=sync --release --verbose \
        --manifest-path=/mnt/host/source/pages/sync/Cargo.toml
ln -fns target/release/sync  pages/sync/sync
'''  /* Editor quote safety: ' */
        }

        stage('Prune') {
            sh """#!/bin/bash -ex
shopt -s nullglob
channel=${channel}
data=coreos-pages/_data/releases-${channel}.yml
keep=5
this=coreos-pages/_os/${version}
""" + '''
# Drop old releases that are in the same channel as this release.
{
        echo "${this}"
        for release in coreos-pages/_os/*
        do
                grep -qxe "-  *version:  *${release##*/}" "${data}" &&
                echo "${release}"
        done
} | sort -ruV | tail -n +$(( keep + 1 )) | xargs -r rm -fr
git -C coreos-pages commit -am "os: prune old ${channel} releases" || :
'''  /* Editor quote safety: ' */
        }

        stage('Release') {
            withCredentials([
                file(credentialsId: gsCreds,
                     variable: 'GOOGLE_APPLICATION_CREDENTIALS')
            ]) {
                sh """#!/bin/bash -ex
export GIT_AUTHOR_EMAIL="team-os@coreos.com"
export GIT_AUTHOR_NAME="Jenkins OS"
export GIT_COMMITTER_EMAIL="team-os@coreos.com"
export GIT_COMMITTER_NAME="Jenkins OS"

cp "\${GOOGLE_APPLICATION_CREDENTIALS}" account.json && chmod 0600 account.json
trap 'shred -u account.json' EXIT
bin/cork enter --bind-gpg-agent=false -- /bin/bash -ex << 'EOF'
gcloud auth activate-service-account --key-file=/mnt/host/source/account.json
cd /mnt/host/source/coreos-pages
../pages/scripts/sync-release ${channel} ${version}
EOF
"""  /* Editor quote safety: " */
            }
        }

        stage('Sync') {
            sh """#!/bin/bash -ex
if [ -n '${base}' ]
then
        cp -r coreos-pages/_os/${base} coreos-pages/_os/${version}
        sed -i -e 's/${base}/${version}/g' coreos-pages/_os/${version}/*.*
        if ${latest}
        then
                git -C coreos-pages rm -fr _os/latest
                cp -r coreos-pages/_os/${version} coreos-pages/_os/latest
                sed -i \
                    -e '/^os-latest: /s/ .*/ ${version}/' \
                    coreos-pages/_config.yml
                sed -i \
                    -e '/^slug: /s/ .*/ docs-list-latest/' \
                    -e '/^version: /s/ .*/ ${version}/' \
                    coreos-pages/_os/latest/index.html
                sed -i \
                    -e '/^version: /s/ .*/ latest/' \
                    coreos-pages/_os/latest/*.md
        fi
else
        (cd pages/sync ; exec ./sync -p os -r ${version} ${latest ? '' : '-s'})
fi

git -C coreos-pages add .
git -C coreos-pages commit -am 'os: sync ${version}'
git -C coreos-pages push -f ${forkUrl} ${branch}
"""  /* Editor quote safety: " */
        }
    }

    stage('PR') {
        withCredentials([string(credentialsId: loginCreds, variable: 'pat')]) {
            def url = createPullRequest token: pat,
                                        upstreamProject: docsProject,
                                        sourceOwner: username,
                                        sourceBranch: branch,
                                        title: "os: sync ${version}",
                                        message: "From: ${env.BUILD_URL}"
            slackSend color: '#2020C0',
                      message: "${version} (${channel}) docs: ${url}"
        }
    }
}

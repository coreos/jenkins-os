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

def gcRepo = 'https://packages.cloud.google.com/yum/repos/cloud-sdk-el7-x86_64'

/* Generate all of the actual required strings from the above values.  */
def base = params.BASE_VERSION
def channel = params.CHANNEL
def version = params.VERSION
def branch = "build-${version.split(/\./)[0]}"
def latest = [auto: channel == 'alpha'].withDefault{it == 'yes'}[params.LATEST]

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
test -d coreos-pages/_os/${base ?: '.'}  # sanity check
git clone --depth=1 ${pagesUrl} pages
git -C coreos-pages config user.name '${gitAuthor}'
git -C coreos-pages config user.email '${gitEmail}'
git -C coreos-pages checkout -B ${branch}
"""  /* Editor quote safety: " */
        }

        stage('Build') {
            if (base == '')
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

        stage('Prune') {
            sh """#!/bin/bash -ex
shopt -s nullglob
declare -A releases
releases[${version}]=${channel}
""" + '''
# Use the release buckets to determine the channel for previous releases.
for release_dir in coreos-pages/_os/[0-9]*.*[0-9]
do
        release=${release_dir##*/}
        releases[${release}]=
        for channel in stable beta alpha
        do
                board_url="http://${channel}.release.core-os.net/amd64-usr"
                curl -fIs "${board_url}/${release}/version.txt" &&
                releases[${release}]=${channel} &&
                break
        done
done

# Keep five releases from each channel (and drop all releases with no channel).
declare -A kept
while read release
do
        release_dir="coreos-pages/_os/${release}"
        [ -z "${releases[${release}]}" ] && rm -fr "${release_dir}" && continue
        [ "${#kept[${releases[${release}]}]}" -lt 5 ] &&
        kept[${releases[${release}]}]+=. ||
        rm -fr "${release_dir}"
done < <(sort -rV <(IFS=$'\n' ; echo "${!releases[*]}"))
git -C coreos-pages commit -am 'os: prune old releases' || :
'''  /* Editor quote safety: ' */
        }

        stage('Sync') {
            withCredentials([
                [$class: 'FileBinding',
                 credentialsId: gsCreds,
                 variable: 'GOOGLE_APPLICATION_CREDENTIALS']
            ]) {
                sh """#!/bin/bash -ex
cp "\${GOOGLE_APPLICATION_CREDENTIALS}" account.json && chmod 0600 account.json
trap 'shred -u account.json' EXIT
docker run --rm -i \
    -v "\$PWD:/workspace" \
    -w /workspace/coreos-pages \
    fedora:latest \
    /bin/bash -ex << EOF
dnf --repofrompath='gcloud,${gcRepo}' -y install git google-cloud-sdk jq which
gcloud auth activate-service-account --key-file=/workspace/account.json
../pages/scripts/sync-release ${channel} ${version}
chown -R \$(id -u):\$(id -g) .
EOF

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

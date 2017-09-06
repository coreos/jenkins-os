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

        stage('Sync') {
            withCredentials([
                file(credentialsId: gsCreds,
                     variable: 'GOOGLE_APPLICATION_CREDENTIALS')
            ]) {
                sh """#!/bin/bash -ex
cp "\${GOOGLE_APPLICATION_CREDENTIALS}" account.json && chmod 0600 account.json
trap 'shred -u account.json' EXIT
docker run --rm -i \
    -v "\$PWD:/workspace" \
    -w /workspace/coreos-pages \
    fedora:latest \
    /bin/bash -ex << EOF
rpmkeys --import /dev/stdin << EOG  # Import the Google RPM key.
-----BEGIN PGP PUBLIC KEY BLOCK-----
Version: GnuPG v1

mQENBFWKtqgBCADmKQWYQF9YoPxLEQZ5XA6DFVg9ZHG4HIuehsSJETMPQ+W9K5c5
Us5assCZBjG/k5i62SmWb09eHtWsbbEgexURBWJ7IxA8kM3kpTo7bx+LqySDsSC3
/8JRkiyibVV0dDNv/EzRQsGDxmk5Xl8SbQJ/C2ECSUT2ok225f079m2VJsUGHG+5
RpyHHgoMaRNedYP8ksYBPSD6sA3Xqpsh/0cF4sm8QtmsxkBmCCIjBa0B0LybDtdX
XIq5kPJsIrC2zvERIPm1ez/9FyGmZKEFnBGeFC45z5U//pHdB1z03dYKGrKdDpID
17kNbC5wl24k/IeYyTY9IutMXvuNbVSXaVtRABEBAAG0Okdvb2dsZSBDbG91ZCBQ
YWNrYWdlcyBSUE0gU2lnbmluZyBLZXkgPGdjLXRlYW1AZ29vZ2xlLmNvbT6JATgE
EwECACIFAlWKtqgCGy8GCwkIBwMCBhUIAgkKCwQWAgMBAh4BAheAAAoJEPCcOUw+
G6jV+QwH/0wRH+XovIwLGfkg6kYLEvNPvOIYNQWnrT6zZ+XcV47WkJ+i5SR+QpUI
udMSWVf4nkv+XVHruxydafRIeocaXY0E8EuIHGBSB2KR3HxG6JbgUiWlCVRNt4Qd
6udC6Ep7maKEIpO40M8UHRuKrp4iLGIhPm3ELGO6uc8rks8qOBMH4ozU+3PB9a0b
GnPBEsZdOBI1phyftLyyuEvG8PeUYD+uzSx8jp9xbMg66gQRMP9XGzcCkD+b8w1o
7v3J3juKKpgvx5Lqwvwv2ywqn/Wr5d5OBCHEw8KtU/tfxycz/oo6XUIshgEbS/+P
6yKDuYhRp6qxrYXjmAszIT25cftb4d4=
=/PbX
-----END PGP PUBLIC KEY BLOCK-----
EOG
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

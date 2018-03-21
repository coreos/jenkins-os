#!groovy

properties([
    buildDiscarder(logRotator(artifactDaysToKeepStr: '3',
                              artifactNumToKeepStr: '3',
                              daysToKeepStr: '30',
                              numToKeepStr: '50')),

    parameters([
        credentials(credentialType: 'com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey',
                    defaultValue: '',
                    description: 'Credential ID for SSH Git clone URLs',
                    name: 'BUILDS_CLONE_CREDS',
                    required: false),
        choice(name: 'COREOS_OFFICIAL',
               choices: "0\n1"),
        credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl',
                    defaultValue: 'jenkins-coreos-systems-write-5df31bf86df3.json',
                    description: '''Credentials ID for a JSON file passed as \
the GOOGLE_APPLICATION_CREDENTIALS value for uploading development files to \
the Google Storage URL, requires write permission''',
                    name: 'GS_DEVEL_CREDS',
                    required: true),
        string(name: 'GS_DEVEL_ROOT',
               defaultValue: 'gs://builds.developer.core-os.net',
               description: 'URL prefix where development files are uploaded'),
        string(name: 'MANIFEST_NAME',
               defaultValue: 'release.xml'),
        string(name: 'MANIFEST_TAG',
               defaultValue: ''),
        string(name: 'MANIFEST_URL',
               defaultValue: 'https://github.com/coreos/manifest-builds.git'),
        credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl',
                    defaultValue: 'buildbot-official.EF4B4ED9.subkey.gpg',
                    description: 'Credential ID for a GPG private key file',
                    name: 'SIGNING_CREDS',
                    required: true),
        credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl',
                    defaultValue: '',
                    description: 'Credential ID for a GPG private key file',
                    name: 'SIGNING_CREDS_PIN',
                    required: true),
        string(name: 'SIGNING_USER',
               defaultValue: 'buildbot@coreos.com',
               description: 'E-mail address to identify the GPG key'),
        text(name: 'VERIFY_KEYRING',
             defaultValue: '',
             description: '''ASCII-armored keyring containing the public keys \
used to verify signed files and Git tags'''),
        string(name: 'PIPELINE_BRANCH',
               defaultValue: 'master',
               description: 'Branch to use for fetching the pipeline jobs')
    ])
])

node('coreos && amd64 && sudo') {
    stage('Build') {
        step([$class: 'CopyArtifact',
              fingerprintArtifacts: true,
              projectName: '/mantle/master-builder',
              selector: [$class: 'TriggeredBuildSelector',
                         allowUpstreamDependencies: true,
                         fallbackToLastSuccessful: true,
                         upstreamFilterStrategy: 'UseGlobalSetting']])

        writeFile file: 'verify.asc', text: params.VERIFY_KEYRING ?: ''

        sshagent(credentials: [params.BUILDS_CLONE_CREDS], ignoreMissing: true) {
            withCredentials([
                file(credentialsId: params.GS_DEVEL_CREDS, variable: 'GOOGLE_APPLICATION_CREDENTIALS'),
                file(credentialsId: profile.SIGNING_CREDS, variable: 'GPG_SECRET_KEY_FILE'),
                file(credentialsId: profile.SIGNING_CREDS_PIN, variable: 'GPG_SECRET_KEY_PIN'),
            ]) {
                withEnv(["COREOS_OFFICIAL=${params.COREOS_OFFICIAL}",
                         "MANIFEST_NAME=${params.MANIFEST_NAME}",
                         "MANIFEST_TAG=${params.MANIFEST_TAG}",
                         "MANIFEST_URL=${params.MANIFEST_URL}",
                         "SIGNING_USER=${params.SIGNING_USER}",
                         "UPLOAD_ROOT=${params.GS_DEVEL_ROOT}"]) {
                    sh '''#!/bin/bash -ex

# The build may not be started without a tag value.
[ -n "${MANIFEST_TAG}" ]

# Catalyst leaves things chowned as root.
[ -d .cache/sdks ] && sudo chown -R "$USER" .cache/sdks

# Set up GPG for verifying tags.
export GNUPGHOME="${PWD}/.gnupg"
rm -rf "${GNUPGHOME}"
trap 'rm -rf "${GNUPGHOME}"' EXIT
mkdir --mode=0700 "${GNUPGHOME}"
gpg --import verify.asc

bin/cork update \
    --create --downgrade-replace --verify --verify-signature --verbose \
    --force-sync \
    --manifest-branch "refs/tags/${MANIFEST_TAG}" \
    --manifest-name "${MANIFEST_NAME}" \
    --manifest-url "${MANIFEST_URL}"

# Run branch-specific build commands from the scripts repository.
. src/scripts/jenkins/sdk.sh
'''
                }
            }
        }
    }

    stage('Post-build') {
        fingerprint 'src/build/catalyst/packages/coreos-sdk/**/*.tbz2,chroot/var/lib/portage/pkgs/*/*.tbz2'
    }
}

#!groovy

properties([
    buildDiscarder(logRotator(daysToKeepStr: '30', numToKeepStr: '50')),

    parameters([
        string(name: 'AWS_REGION',
               defaultValue: 'us-west-2',
               description: 'AWS region to use for AMIs and testing'),
        [$class: 'CredentialsParameterDefinition',
         credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl',
         defaultValue: '1bb768fc-940d-4a95-95d0-27c1153e7fa0',
         description: 'AWS credentials list for AMI creation and releasing',
         name: 'AWS_RELEASE_CREDS',
         required: true],
        [$class: 'CredentialsParameterDefinition',
         credentialType: 'com.cloudbees.jenkins.plugins.awscredentials.AWSCredentialsImpl',
         defaultValue: '6d37d17c-503e-4596-9a9b-1ab4373955a9',
         description: 'Credentials with permissions required by "kola run --platform=aws"',
         name: 'AWS_TEST_CREDS',
         required: true],
        [$class: 'CredentialsParameterDefinition',
         credentialType: 'com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey',
         defaultValue: '',
         description: 'Credential ID for SSH Git clone URLs',
         name: 'BUILDS_CLONE_CREDS',
         required: false],
        [$class: 'CredentialsParameterDefinition',
         credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl',
         defaultValue: 'jenkins-coreos-systems-write-5df31bf86df3.json',
         description: '''Credentials given here must have permission to \
download release storage files''',
         name: 'DOWNLOAD_CREDS',
         required: true],
        string(name: 'DOWNLOAD_ROOT',
               defaultValue: 'gs://builds.developer.core-os.net',
               description: 'URL prefix where image files are downloaded'),
        string(name: 'GROUP',
               defaultValue: 'developer',
               description: 'Which release group owns this build'),
        string(name: 'MANIFEST_NAME',
               defaultValue: 'release.xml'),
        string(name: 'MANIFEST_TAG',
               defaultValue: ''),
        string(name: 'MANIFEST_URL',
               defaultValue: 'https://github.com/coreos/manifest-builds.git'),
        text(name: 'VERIFY_KEYRING',
             defaultValue: '',
             description: '''ASCII-armored keyring containing the public keys \
used to verify signed files and Git tags'''),
        string(name: 'PIPELINE_BRANCH',
               defaultValue: 'master',
               description: 'Branch to use for fetching the pipeline jobs')
    ])
])

def amiprops = [:]

node('coreos && amd64 && sudo') {
    stage('Build') {
        step([$class: 'CopyArtifact',
              fingerprintArtifacts: true,
              projectName: '/mantle/master-builder',
              selector: [$class: 'StatusBuildSelector', stable: false]])

        writeFile file: 'verify.asc', text: params.VERIFY_KEYRING ?: ''

        sshagent(credentials: [params.BUILDS_CLONE_CREDS],
                 ignoreMissing: true) {
            withCredentials([
                [$class: 'FileBinding',
                 credentialsId: params.AWS_RELEASE_CREDS,
                 variable: 'AWS_CREDENTIALS'],
                [$class: 'FileBinding',
                 credentialsId: params.DOWNLOAD_CREDS,
                 variable: 'GOOGLE_APPLICATION_CREDENTIALS']
            ]) {
                withEnv(["AWS_REGION=${params.AWS_REGION}",
                         "BOARD=amd64-usr",
                         "CHANNEL=${params.GROUP}",
                         "DOWNLOAD_ROOT=${params.DOWNLOAD_ROOT}",
                         "MANIFEST_NAME=${params.MANIFEST_NAME}",
                         "MANIFEST_TAG=${params.MANIFEST_TAG}",
                         "MANIFEST_URL=${params.MANIFEST_URL}"]) {
                    sh '''#!/bin/bash -ex

rm -f coreos_production_ami_hvm.txt coreos_production_ami_pv.txt

enter() {
  bin/cork enter --experimental -- "$@"
}

# set up GPG for verifying tags
export GNUPGHOME="${PWD}/.gnupg"
rm -rf "${GNUPGHOME}"
trap 'rm -rf "${GNUPGHOME}"' EXIT
mkdir --mode=0700 "${GNUPGHOME}"
gpg --import verify.asc

bin/cork update --create --downgrade-replace --verify --verify-signature --verbose \
                --manifest-url "${MANIFEST_URL}" \
                --manifest-branch "refs/tags/${MANIFEST_TAG}" \
                --manifest-name "${MANIFEST_NAME}"
source .repo/manifests/version.txt

[ -s verify.asc ] && verify_key=--verify-key=verify.asc || verify_key=

bin/plume pre-release \
    --debug \
    --platform=aws \
    --aws-credentials="${AWS_CREDENTIALS}" \
    --gce-json-key="${GOOGLE_APPLICATION_CREDENTIALS}" \
    --board="${BOARD}" \
    --channel="${CHANNEL}" \
    --version="${COREOS_VERSION}" \
    $verify_key

# XXX: AMI ID lists are not signed.
enter gsutil cp "${DOWNLOAD_ROOT}/boards/${BOARD}/${COREOS_VERSION}"/coreos_production_ami_{hvm,pv}.txt /mnt/host/source/

hvm_ami_id=$(sed -n "s/\\(.*|\\|^\\)${AWS_REGION}=\\([^|]*\\).*/\\2/p" coreos_production_ami_hvm.txt)
pv_ami_id=$(sed -n "s/\\(.*|\\|^\\)${AWS_REGION}=\\([^|]*\\).*/\\2/p" coreos_production_ami_pv.txt)

tee ami.properties << EOF
HVM_AMI_ID = ${hvm_ami_id:?}
PV_AMI_ID = ${pv_ami_id:?}
EOF
'''  /* Editor quote safety: ' */
                }
            }
        }
    }

    stage('Post-build') {
        archiveArtifacts 'ami.properties,coreos_production_ami_hvm.txt,coreos_production_ami_pv.txt'

        for (line in readFile('ami.properties').trim().split("\n")) {
            def tokens = line.tokenize(" =")
            amiprops[tokens[0]] = tokens[1]
        }
    }

}

stage('Downstream') {
    parallel failFast: false,
        'kola-aws-hvm': {
            build job: '../kola/aws', propagate: false, parameters: [
                string(name: 'AWS_AMI_ID', value: amiprops.HVM_AMI_ID),
                string(name: 'AWS_AMI_TYPE', value: 'HVM'),
                string(name: 'AWS_REGION', value: params.AWS_REGION),
                [$class: 'CredentialsParameterValue', name: 'AWS_TEST_CREDS', value: params.AWS_TEST_CREDS],
                string(name: 'PIPELINE_BRANCH', value: params.PIPELINE_BRANCH)
            ]
        },
        'kola-aws-pv': {
            build job: '../kola/aws', propagate: false, parameters: [
                string(name: 'AWS_AMI_ID', value: amiprops.PV_AMI_ID),
                string(name: 'AWS_AMI_TYPE', value: 'PV'),
                string(name: 'AWS_REGION', value: params.AWS_REGION),
                [$class: 'CredentialsParameterValue', name: 'AWS_TEST_CREDS', value: params.AWS_TEST_CREDS],
                string(name: 'PIPELINE_BRANCH', value: params.PIPELINE_BRANCH)
            ]
        }
}

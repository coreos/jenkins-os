#!groovy

properties([
    buildDiscarder(logRotator(daysToKeepStr: '30', numToKeepStr: '50')),

    parameters([
        string(name: 'AWS_BUCKET',
               defaultValue: "s3://coreos-dev-ami-import-us-west-2",
               description: 'AWS bucket to upload images to during AMI-creation'),
        string(name: 'AWS_REGION',
               defaultValue: 'us-west-2',
               description: 'AWS region to use for AMIs and testing'),
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

node('amd64') {
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
                 credentialsId: params.DOWNLOAD_CREDS,
                 variable: 'GOOGLE_APPLICATION_CREDENTIALS'],
                [$class: 'AmazonWebServicesCredentialsBinding',
                 credentialsId: params.AWS_TEST_CREDS,
                 accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                 secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']
            ]) {
                withEnv(["AWS_REGION=${params.AWS_REGION}",
                         "BOARD=amd64-usr",
                         "DOWNLOAD_ROOT=${params.DOWNLOAD_ROOT}",
                         "MANIFEST_TAG=${params.MANIFEST_TAG}",
                         "MANIFEST_URL=${params.MANIFEST_URL}"]) {
                    rc = sh returnStatus: true, script: '''#!/bin/bash -ex
set -o pipefail

rm -rf tmp manifests

# set up GPG for verifying tags
export GNUPGHOME="${PWD}/.gnupg"
rm -rf "${GNUPGHOME}"
trap 'rm -rf "${GNUPGHOME}" "ore_amis"' EXIT
mkdir --mode=0700 "${GNUPGHOME}"
gpg --import verify.asc

git clone --depth=1 --branch="${MANIFEST_TAG}" "${MANIFEST_URL}" manifests
git -C manifests tag -v "${MANIFEST_TAG}"
source manifests/version.txt

[ -s verify.asc ] && verify_key=--verify-key=verify.asc || verify_key=

mkdir -p tmp
./bin/cork download-image \
    --cache-dir=tmp \
    --json-key="${GOOGLE_APPLICATION_CREDENTIALS}" \
    --root="${DOWNLOAD_ROOT}/boards/${BOARD}/${COREOS_VERSION}" \
    --verify=true $verify_key \
    --platform=aws

bunzip2 -k -f ./tmp/coreos_production_ami_vmdk_image.vmdk.bz2

NAME="jenkins-${JOB_NAME##*/}-${BUILD_NUMBER}"
bin/ore aws upload \
    --bucket="${AWS_BUCKET}" \
    --region=${AWS_REGION} \
    --file=./tmp/coreos_production_ami_vmdk_image.vmdk \
    --board="${BOARD}" \
    --create-pv=true \
    --name="${NAME}" \
    | tee /dev/stderr | tail -n 1 > ore_amis.json

hvm_ami_id=$(jq -r '.HVM' ore_amis.json)
pv_ami_id=$(jq -r '.PV' ore_amis.json)

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
        archiveArtifacts 'ami.properties'

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

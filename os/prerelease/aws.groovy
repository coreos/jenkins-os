#!groovy

properties([
    buildDiscarder(logRotator(daysToKeepStr: '30', numToKeepStr: '50')),

    parameters([
        string(name: 'AWS_REGION',
               defaultValue: 'us-west-2',
               description: 'AWS region to use for AMIs and testing'),
        credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl',
                    defaultValue: '1bb768fc-940d-4a95-95d0-27c1153e7fa0',
                    description: 'AWS credentials list for AMI creation and releasing',
                    name: 'AWS_RELEASE_CREDS',
                    required: true),
        credentials(credentialType: 'com.cloudbees.jenkins.plugins.awscredentials.AWSCredentialsImpl',
                    defaultValue: '6d37d17c-503e-4596-9a9b-1ab4373955a9',
                    description: 'Credentials with permissions required by "kola run --platform=aws"',
                    name: 'AWS_TEST_CREDS',
                    required: true),
        credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl',
                    defaultValue: 'jenkins-coreos-systems-write-5df31bf86df3.json',
                    description: 'Credentials to download release files',
                    name: 'DOWNLOAD_CREDS',
                    required: true),
        string(name: 'GROUP',
               defaultValue: 'developer',
               description: 'Which release group owns this build'),
        text(name: 'VERIFY_KEYRING',
             defaultValue: '',
             description: '''ASCII-armored keyring containing the public keys \
used to verify signed files and Git tags'''),
        string(name: 'VERSION',
               defaultValue: '',
               description: 'OS image version to use'),
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

        withCredentials([
            file(credentialsId: params.AWS_RELEASE_CREDS, variable: 'AWS_CREDENTIALS'),
            file(credentialsId: params.DOWNLOAD_CREDS, variable: 'GOOGLE_APPLICATION_CREDENTIALS'),
        ]) {
            withEnv(["AWS_REGION=${params.AWS_REGION}",
                     "BOARD=amd64-usr",
                     "CHANNEL=${params.GROUP}",
                     "COREOS_VERSION=${params.VERSION}"]) {
                sh '''#!/bin/bash -ex

rm -f ami.properties images.json

[ -s verify.asc ] && verify_key=--verify-key=verify.asc || verify_key=

bin/plume pre-release \
    --debug \
    --platform=aws \
    --aws-credentials="${AWS_CREDENTIALS}" \
    --gce-json-key="${GOOGLE_APPLICATION_CREDENTIALS}" \
    --board="${BOARD}" \
    --channel="${CHANNEL}" \
    --version="${COREOS_VERSION}" \
    --write-image-list=images.json \
    $verify_key

hvm_ami_id=$(jq -r '.aws.amis[]|select(.name == "'"${AWS_REGION}"'").hvm' images.json)
pv_ami_id=$(jq -r '.aws.amis[]|select(.name == "'"${AWS_REGION}"'").pv' images.json)

tee ami.properties << EOF
HVM_AMI_ID = ${hvm_ami_id:?}
PV_AMI_ID = ${pv_ami_id:?}
EOF
'''  /* Editor quote safety: ' */
            }
        }
    }

    stage('Post-build') {
        archiveArtifacts 'ami.properties,images.json'

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
                credentials(name: 'AWS_TEST_CREDS', value: params.AWS_TEST_CREDS),
                string(name: 'PIPELINE_BRANCH', value: params.PIPELINE_BRANCH)
            ]
        },
        'kola-aws-pv': {
            build job: '../kola/aws', propagate: false, parameters: [
                string(name: 'AWS_AMI_ID', value: amiprops.PV_AMI_ID),
                string(name: 'AWS_AMI_TYPE', value: 'PV'),
                string(name: 'AWS_REGION', value: params.AWS_REGION),
                credentials(name: 'AWS_TEST_CREDS', value: params.AWS_TEST_CREDS),
                string(name: 'PIPELINE_BRANCH', value: params.PIPELINE_BRANCH)
            ]
        }
}

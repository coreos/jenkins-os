#!groovy

properties([
    buildDiscarder(logRotator(daysToKeepStr: '30', numToKeepStr: '50')),

    pipelineTriggers([cron('H 20 * * *')]),

    parameters([
        credentials(credentialType: 'com.cloudbees.jenkins.plugins.awscredentials.AWSCredentialsImpl',
                    defaultValue: '6d37d17c-503e-4596-9a9b-1ab4373955a9',
                    description: 'Credentials with permissions required by "ore aws gc"',
                    name: 'AWS_TEST_CREDS',
                    required: true),
        credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl',
                    defaultValue: '787e229d-0941-4f04-aba5-9e5f22fe1c71',
                    description: 'Credentials with permissions required by "ore do gc"',
                    name: 'DIGITALOCEAN_CREDS',
                    required: true),
        credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl',
                    defaultValue: 'jenkins-coreos-systems-write-5df31bf86df3.json',
                    description: '''Credentials ID for a JSON file passed as \
the GOOGLE_APPLICATION_CREDENTIALS value for authing to gcloud & creating a bucket''',
                    name: 'GS_CREDS',
                    required: true),
        credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl',
                    defaultValue: '17942f6a-969b-466d-ac46-cd15925c8953',
                    description: 'Config required by "ore oci gc"',
                    name: 'OCI_TEST_CONFIG',
                    required: true),
        credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl',
                    defaultValue: 'f7121197-c5b2-4e67-8529-ff59224bef91',
                    description: 'RSA Key in PEM format referenced by config',
                    name: 'OCI_TEST_KEY',
                    required: true),
        credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl',
                    defaultValue: 'd67b5bde-d138-487a-9da3-0f5f5f157310',
                    description: 'Credentials with permissions required by "ore packet gc"',
                    name: 'PACKET_CREDS',
                    required: true),
        string(name: 'PACKET_PROJECT',
               defaultValue: '9da29e12-d97c-4d6e-b5aa-72174390d57a',
               description: 'The Packet project ID to run test machines'),
        string(name: 'UPLOAD_ROOT',
               defaultValue: 'gs://builds.developer.core-os.net',
               description: 'URL prefix where development files are uploaded'),
    ])
])

node('amd64') {
    stage('GC') {
        step([$class: 'CopyArtifact',
              fingerprintArtifacts: true,
              projectName: '/mantle/master-builder',
              selector: [$class: 'StatusBuildSelector', stable: false]])

            withCredentials([
                [$class: 'AmazonWebServicesCredentialsBinding',
                credentialsId: params.AWS_TEST_CREDS,
                accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'],
                file(credentialsId: params.DIGITALOCEAN_CREDS, variable: 'DIGITALOCEAN_CREDS'),
                file(credentialsId: params.GS_CREDS, variable: 'GOOGLE_APPLICATION_CREDENTIALS'),
                file(credentialsId: params.OCI_TEST_CONFIG, variable: 'OCI_TEST_CONFIG'),
                file(credentialsId: params.OCI_TEST_KEY, variable: 'OCI_TEST_KEY'),
                string(credentialsId: params.PACKET_CREDS, variable: 'PACKET_API_KEY')
            ]) {
                withEnv([
                    "UPLOAD_ROOT=${params.UPLOAD_ROOT}",
                ]) {
                sh '''#!/bin/bash -ex

# the oci config file refers to the location of the oci_api_key.pem
# so we have to move it to the correct location rather than passing
# in the variables
rm -rf ~/.oci
mkdir  --mode=0700 ~/.oci
mv ${OCI_TEST_CONFIG} ~/.oci/config
mv ${OCI_TEST_KEY} ~/.oci/oci_api_key.pem
trap 'rm -rf ~/.oci/' EXIT

timeout --signal=SIGQUIT 60m bin/ore aws gc
timeout --signal=SIGQUIT 60m bin/ore do gc \
    --config-file="${DIGITALOCEAN_CREDS}"
timeout --signal=SIGQUIT 60m bin/ore gcloud gc \
    --json-key "${GOOGLE_APPLICATION_CREDENTIALS}"
timeout --signal=SIGQUIT 60m bin/ore oci gc
timeout --signal=SIGQUIT 60m bin/ore packet gc \
    --api-key="${PACKET_API_KEY}" \
    --project="${PACKET_PROJECT}" \
    --gs-json-key="${GOOGLE_APPLICATION_CREDENTIALS}" \
    --storage-url="${UPLOAD_ROOT}/mantle/packet"
'''
            }
        }
    }
}

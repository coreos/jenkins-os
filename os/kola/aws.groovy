#!groovy

properties([
    buildDiscarder(logRotator(daysToKeepStr: '30', numToKeepStr: '50')),

    parameters([
        [$class: 'CredentialsParameterDefinition',
         credentialType: 'com.cloudbees.jenkins.plugins.awscredentials.AWSCredentialsImpl',
         defaultValue: '6d37d17c-503e-4596-9a9b-1ab4373955a9',
         description: '''Credentials given here must have all permissions required by ore upload and kola run --platform=aws''',
         required: true,
         name: 'AWS_DEV_CREDS'],
        string(name: 'AWS_AMI_ID',
               description: 'AWS AMI to test'),
        string(name: 'AWS_AMI_TYPE',
               choices: "HVM\nPV",
               description: 'The type of the AMI, PV or HVM'),
        string(name: 'AWS_REGION',
               defaultValue: 'us-west-2',
               description: 'AWS region to build the test AMI for'),
        string(name: 'PIPELINE_BRANCH',
               defaultValue: 'master',
               description: 'Branch to use for fetching the pipeline jobs'),
    ])
])

/* The kola step doesn't fail the job, so save the return code separately.  */
def rc = 0

node('amd64') {
    stage('Build') {
        step([$class: 'CopyArtifact',
              fingerprintArtifacts: true,
              projectName: '/mantle/master-builder',
              selector: [$class: 'StatusBuildSelector', stable: false]])

        withCredentials([
            [$class: 'AmazonWebServicesCredentialsBinding',
                credentialsId: params.AWS_DEV_CREDS,
                accessKeyVariable: 'AWS_ACCESS_KEY_ID', 
                secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']
        ]) {
            withEnv(["AWS_REGION=${params.AWS_REGION}",
                     "AWS_AMI_ID=${params.AWS_AMI_ID}",
                     "AWS_AMI_TYPE=${params.AWS_AMI_TYPE}"]) {
                rc = sh returnStatus: true, script: '''#!/bin/bash -ex

sudo rm -rf *.tap _kola_temp*

NAME="jenkins-${JOB_NAME##*/}-${BUILD_NUMBER}"

instance_type="t2.small"
if [[ "${AWS_AMI_TYPE}" == "PV" ]]; then
    instance_type="m1.small"
fi

timeout --signal=SIGQUIT 30m bin/kola run \
    --parallel=4 \
    --basename="${NAME}" \
    --aws-ami="${AWS_AMI_ID}" \
    --aws-region="${AWS_REGION}" \
    --aws-type="${instance_type}" \
    --platform=aws \
    --tapfile="${JOB_NAME##*/}.tap"
'''  /* Editor quote safety: ' */
            }
        }
    }

    stage('Post-build') {
        step([$class: 'TapPublisher',
              discardOldReports: false,
              enableSubtests: true,
              failIfNoResults: true,
              failedTestsMarkBuildAsFailure: true,
              flattenTapResult: false,
              includeCommentDiagnostics: true,
              outputTapToConsole: true,
              planRequired: true,
              showOnlyFailures: false,
              skipIfBuildNotOk: false,
              stripSingleParents: false,
              testResults: '*.tap',
              todoIsFailure: false,
              validateNumberOfTests: true,
              verbose: true])

        sh 'tar -cJf _kola_temp.tar.xz _kola_temp'
        archiveArtifacts '_kola_temp.tar.xz'
    }
}

/* Propagate the job status after publishing TAP results.  */
currentBuild.result = rc == 0 ? 'SUCCESS' : 'FAILURE'

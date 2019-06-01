#!groovy

properties([
    buildDiscarder(logRotator(daysToKeepStr: '30', numToKeepStr: '50')),

    parameters([
        string(name: 'AWS_AMI_ID',
               description: 'AWS AMI to test'),
        string(name: 'AWS_AMI_TYPE',
               choices: "HVM\nPV",
               description: 'The type of the AMI, PV or HVM'),
        string(name: 'AWS_REGION',
               defaultValue: 'us-west-2',
               description: 'AWS region to build the test AMI for'),
        credentials(credentialType: 'com.cloudbees.jenkins.plugins.awscredentials.AWSCredentialsImpl',
                    defaultValue: '6d37d17c-503e-4596-9a9b-1ab4373955a9',
                    description: 'Credentials with permissions required by "kola run --platform=aws"',
                    name: 'AWS_TEST_CREDS',
                    required: true),
        text(name: 'TORCX_MANIFEST',
             defaultValue: '',
             description: 'Contents of the torcx manifest for kola tests'),
        string(name: 'PIPELINE_BRANCH',
               defaultValue: 'master',
               description: 'Branch to use for fetching the pipeline jobs')
    ])
])

/* The kola step doesn't fail the job, so save the return code separately.  */
def rc = 0

node('amd64') {
    stage('Build') {
        copyArtifacts fingerprintArtifacts: true,
                      projectName: '/mantle/master-builder',
                      selector: lastSuccessful()

        writeFile file: 'torcx_manifest.json', text: params.TORCX_MANIFEST ?: ''

        withCredentials([
            [$class: 'AmazonWebServicesCredentialsBinding',
             credentialsId: params.AWS_TEST_CREDS,
             accessKeyVariable: 'AWS_ACCESS_KEY_ID',
             secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']
        ]) {
            withEnv(["AWS_AMI_ID=${params.AWS_AMI_ID}",
                     "AWS_AMI_TYPE=${params.AWS_AMI_TYPE}",
                     "AWS_REGION=${params.AWS_REGION}"]) {
                rc = sh returnStatus: true, script: '''#!/bin/bash -ex

rm -rf *.tap _kola_temp*

NAME="jenkins-${JOB_NAME##*/}-${BUILD_NUMBER}"

instance_type="m4.large"
if [[ "${AWS_AMI_TYPE}" == "PV" ]]; then
    instance_type="m3.medium"
fi

timeout --signal=SIGQUIT 3h bin/kola run \
    --parallel=4 \
    --basename="${NAME}" \
    --aws-ami="${AWS_AMI_ID}" \
    --aws-region="${AWS_REGION}" \
    --aws-type="${instance_type}" \
    --platform=aws \
    --tapfile="${JOB_NAME##*/}.tap" \
    --torcx-manifest=torcx_manifest.json
'''  /* Editor quote safety: ' */

                message = sh returnStdout: true, script: '''jq '.tests[] | select(.result == "FAIL") | .name' -r < _kola_temp/aws-latest/reports/report.json | sed -e :a -e '$!N; s/\\n/, /; ta' '''
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

if (currentBuild.result == 'FAILURE')
    slackSend color: 'danger',
              message: "```Kola: AWS-amd64-$AWS_AMI_TYPE Failure: <${BUILD_URL}console|Console> - <${BUILD_URL}artifact/_kola_temp.tar.xz|_kola_temp>\n$message```"

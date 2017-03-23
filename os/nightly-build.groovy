#!groovy

/* This schedules with the server's time, which is currently in UTC.  */
properties([pipelineTriggers([cron('H 9 * * *')])])

/* Build the manifest job with its default parameters (to build master).  */
stage('Downstream') {
    def run = build job: 'manifest', propagate: false

    if (run.result == 'SUCCESS')
        slackSend color: 'good',
                  message: ":partyparrot: The nightly OS build succeeded!\n${BUILD_URL}"
    else
        slackSend color: 'danger',
                  message: "${buildTree run}"

    currentBuild.result = run.result
}

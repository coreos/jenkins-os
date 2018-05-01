#!groovy

/* This schedules with the server's time, which is currently in UTC.  */
properties([pipelineTriggers([cron('H 8 * * *')])])

/* Build the manifest job with its default parameters (to build master).  */
stage('Downstream') {
    def run = build job: 'manifest', propagate: false

    if (run.result == 'SUCCESS')
        trySlackSend color: 'good',
                  message: ":partyparrot: The nightly OS build succeeded!\n${BUILD_URL}cldsv"
    else
        trySlackSend color: 'danger',
                  message: ":trashfire: The nightly OS build failed!\n${BUILD_URL}cldsv"

    currentBuild.result = run.result
}

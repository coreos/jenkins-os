#!groovy

/* This schedules with the server's time, which is currently in UTC.  */
properties([pipelineTriggers([cron('H 9 * * *')])])

/* Build the manifest job with its default parameters (to build master).  */
stage('Downstream') {
    try {
        build job: 'manifest'
    } catch (exc) {
        slackSend color: 'danger',
                  message: ":trashfire: The nightly OS build failed!\n${BUILD_URL}"

        /* Fail this build after notifying.  */
        currentBuild.result = 'FAILURE'
        throw exc
    }

    /* Notify that everything worked if this point is reached.  */
    slackSend color: 'good',
              message: ":partyparrot: The nightly OS build succeeded!\n${BUILD_URL}"
}

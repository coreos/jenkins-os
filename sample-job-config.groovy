#!groovy

/*
 * Default Jenkins job config file.
 */

/*
 * CL_MANIFEST_URL - Git repository of the Container Linux manifest.
 */
def CL_MANIFEST_URL() {'https://github.com/coreos/manifest.git'}

/*
 * DEV_BUILDS_ROOT - Google storage bucket for Jenkins developer artifacts.
 */
def DEV_BUILDS_ROOT() {'builds.developer.core-os.net'}

/*
 * DOWNLOAD_ROOT - Download URL of official Container Linux releases.
 */
def DOWNLOAD_ROOT() {'release.core-os.net'}

/*
 *  GIT_AUTHOR_EMAIL - Jenkins commit author.
 */
def GIT_AUTHOR_EMAIL() {'jenkins@jenkins.coreos.systems'}

/*
 *  GIT_AUTHOR_NAME - Jenkins commit author.
 */
def GIT_AUTHOR_NAME() {'CoreOS Jenkins'}

/*
 * GPG_USER_ID - User ID for the Jenkins GPG_SECRET_KEY_FILE credential.
 */
def GPG_USER_ID() {'buildbot@coreos.com'}

/*
 * MANIFEST_BUILDS_URL - Git repository for Jenkins manifest-builds.
 */
def MANIFEST_BUILDS_URL() {'https://github.com/coreos/manifest-builds.git'}

/*
 * REL_BUILDS_ROOT - Google storage bucket for Jenkins release artifacts.
 */
def REL_BUILDS_ROOT() {'builds.release.core-os.net'}

return this

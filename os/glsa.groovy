#!groovy

/* This schedules with the server's time, which is currently in UTC.  */
properties([pipelineTriggers([cron('H 14 * * *')])])

node('coreos') {
    stage('SCM') {
        git url: 'https://github.com/coreos/scripts.git'
        dir('coreos-overlay') {
            git url: 'https://github.com/coreos/coreos-overlay.git'
        }
        dir('portage-stable') {
            git url: 'https://github.com/coreos/portage-stable.git'
        }
    }

    stage('Check') {
        sh '''#!/bin/bash -ex

function glsa_affects_us() {
        [ '' $(sed -n '
/<package / {
 s,.* name="\\([^"]*\\)".*, -o -e portage-stable/\\1 -o -e coreos-overlay/\\1,p
}
' "$1") ]
}

# Trick the SDK into allowing us to run an isolated script.
echo COREOS_BUILD_ID= > version.txt
echo COREOS_SDK_VERSION= >> version.txt
echo COREOS_VERSION_ID= >> version.txt
mkdir -p src
ln -fns .. src/scripts
GCLIENT_ROOT=$PWD ; export GCLIENT_ROOT

# Edit our master branch GLSAs via rsync from upstream.
./update_ebuilds --nocommit --portage_stable portage-stable metadata/glsa

# Read the type of modifications that were made.
declare -a added modified other
while read change file
do
        [[ $file == metadata/glsa/glsa-*.xml ]] || continue
        glsa_affects_us "portage-stable/$file" || continue
        url="https://security.gentoo.org/glsa/${file:19:-4}"
        case "$change" in
            A) added+=("$url") ;;
            M) modified+=("$url") ;;
            *) other+=("($change) $file") ;;
        esac
done < <(git -C portage-stable status --short)

# Create some files to hold the notification data.
: > notify.txt
: > status.txt

# Write notification information for new GLSAs as the most important.
[ -n "${added[*]}" ] &&
    echo -e 'There are new GLSAs!\n' >> notify.txt &&
    [ ! -s status.txt ] && echo -n danger > status.txt
for file in "${added[@]}"
do
        echo "  * $file" >> notify.txt
done

exit 0  # Do not bother us unless there are new GLSAs.

# Write notification information for warning about updates to GLSAs.
[ -n "${modified[*]}" -a -s notify.txt ] && echo >> notify.txt
[ -n "${modified[*]}" ] &&
    echo -e 'GLSAs have been updated!\n' >> notify.txt &&
    [ ! -s status.txt ] && echo -n warning > status.txt
for file in "${modified[@]}"
do
        echo "  * $file" >> notify.txt
done

exit 0  # Do not bother us about renames etc.

# Write notification information for warning about other changes.
[ -n "${other[*]}" -a -s notify.txt ] && echo >> notify.txt
[ -n "${other[*]}" ] &&
    echo -e 'There are other GLSA changes!\n' >> notify.txt &&
    [ ! -s status.txt ] && echo -n warning > status.txt
for file in "${other[@]}"
do
        echo "  * $file" >> notify.txt
done

exit 0  # Do not bother us when there is no problem.

# Write notification information for when everything is current.
[ ! -s notify.txt ] &&
    echo 'GLSAs are up to date!' >> notify.txt &&
    [ ! -s status.txt ] && echo -n good > status.txt

exit 0  # Do not return a non-zero $? from above.
'''  /* Editor quote safety: ' */
    }

    stage('Notify') {
        def color = readFile 'status.txt'
        def message = readFile 'notify.txt'
        if (message)
            slackSend color: color ?: '#C0C0C0', message: """${message}
Run `update_ebuilds --commit metadata/glsa` in the SDK to update GLSAs."""
    }
}

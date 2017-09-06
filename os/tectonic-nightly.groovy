#!/usr/bin/env groovy

def creds = [
  file(credentialsId: 'tectonic-license', variable: 'TF_VAR_tectonic_license_path'),
  file(credentialsId: 'tectonic-pull', variable: 'TF_VAR_tectonic_pull_secret_path'),
  [
    $class: 'UsernamePasswordMultiBinding',
    credentialsId: 'tectonic-console-login',
    passwordVariable: 'TF_VAR_tectonic_admin_password_hash',
    usernameVariable: 'TF_VAR_tectonic_admin_email'
  ],
  [
    $class: 'AmazonWebServicesCredentialsBinding',
    credentialsId: 'c7e3cb5d-0c69-46c8-b184-48d68b1ce680'
  ]
]

def default_builder_image = 'quay.io/coreos/tectonic-builder:v1.36'
def tectonic_smoke_test_env_image = 'quay.io/coreos/tectonic-smoke-test-env:v3.0'


pipeline {
  agent none
  options {
    timeout(time:70, unit:'MINUTES')
    timestamps()
    buildDiscarder(logRotator(numToKeepStr:'100'))
  }
  parameters {
    string(
      name: 'builder_image',
      defaultValue: default_builder_image,
      description: 'tectonic-builder docker image to use for builds'
    )
  }

  stages {
    stage('Build & Test') {
      environment {
        GO_PROJECT = '/go/src/github.com/coreos/tectonic-installer'
        MAKEFLAGS = '-j4'
      }
      steps {
        node('amd64 && docker') {
          withDockerContainer(params.builder_image) {
            sh '''#!/bin/bash -ex
            rm -rf tectonic-installer
            git clone https://github.com/coreos/tectonic-installer

            mkdir -p "${GO_PROJECT%/*}"
            ln -fns "$(pwd)/tectonic-installer" "${GO_PROJECT}"

            cd ${GO_PROJECT}/
            make bin/smoke

            cd ${GO_PROJECT}/installer
            make clean
            make tools
            make build

            make dirtycheck
            make lint
            make test
            rm -fr frontend/tests_output
            '''
            stash name: 'installer', includes: 'tectonic-installer/installer/bin/linux/installer'
            stash name: 'smoke', includes: 'tectonic-installer/bin/smoke'
          }
        }
      }
    }

    stage("Tests") {
      environment {
        TECTONIC_INSTALLER_ROLE = 'tectonic-installer'
      }
      steps {
        node('amd64 && docker') {
          withCredentials(creds) {
            unstash 'installer'
            unstash 'smoke'
            withDockerContainer(tectonic_smoke_test_env_image) {
              sh '''#!/bin/bash -ex
              function cleanup {
                # Delete aws key-pair
                if [ -n "$TF_VAR_tectonic_aws_ssh_key" ]; then
                  ssh-add -d $sshdir/$TF_VAR_tectonic_aws_ssh_key
                  aws ec2 delete-key-pair --key-name $TF_VAR_tectonic_aws_ssh_key --region us-west-2
                fi
                rm $sshdir/$TF_VAR_tectonic_aws_ssh_key
                rm $sshdir/$TF_VAR_tectonic_aws_ssh_key.pub
              }
              trap cleanup EXIT

              # Create aws key-pair
              sshdir=$(pwd)
              export TF_VAR_tectonic_aws_ssh_key="tectonic-nightly-$(mktemp -t 'XXXXXXXXXX' -u -p . | tr -d ./)"
              ssh-keygen -t rsa -b 4096 -f $sshdir/$TF_VAR_tectonic_aws_ssh_key -N "" -q
              kp=$(<$sshdir/$TF_VAR_tectonic_aws_ssh_key.pub)
              eval `ssh-agent -s`
              ssh-add $sshdir/$TF_VAR_tectonic_aws_ssh_key
              aws ec2 import-key-pair --key-name=$TF_VAR_tectonic_aws_ssh_key --public-key-material "$kp" --region us-west-2

              # Update the AMI
              COREOS_VERSION=$(curl -Ls "https://storage.googleapis.com/builds.developer.core-os.net/boards/amd64-usr/current-master/version.txt" | sed -n 's/^COREOS_VERSION=//p')
              AMI=$(curl -s "https://storage.googleapis.com/builds.developer.core-os.net/boards/amd64-usr/${COREOS_VERSION}/coreos_production_ami_all.json" | jq -r '.amis[] | select(.name == "us-west-2") | .hvm')
              sed -i "s/.{data.aws_ami.coreos_ami.image_id}/${AMI}/g" tectonic-installer/modules/aws/master-asg/master.tf
              sed -i "s/.{data.aws_ami.coreos_ami.image_id}/${AMI}/g" tectonic-installer/modules/aws/worker-asg/worker.tf
              sed -i "s/.{data.aws_ami.coreos_ami.image_id}/${AMI}/g" tectonic-installer/modules/aws/etcd/nodes.tf

              # Update the base domain in vars
              find tectonic-installer/tests/smoke/aws/vars/ -type f -exec sed -i "s|tectonic.dev.coreos.systems|clnightly.dev.coreos.systems|g" {} +;
              find tectonic-installer/tests/smoke/aws/vars/ -type f -exec sed -i "s|eu-west-1|us-west-2|g" {} +;

              sed -i "s|eu-west-1|us-west-2|g" tectonic-installer/examples/terraform.tfvars.aws

              # Update the regions & base domain in smoke.sh
              sed -i "s|REGIONS=.*|REGIONS=(us-west-2)|g" tectonic-installer/tests/smoke/aws/smoke.sh
              sed -i "s|TF_VAR_base_domain=.*|TF_VAR_base_domain=clnightly.dev.coreos.systems|g" tectonic-installer/tests/smoke/aws/smoke.sh

              cd tectonic-installer/tests/rspec
              bundler exec rubocop --cache false tests/rspec
              bundler exec rspec
              '''
            }
          }
        }
      }
    }
  }
}

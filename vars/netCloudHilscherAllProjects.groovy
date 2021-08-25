
def getTag(repo, project, version, arch) {
  print '###  tag repo : ' + repo + ' # project : ' + project + ' # version : ' + version + ' # arch : ' + arch
  return repo + '/' + project + ':' + version + '-' + arch
}

def dockerLogin(registry, username, password) {
  sh "docker login -u $username -p $password $registry"
}

def dockerBuild(context, arch, tags) {
  dockerfile = context + 'Dockerfile.' + arch
  sh 'docker buildx build --progress tty --rm -f ' + dockerfile + ' ' +  tags + ' --load ' + buildParams.docker_context_path
}

def dockerPush(tag) {
  // sh 'docker push ' + tag
  print "### docker push is commented out ."
}

def multiArchTag(registry, projectName, versionstring, archs, tag = null) {
  // More info on docker manifest:
  // https://docs.docker.com/engine/reference/commandline/manifest/
  manifestTag = ""
  if (tag == null) {
    manifestTag = "$registry/$projectName:$versionstring"
  } else {
    manifestTag = "$registry/$projectName:$tag"
  }

  // Create the list of tags that will be bundled in this manifest
  imageTags = ""
  archs.each { arch ->
    imageTags = imageTags + " " + getTag(registry, projectName, versionstring, arch)
  }

  print "image tags: $imageTags"
  print "manifest tags: $manifestTag"

  // workaround to delete manifest if it exist due to previous build
  try {
    // only supported with docker >20.10.0
    sh "docker manifest rm " + manifestTag + " " + imageTags
  } catch (e) {
    print "expected exception could not delete manifest: ${e}"
  }

  // Create the manifest file
  sh "docker manifest create " + manifestTag + " " + imageTags

  // Save the created manifest as an artifact
  filename = "manifest_" + registry + ".json"
  sh "docker manifest inspect " + manifestTag + " > " + filename
  archiveArtifacts(filename)

  // Push the manifest to the registry
  sh "docker manifest push -p " + manifestTag
}

def call(Map args) { 
  /*************************/
  /* Default Configuration */
  /*************************/
  // container_create_options_default = '{\\"Hostname\\":\\"publisher\\",\\"Cmd\\":[\\"--di=30\\",\\"--to\\",\\"--aa\\",\\"--si=0\\",\\"--ms=0\\",\\"--pf=/app/config/publishednodes.json\\",\\"--tc=/app/telemetryconfiguration.json\\",\\"--fn\\"],\\"HostConfig\\":{\\"Mounts\\":[{\\"Type\\":\\"bind\\",\\"Source\\":\\"/etc/gateway/mqtt-config.json\\",\\"Target\\":\\"/mqtt-config.json\\",\\"ReadOnly\\":false},{\\"Type\\":\\"bind\\",\\"Source\\":\\"/etc/gateway/settings.json\\",\\"Target\\":\\"/settings.json\\",\\"ReadOnly\\":false},{\\"Type\\":\\"bind\\",\\"Source\\":\\"/usr/local/\\",\\"Target\\":\\"/host\\",\\"ReadOnly\\":false},{\\"Type\\":\\"bind\\",\\"Source\\":\\"/etc/ssl/\\",\\"Target\\":\\"/etc/ssl/\\",\\"ReadOnly\\":true},{\\"Type\\":\\"bind\\",\\"Source\\":\\"/usr/share/ca-certificates/\\",\\"Target\\":\\"/usr/share/ca-certificates/\\",\\"ReadOnly\\":true}],\\"PortBindings\\":{\\"62222/tcp\\":[{\\"HostPort\\":\\"62222\\"}],\\"5001/tcp\\":[{\\"HostPort\\":\\"5003\\"}]}},\\"ExposedPorts\\":{\\"5001/tcp\\":{}}}'
  // These are the default build parameters
  // Depending on how the pipeline was started, these will be adjusted in the first stage!
  buildParams = [
    build_archs: ["amd64", "arm32v7", "aarch64"],
    tag: "No",
    project_name: args.project_name,
    docker_context_path: "./",
    run_test: "Yes",
    tag_to_production: "No",
    tag_to_training: "No",
    tag_to_development: "No",
    tag_latest: "No",
    tag_latest_dev: "No"
    // container_create_options: container_create_options_default,
    // test_dir: "tests",
    // test_cmd: "npm i; npm run test",
    // test_docker_image: "node:latest",
  ]

  acrProd = "netsps.azurecr.io"
  acrTraining = "epcontainerregistrytraining.azurecr.io"
  acrDev = "epcontainerregistrydevelopment.azurecr.io"
  GIT_CREDS="3b473638-19b3-4f50-b39c-7597de0a5f6b"

  notificationChannelCredentialsId = args.notificationChannelCredentialsId
  notificationColorInfo = 'ffff00' // yellow
  notificationColorError = 'ff0000' // red
  notificationColorSuccess = '00ff00' // green

  /******************/
  /* Build Pipeline */
  /******************/

  version = ""
  versionstring = ""
  projectName = ""

  node('buildmachine-netcloud-linux') {
    // Notify Teams that the build has started
    withCredentials([string(credentialsId: notificationChannelCredentialsId, variable: 'webhookUrl')]) {
      office365ConnectorSend color: notificationColorInfo, message: "Job $currentBuild.displayName started", status: "STARTED", webhookUrl: webhookUrl
    }

    // Run the pipeline and catch any error.
    // This will set currentBuild.result to 'FAILURE' if an error occurs.
    catchError {
      stage('Set Build Parameters') {
        // Checkout git branch and save commit hash
        hash = checkout(scm).GIT_COMMIT

        // Execute the git-version-gen buildscript. For more info on the buildscripts see:
        // https://bitbucket.hilscher.com/projects/NF/repos/buildscripts
        versionstring = sh returnStdout: true , script: 'git-version-gen'
        // versionstring = '864ed28b.bugfix-GWT-72-gwt-cockpit-plugin-save-button-always-disabled'
        print "#### versionstring : "+ versionstring
        print "#### env.CHANGE_TARGET : "+ env.CHANGE_TARGET
        print "#### env.BRANCH_NAME : "+ env.BRANCH_NAME

        if (env.CHANGE_TARGET != null) {
          // PR was opened, target branch is env.CHANGE_TARGET
          if (env.CHANGE_TARGET == "master") {
            // PR to master
            print "PR to master opened"
            buildParams.run_test = "Yes"
            buildParams.tag = "No"
            buildParams.tag_to_production = "No"
            buildParams.tag_to_training = "No"
            buildParams.tag_to_development = "No"
            buildParams.tag_latest_dev = "No"
            buildParams.tag_latest = "No"
          } else if (env.CHANGE_TARGET == "develop") {
            // PR to develop
            print "PR to develop opened"
            buildParams.run_test = "Yes"
            buildParams.tag = "No"
            buildParams.tag_to_production = "No"
            buildParams.tag_to_training = "No"
            buildParams.tag_to_development = "No"
            buildParams.tag_latest_dev = "No"
            buildParams.tag_latest = "No"
          } else {
            // PR to anywhere else
            print "PR to " + env.CHANGE_TARGET + " opened"
            buildParams.run_test = "Yes"
            buildParams.tag = "No"
            buildParams.tag_to_production = "No"
            buildParams.tag_to_training = "No"
            buildParams.tag_to_development = "No"
            buildParams.tag_latest_dev = "No"
            buildParams.tag_latest = "No"
          }
        } else {
          // Pipeline either started via merge or new branch opened
          if (env.BRANCH_NAME == "master") {
            // Merge to master
            print "Master branch was updated - Tag in production"
            buildParams.run_test = "No"
            buildParams.tag = "Yes"
            buildParams.tag_to_production = "Yes"
            buildParams.tag_to_training = "Yes"
            buildParams.tag_to_development = "No"
            buildParams.tag_latest_dev = "No"
            buildParams.tag_latest = "Yes"
          } else if (env.BRANCH_NAME == "develop") {
            // Merge to develop
            print "Develop branch was updated - Tag in training and development"
            buildParams.run_test = "No"
            buildParams.tag = "Yes"
            buildParams.tag_to_production = "No"
            buildParams.tag_to_training = "Yes"
            buildParams.tag_to_development = "Yes"
            buildParams.tag_latest_dev = "Yes"
            buildParams.tag_latest = "No"
          } else {
            print "Develop branch was updated - Tag in training and development"
            // Either a commit to an arbitrary which is not develop or master
            // Or a "tag branch"
            withCredentials([
              [$class: 'UsernamePasswordMultiBinding', credentialsId: GIT_CREDS, usernameVariable: 'GIT_USERNAME', passwordVariable: 'GIT_PASSWORD'],
            ]) {
              sh("""
              git config --global credential.username $GIT_USERNAME
              git config --global credential.helper "!echo password=$GIT_PASSWORD; echo"
              """)
            }
            tag = sh(returnStdout: true, script: 'git fetch --tags && git tag --points-at HEAD | awk NF').trim()

            if (tag) {
              // We're working with a "tag branch"
              print "Tag is: " + tag
              // Check out master and develop
              // To test on which branches this tag exists
              // Yes, this is a dirty hack
              sh "git checkout develop"
              sh "git pull"
              sh "git checkout master"
              sh "git pull"
              sh "git checkout " + env.BRANCH_NAME
              sh "git pull"
              containingBranches = sh(returnStdout: true, script: 'git branch --contains ' + hash).trim()
              buildParams.run_test = "Yes"
              foundMatchingBranch = false
              // This is facy syntax for regex matching in groovy
              if (containingBranches =~ /develop/) {
                foundMatchingBranch = true
                print "Tag exists on develop"
                buildParams.run_test = "No"
                buildParams.tag = "Yes"
                buildParams.tag_to_production = "No"
                buildParams.tag_to_training = "No"
                buildParams.tag_to_development = "Yes"
                buildParams.tag_latest_dev = "Yes"
                buildParams.tag_latest = "No"
              }
              if (containingBranches =~ /master/) {
                foundMatchingBranch = true
                print "Tag exists on master"
                buildParams.run_test = "No"
                buildParams.tag = "Yes"
                buildParams.tag_to_production = "Yes"
                buildParams.tag_to_training = "Yes"
                buildParams.tag_latest_dev = "No"
                buildParams.tag_latest = "Yes"
              }
              if (foundMatchingBranch == false) {
                // This is a tag, but it doesn't exist on develop or master
                // Do nothing
                print "Tag commit, not on develop or master. Do nothing"
                buildParams.run_test = "Yes"
                buildParams.tag = "No"
                buildParams.tag_to_production = "No"
                buildParams.tag_to_training = "No"
                buildParams.tag_to_development = "No"
                buildParams.tag_latest_dev = "No"
                buildParams.tag_latest = "No"
              }
            } else {
              // Just a comit to a arbitrary branc
              print "Commit on arbitrary branch. Test commit"
              buildParams.run_test = "Yes"
              buildParams.tag = "No"
              buildParams.tag_to_production = "No"
              buildParams.tag_to_training = "No"
              buildParams.tag_to_development = "No"
              buildParams.tag_latest_dev = "No"
              buildParams.tag_latest = "No"
            }
          }
        }

        for (elem in buildParams) {
          print elem.key + ": " + elem.value
        }
      }
      stage('Build') {
        // The development repo is always required during build
        withCredentials([
            [$class: 'UsernamePasswordMultiBinding', credentialsId: acrDev, usernameVariable: 'ACRUSERNAME', passwordVariable: 'ACRPASSWORD'],
        ]) {
          dockerLogin(acrDev, ACRUSERNAME, ACRPASSWORD)
        }

        // The only reason to log into the training repository is if we want to push a tag
        if (buildParams.tag_to_training == "Yes") {
          withCredentials([
            [$class: 'UsernamePasswordMultiBinding', credentialsId: acrTraining, usernameVariable: 'ACRUSERNAME', passwordVariable: 'ACRPASSWORD']
          ]) {
            dockerLogin(acrTraining, ACRUSERNAME, ACRPASSWORD)
          }
        }

        // The only reason to log into the production repository is if we want to push a tag
        if (buildParams.tag_to_production == "Yes") {
          withCredentials([
            [$class: 'UsernamePasswordMultiBinding', credentialsId: acrProd, usernameVariable: 'ACRUSERNAME', passwordVariable: 'ACRPASSWORD']
          ]) {
            dockerLogin(acrProd, ACRUSERNAME, ACRPASSWORD)
          }
        }

        // Build images for all architectures
        buildParams.build_archs.each { arch ->
          // Gather all tags for which to build for
          // They won't be pushed to the registries until after testing
          tags = ""
          if (buildParams.tag_to_production == "Yes") {
            tags += '-t ' + getTag(acrProd, buildParams.project_name, versionstring, arch) + ' '
          }
          if (buildParams.tag_to_training == "Yes") {
            tags += '-t ' + getTag(acrTraining, buildParams.project_name, versionstring, arch) + ' '
          }
          if (buildParams.tag_to_development == "Yes" || buildParams.run_test == "Yes") {
            tags += '-t ' + getTag(acrDev, buildParams.project_name, versionstring, arch) + ' '
          }

          // Create a tag that will be used for creating a license report
          if (buildParams.create_license_report == "Yes") {
            tags += '-t ' + getTag(acrDev, buildParams.project_name, versionstring, arch) + '_terntest' + ' '
          }

          // Build all tags
          dockerBuild(buildParams.docker_context_path, arch, tags)

          // Push every image to the dev registry for manual testing
          if (buildParams.run_test == "Yes") {
            dockerPush(getTag(acrDev, buildParams.project_name, versionstring, arch))
          }
        }
      }
      stage('Test') {
        if (buildParams.run_test == "Yes") {
          print "To test the image run the following command on a gateway with the netFIELD App Platform Connector installed."
          print '"docker-iotedge run -v /usr/local/:/host ' + getTag(acrDev, buildParams.project_name, versionstring, "amd64") + '"'
          print '"docker-iotedge run -v /usr/local/:/host ' + getTag(acrDev, buildParams.project_name, versionstring, "arm32v7") + '"'
          print '"docker-iotedge run -v /usr/local/:/host ' + getTag(acrDev, buildParams.project_name, versionstring, "aarch64") + '"'
          print 'An additional tab for the frontend is displayed, to be reached at : "https://<IP_ADDRESS>/'+args.frontend_tab+'"'
          print '!!!! CAUTION: this only works when the port '+args.port_number+' is binded on host or you manually set the service endpoint to the ip of the docker container!!!!'
          print 'To cleanup run: "sudo rm -r -f /usr/local/share/cockpit/'+args.frontend_tab+'".'
        } else {
          echo 'Skipped Tests'
        }
      }
      stage('Tag & Push') {
        if (buildParams.tag == "Never"){
          projectName = buildParams.project_name
          archs = []

          // Push every image
          buildParams.build_archs.each { arch ->
            if (buildParams.tag_to_production  == "Yes") {
              dockerPush(getTag(acrProd, buildParams.project_name, versionstring, arch))
            }

            if (buildParams.tag_to_training == "Yes") {
              dockerPush(getTag(acrTraining, buildParams.project_name, versionstring, arch))
            }

            if (buildParams.tag_to_development == "Yes") {
              dockerPush(getTag(acrDev, buildParams.project_name, versionstring, arch))
            }
          }

          // Create and push multiarch tags
          if (buildParams.tag_to_production == "Yes") {
            multiArchTag(acrProd, projectName, versionstring, buildParams.build_archs)

            if (buildParams.tag_latest == "Yes") {
              multiArchTag(acrProd, projectName, versionstring, buildParams.build_archs, "latest")
            }

            if (buildParams.tag_latest_dev == "Yes") {
              multiArchTag(acrProd, projectName, versionstring, buildParams.build_archs, "latest-dev")
            }
          }

          if (buildParams.tag_to_training == "Yes") {
            multiArchTag(acrTraining, projectName, versionstring, buildParams.build_archs)

            if (buildParams.tag_latest == "Yes") {
              multiArchTag(acrTraining, projectName, versionstring, buildParams.build_archs, "latest")
            }

            if (buildParams.tag_latest_dev == "Yes") {
              multiArchTag(acrTraining, projectName, versionstring, buildParams.build_archs, "latest-dev")
            }
          }

          if (buildParams.tag_to_development == "Yes") {
            multiArchTag(acrDev, projectName, versionstring, buildParams.build_archs)

            if (buildParams.tag_latest == "Yes") {
              multiArchTag(acrDev, projectName, versionstring, buildParams.build_archs, "latest")
            }

            if (buildParams.tag_latest_dev == "Yes") {
              multiArchTag(acrDev, projectName, versionstring, buildParams.build_archs, "latest-dev")
            }
          }
        } else {
          echo 'Skipped Tagging'
        } 
      }

      // The pipeline executed successfully
      currentBuild.result = 'SUCCESS'
    }

    // Notify Teams about the final build status
    notificationColor = notificationColorInfo
    if (currentBuild.result == 'SUCCESS') {
      notificationColor = notificationColorSuccess
    } else if (currentBuild.result == 'FAILURE') {
      notificationColor = notificationColorError
    }
    withCredentials([string(credentialsId: notificationChannelCredentialsId, variable: 'webhookUrl')]) {
      office365ConnectorSend color: notificationColor, message: "Job $currentBuild.displayName is done", status: currentBuild.result, webhookUrl: webhookUrl
    }
  }
}
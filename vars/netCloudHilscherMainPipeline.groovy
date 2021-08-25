import com.hilscher.netCloud.BuildParametersCollection

def call(Closure body) {
    /*************************/
    /* Spezific Parameters for every Project delivered in Closure  
        project_name 
        port_number 
        notificationChannelCredentialsId 
        frontend_tab
        container_name
    */
    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_ONLY
    body.delegate = config
    body()

    /* Default build Configuration parameters */

    BuildParametersCollection buildParamColl = new BuildParametersCollection()

    def buildParams = [
        project_name: config.args.project_name,
        build_archs: ["amd64", "arm32v7", "aarch64"],
        docker_context_path: "./",
        notificationChannelCredentialsId: config.args.notificationChannelCredentialsId
    ]

    pipeline {

        agent {
            label 'buildmachine-netcloud-linux'
        }

        environment {
            acrProd_Id = 'netsps.azurecr.io' 
            acrTraining_Id = 'epcontainerregistrytraining.azurecr.io'
            acrDev_Id = 'epcontainerregistrydevelopment.azurecr.io'
            acrProd = credentials('netsps.azurecr.io') 
            acrTraining = credentials('epcontainerregistrytraining.azurecr.io')
            acrDev = credentials('epcontainerregistrydevelopment.azurecr.io')
            GIT_CREDS = credentials('3b473638-19b3-4f50-b39c-7597de0a5f6b')
            notificationColorInfo = 'ffff00' // yellow
            notificationColorError = 'ff0000' // red
            notificationColorSuccess = '00ff00' // green
        }

        options {
            timestamps()
            // timeout(time: 1, unit: 'HOURS')
            /* prevent jenkins from build two instances of this pipeline in parallel */
            // disableConcurrentBuilds()
            buildDiscarder(logRotator(numToKeepStr: '20', artifactNumToKeepStr: '20'))
        }

        stages {
            stage('Set Build Parameters') {
                when {
                    expression { env.CHANGE_TARGET == null}
                }
                steps {
                    script {
                        // Notify Teams that the build has started
                        withCredentials([string(credentialsId: buildParams.notificationChannelCredentialsId, variable: 'webhookUrl')]) {
                            office365ConnectorSend color: notificationColorInfo, message: "Job $currentBuild.displayName started", status: "STARTED", webhookUrl: webhookUrl
                        }
                        hash = checkout(scm).GIT_COMMIT
                        // Execute the git-version-gen buildscript. For more info on the buildscripts see:
                        env.versionstring = sh returnStdout: true , script: 'git-version-gen'

                        switch (env.BRANCH_NAME) {
                            case 'master':
                                buildParamColl.buildParams.run_test = 'No'
                                buildParamColl.buildParams.tag = 'Yes'
                                buildParamColl.buildParams.tag_to_production = 'Yes'
                                buildParamColl.buildParams.tag_to_training = 'Yes'
                                buildParamColl.buildParams.tag_latest = 'Yes'
                                break
                            case 'develop':
                                buildParamColl.buildParams.run_test = 'No'
                                buildParamColl.buildParams.tag = 'Yes'
                                buildParamColl.buildParams.tag_to_training = 'Yes'
                                buildParamColl.buildParams.tag_to_development = 'Yes'
                                buildParamColl.buildParams.tag_latest_dev  = 'Yes'
                                break
                            default:
                                sh("""
                                git config --global credential.username $GIT_CREDS_USR
                                git config --global credential.helper "!echo password=$GIT_CREDS_PSW; echo"
                                """)
                                hasTag = sh(returnStdout: true, script: 'git fetch --tags && git tag | awk NF').trim()

                                if (hasTag) {
                                    // We're working with a "tag branch"
                                    log.info "tag is: " + hasTag
                                    // Check out master and develop
                                    // To test on which branches this tag exists
                                    // Yes, this is a dirty hack
                                    sh "git checkout develop"
                                    // sh "git pull"
                                    sh "git checkout master"
                                    // sh "git pull"
                                    sh "git checkout " + env.BRANCH_NAME
                                    // sh "git pull"
                                    containingBranches = sh(returnStdout: true, script: 'git branch --contains ' + hash).trim()
                                    buildParams.run_test = "Yes"
                                    // This is facy syntax for regex matching in groovy
                                    if (containingBranches =~ /develop/) {
                                        buildParamColl.buildParams.run_test = 'No'
                                        buildParamColl.buildParams.tag = 'Yes'
                                        buildParamColl.buildParams.tag_to_development = 'Yes'
                                        buildParamColl.buildParams.tag_latest_dev  = 'Yes'
                                    }
                                    if (containingBranches =~ /master/) {
                                        buildParamColl.buildParams.run_test = 'No'
                                        buildParamColl.buildParams.tag = 'Yes'
                                        buildParamColl.buildParams.tag_to_production = 'Yes'
                                        buildParamColl.buildParams.tag_to_training = 'Yes'
                                    }
                                }
                                break
                        }

                        // creating new environment variables for restart from stage 

                        env.run_test = "${buildParamColl.buildParams.run_test}" 
                        env.tag = "${buildParamColl.buildParams.tag}"
                        env.create_version = "${buildParamColl.buildParams.create_version}"
                        env.tag_to_production = "${buildParamColl.buildParams.tag_to_production}"
                        env.tag_to_training = "${buildParamColl.buildParams.tag_to_training}"
                        env.tag_to_development = "${buildParamColl.buildParams.tag_to_development}"
                        env.create_license_report = "${buildParamColl.buildParams.create_license_report}"
                        env.tag_latest = "${buildParamColl.buildParams.tag_latest}"
                        env.tag_latest_dev = "${buildParamColl.buildParams.tag_latest_dev}"

                        for (param in buildParamColl.buildParams) {
                            log.info param.key + ": " + param.value
                        }
                    }
                }        
            }
            stage('Build and push for test') {
                steps {
                    script {
                        // The development repo is always required during build
                        buildHelper.dockerLogin(acrDev_Id, acrDev_USR, acrDev_PSW)

                        // The only reason to log into the training repository is if we want to push a tag
                        if (env.tag_to_training == "Yes") {
                            buildHelper.dockerLogin(acrTraining_Id, acrTraining_USR, acrTraining_PSW)
                        }

                        // The only reason to log into the production repository is if we want to push a tag
                        if (env.tag_to_production == "Yes") {
                            buildHelper.dockerLogin(acrProd_Id, acrProd_USR, acrProd_PSW)
                        }

                        // Build images for all architectures
                        buildParams.build_archs.each { arch ->
                            // Gather all tags for which to build for
                            // They won't be pushed to the registries until after testing
                            tags = ""
                            if (env.tag_to_production == "Yes") {
                                tags += '-t ' + buildHelper.getTag(acrProd_Id, buildParams.project_name, versionstring, arch) + ' '
                            }
                            if (env.tag_to_training == "Yes") {
                                tags += '-t ' + buildHelper.getTag(acrTraining_Id, buildParams.project_name, versionstring, arch) + ' '
                            }
                            if (env.tag_to_development == "Yes" || env.run_test == "Yes") {
                                tags += '-t ' + buildHelper.getTag(acrDev_Id, buildParams.project_name, versionstring, arch) + ' '
                            }

                            // Create a tag that will be used for creating a license report
                            if (env.create_license_report == "Yes") {
                                tags += '-t ' + buildHelper.getTag(acrDev_Id, buildParams.project_name, versionstring, arch) + '_terntest' + ' '
                            }

                            // Build all tags
                            buildHelper.dockerBuild(buildParams.docker_context_path, arch, tags)

                            // Push every image to the dev registry for manual testing
                            if (env.run_test == "Yes") {
                                buildHelper.dockerPush(buildHelper.getTag(acrDev_Id, buildParams.project_name, versionstring, arch))
                            }
                        }
                    }
                }
            }
            stage('Test') {
                when {
                    expression { env.run_test == "Yes"}
                }
                steps {
                    script {
                        log.info 'To test the image run the following command on a gateway with the ' + config.args.container_name + ' installed.'
                        log.info '"docker-iotedge run -v /usr/local/:/host ' + buildHelper.getTag(acrDev_Id, buildParams.project_name, versionstring, "amd64") + '"'
                        log.info '"docker-iotedge run -v /usr/local/:/host ' + buildHelper.getTag(acrDev_Id, buildParams.project_name, versionstring, "arm32v7") + '"'
                        log.info '"docker-iotedge run -v /usr/local/:/host ' + buildHelper.getTag(acrDev_Id, buildParams.project_name, versionstring, "aarch64") + '"'
                        log.info 'An additional tab for the frontend is displayed, to be reached at : "https://<IP_ADDRESS>/'+config.args.frontend_tab+'"'
                        log.info '!!!! CAUTION: this only works when the port '+config.args.port_number+' is binded on host or you manually set the service endpoint to the ip of the docker container!!!!'
                        log.info 'To cleanup run: "sudo rm -r -f /usr/local/share/cockpit/'+config.args.frontend_tab+'".'
                    }
                }
            }
            stage('Tag & Push') {
                when {
                    expression { env.tag == "Yes"}
                }
                steps {
                    script {
                        archs = []

                        // Push every image
                       buildParams.build_archs.each { arch ->
                            if (env.tag_to_production  == "Yes") {
                                buildHelper.dockerPush(buildHelper.getTag(acrProd_Id, buildParams.project_name, versionstring, arch))
                            }

                            if (env.tag_to_training == "Yes") {
                                buildHelper.dockerPush(buildHelper.getTag(acrTraining_Id, buildParams.project_name, versionstring, arch))
                            }

                            if (env.tag_to_development == "Yes") {
                                buildHelper.dockerPush(buildHelper.getTag(acrDev_Id, buildParams.project_name, versionstring, arch))
                            }
                        }

                        // Create and push multiarch tags
                        if (env.tag_to_production == "Yes") {
                                buildHelper.multiArchTag(acrProd_Id, buildParams.project_name, versionstring, buildParams.build_archs)

                            if (env.tag_latest == "Yes") {
                                buildHelper.multiArchTag(acrProd_Id, buildParams.project_name, versionstring, buildParams.build_archs, "latest")
                            }

                            if (env.tag_latest_dev  == "Yes") {
                                buildHelper.multiArchTag(acrProd_Id, buildParams.project_name, versionstring, buildParams.build_archs, "latest-dev")
                            }
                        }

                        if (env.tag_to_training == "Yes") {
                            buildHelper.multiArchTag(acrTraining_Id, buildParams.project_name, versionstring, buildParams.build_archs)

                            if (env.tag_latest == "Yes") {
                                buildHelper.multiArchTag(acrTraining_Id, buildParams.project_name, versionstring, buildParams.build_archs, "latest")
                            }

                            if (env.tag_latest_dev  == "Yes") {
                                buildHelper.multiArchTag(acrTraining_Id, buildParams.project_name, versionstring, buildParams.build_archs, "latest-dev")
                            }
                        }

                        if (env.tag_to_development == "Yes") {
                            buildHelper.multiArchTag(acrDev_Id, buildParams.project_name, versionstring, buildParams.build_archs)

                            if (env.tag_latest == "Yes") {
                                buildHelper.multiArchTag(acrDev_Id, buildParams.project_name, versionstring, buildParams.build_archs, "latest")
                            }

                            if (env.tag_latest_dev  == "Yes") {
                                buildHelper.multiArchTag(acrDev_Id, buildParams.project_name, versionstring, buildParams.build_archs, "latest-dev")
                            }
                        }
                    } 
                }
            }
        }
        post {
            always {
                script {
                    notificationColor = notificationColorInfo
                    if (currentBuild.result == 'SUCCESS') {
                        notificationColor = notificationColorSuccess
                    } else if (currentBuild.result == 'FAILURE') {
                        notificationColor = notificationColorError
                    }
                    withCredentials([string(credentialsId: buildParams.notificationChannelCredentialsId, variable: 'webhookUrl')]) {
                        office365ConnectorSend color: notificationColor, message: "Job $currentBuild.displayName is done", status: currentBuild.result, webhookUrl: webhookUrl
                    }
                }
            }
        }
    }
}
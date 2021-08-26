import com.hilscher.netCloud.BuildParametersCollection

def call(Closure body) {
    /*************************/
    /* Specfic Parameters for every Project delivered within Closure  
        project_name 
        notificationChannelCredentialsId 
        container_create_options_default
        docker_context_path
        container_name
        build_archs
        deploy_IP
        device_id
        test_platform
        test_dir
        additional_modules
        container_id
        deployment_json
        deployment_json_amd64
        deployment_json_arm32
        container_id_prod
        container_id_train
        container_id_dev
        cifx-manager
    */
    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_ONLY
    body.delegate = config
    body()

    BuildParametersCollection buildParamColl = new BuildParametersCollection()

  /* Default build Configuration parameters */
    def buildParams = [
        build_archs: config.args.build_archs,
        deploy_IP: config.args.deploy_IP,
        device_id: config.args.device_id,
        test_platform: config.args.test_platform,
        project_name: config.args.project_name,
        docker_context_path: config.args.docker_context_path,
        container_name: config.args.container_name,
        container_create_options: config.args.container_create_options_default,
        test_dir: config.args.test_dir,
        additional_modules: config.args.additional_modules,
        test_cmd: "npm i; npm run test",
        test_docker_image: "node:latest",
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
            CONNECTIONSTRING_ID = 'DevelopmentIotHubConnectionString'
            CONNECTIONSTRING = credentials('DevelopmentIotHubConnectionString')
            cifx_manager = config.args.get('cifx_manager', "No")
            CONTAINER_ID = config.args.get('container_id', "")
            DEPLOYMENT_JSON = config.args.get('deployment_json', "")
            DEPLOYMENT_JSON_AMD64 = config.args.get('deployment_json_amd64', "")
            DEPLOYMENT_JSON_ARM32 = config.args.get('deployment_json_arm32', "")
            CONTAINER_ID_PROD = config.args.get('container_id_prod', "")
            CONTAINER_ID_TRAIN = config.args.get('container_id_train', "")
            CONTAINER_ID_DEV = config.args.get('container_id_dev', "")
            API_PROD = 'api'
            API_TRAIN = 'api-training'
            API_DEV = 'api-development'
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
            preserveStashes()
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
                                buildParamColl.buildParams.run_test = "No"
                                buildParamColl.buildParams.tag= "Yes"
                                buildParamColl.buildParams.tag_to_production = "Yes"
                                buildParamColl.buildParams.tag_to_training = "Yes"
                                buildParamColl.buildParams.tag_to_development = "Yes"
                                buildParamColl.buildParams.create_license_report = "Yes"
                                buildParamColl.buildParams.tag_latest = 'Yes'
                                head_points_to_tag = sh(returnStdout: true, script: 'git fetch --tags && git tag --points-at HEAD | awk NF').trim()
                                if(head_points_to_tag == versionstring) {
                                    buildParamColl.buildParams.create_version= "Yes"
                                }
                                break
                            case 'develop':
                                log.info "Develop branch was updated - Tag in training and development"
                                buildParamColl.buildParams.run_test = "No"
                                buildParamColl.buildParams.tag= "Yes"
                                buildParamColl.buildParams.tag_to_training = "Yes"
                                buildParamColl.buildParams.tag_to_development = "Yes"
                                buildParamColl.buildParams.tag_latest_dev = 'Yes'
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
                                        log.info "Tag exists on develop"
                                        buildParamColl.buildParams.run_test = "No"
                                        buildParamColl.buildParams.tag= "Yes"
                                        buildParamColl.buildParams.tag_to_development = "Yes"
                                        buildParamColl.buildParams.create_version= "Yes"
                                    }
                                    if (containingBranches =~ /master/) {
                                        log.info "Tag exists on master"
                                        buildParamColl.buildParams.run_test = "No"
                                        buildParamColl.buildParams.tag= "Yes"
                                        buildParamColl.buildParams.tag_to_production = "Yes"
                                        buildParamColl.buildParams.tag_to_training = "Yes"
                                        buildParamColl.buildParams.create_license_report = "Yes"
                                        buildParamColl.buildParams.create_version= "Yes"
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

            stage('Deploy & Test') {
                when {
                    expression { env.run_test == "Yes" && env.cifx_manager == "No"}
                }
                steps {
                    lock('netcloudbuilder') {
                        script {
                            // Create two files that are going to be used by {add,remove}_module
                            container_create_options = buildParams.container_create_options  
                            if (config.args.container_create_options_default_ext) {
                                container_create_options += (CONNECTIONSTRING + config.args.container_create_options_default_ext)
                            }

                            sh 'echo ' + container_create_options + ' > createOptions'
                            sh 'echo ' + CONNECTIONSTRING + ' > connectionString'

                            sh 'cat  connectionString'

                            // Deploy the module to the device
                            current_tag = versionstring + "-" + buildParams.test_platform
                            moduleUrl = acrDev_Id + "/" + buildParams.project_name + ":" + current_tag

                            // Execute the add_module buildscript. For more info on the buildscripts see:
                            sh "add_module " + buildParams.container_name + " " + buildParams.device_id + " " + moduleUrl + " " + acrDev_Id + " " + acrDev_USR + " " + acrDev_PSW + " --createOptions ./createOptions --connectionString ./connectionString " + buildParams.additional_modules.join(" ")
                            echo 'Module deployment command sent, waiting for completion of job'
                            sleep(time:1,unit:"MINUTES")

                            // Run the tests and remove the module afterwards
                            try {
                                timeout(time: 5, unit: 'MINUTES') {
                                    docker.image(buildParams.test_docker_image).inside('-u=root') {
                                        dir(buildParams.test_dir) {
                                        sh buildParams.test_cmd
                                        echo "Successfully ran tests"
                                        }
                                    }
                                }
                            } finally {
                                // Always clean up, even if test fail
                                // Execute the remove_module buildscript. For more info on the buildscripts see:
                                sh "remove_module " + buildParams.device_id + " --connectionString ./connectionString"
                                echo "Successfully removed modules from device"
                            }
                        }
                    }
                }
            }
           
            stage('Create License Reports'){
                when {
                    expression { env.create_license_report == "Yes" && env.cifx_manager == "No"}
                }
                steps {
                    script {
                        buildParams.build_archs.each{ arch -> 
                            buildHelper.createLicenseReport(versionstring, arch, acrDev_Id, buildParams.project_name, buildParams.tag_to_production)
                        }
                    }
                }
            }

            stage('Tag & Push') {
                when {
                    expression { env.tag == "Yes" }
                }
                steps {
                    script {
                        archs = []

                        // Push every image
                        buildParams.build_archs.each { arch -> 
                            if (env.tag_to_production  == "Yes") {
                                buildHelper.dockerPush(buildHelper.getTag(acrProd_Id, buildParams.project_name, versionstring, arch))
                                if (env.cifx_manager == "Yes" && env.create_version == "Yes") {
				                    buildHelper.addVerionsInNetfield(versionstring, acrProd_Id + "/" + buildParams.project_name + ":" + versionstring, "prod", "True", arch)
			                    }
                            }

                            if (env.tag_to_training == "Yes") {
                                buildHelper.dockerPush(buildHelper.getTag(acrTraining_Id, buildParams.project_name, versionstring, arch))
                                if (env.cifx_manager == "Yes" && env.create_version == "Yes") {
				                    buildHelper.addVerionsInNetfield(versionstring, acrTraining_Id + "/" + buildParams.project_name + ":" + versionstring, "train", "True", arch)
			                    }
                            }

                            if (env.tag_to_development == "Yes") {
                                buildHelper.dockerPush(buildHelper.getTag(acrDev_Id, buildParams.project_name, versionstring, arch))
                                if (env.cifx_manager == "Yes" && env.create_version == "Yes") {
				                    buildHelper.addVerionsInNetfield(versionstring, acrDev_Id + "/" + buildParams.project_name + ":" + versionstring, "dev", "True", arch)
			                    }
                            }
                        }

                        // Create and push multiarch tags
                        if (env.cifx_manager == "No" && env.tag_to_production  == "Yes") {
                            buildHelper.multiArchTag(acrProd_Id, buildParams.project_name, versionstring, buildParams.build_archs)
                            if (env.create_version  == "Yes") {
                                buildHelper.addVerionsInNetfield(versionstring, acrProd_Id + "/" + buildParams.project_name + ":" + versionstring, "prod", "False", "All")
                            }
                        }

                        if (env.cifx_manager == "No" && env.tag_to_training == "Yes") {
                            buildHelper.multiArchTag(acrTraining_Id, buildParams.project_name, versionstring, buildParams.build_archs)
                            if (env.create_version == "Yes") {
                                buildHelper.addVerionsInNetfield(versionstring, acrTraining_Id + "/" + buildParams.project_name + ":" + versionstring, "train", "False", "All")
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
                            if (env.cifx_manager == "No" && env.create_version == "Yes") {
                                buildHelper.addVerionsInNetfield(versionstring, acrDev_Id + "/" + buildParams.project_name + ":" + versionstring, "dev", "True", "All")
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
def getTag(repo, project, version, arch) {
  return repo + '/' + project + ':' + version + '-' + arch
}

def dockerLogin(registry, username, password) {
  sh "docker login -u $username -p $password $registry"
}

def dockerBuild(context, arch, tags) {
  dockerfile = context + 'Dockerfile.' + arch
  sh 'docker buildx build --progress tty --rm -f ' + dockerfile + ' ' +  tags + ' --load ' + context
}

def dockerPush(tag) {
  sh 'docker push ' + tag
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

// new server functions

def createLicenseReport(versionstring, arch, acrDev, project_name, tag_to_production) {
  // This method creates a license report for a given image
  // and adds the license report to the image itself, if the image
  // will be tagged to production.
  // NOTE: This method assumes that an image with the tag suffix "_terntest" was
  // created and pushed to the dev repository during build
  tag = getTag(acrDev, project_name, versionstring, arch) + "_terntest"
  report_filename = "third-party-license-report-Dockerfile." + arch + ".json"
  
  // This executes tern inside a docker container.
  // The docker image that is used is the same that is used in the github action for tern
  sh 'docker run --privileged --device /dev/fuse -v /var/run/docker.sock:/var/run/docker.sock --rm philipssoftware/tern:2.2.0 report -f json -i ' + tag + ' > ' + report_filename

  // Save this report to the artifacts of the current jenkins pipeline instance
  archiveArtifacts(report_filename)

  // if this is going to be tagged to production, add the license report to the prod image
  if (tag_to_production == "Yes") {
    // Adds a new file to an existing image by creating
    // a container from the image, copying the file into it
    // and commiting the container to the same tag again.
    prod_tag = getTag(acrProd, project_name, versionstring, arch)
    containerId = sh(returnStdout: true, script: 'docker create ' + prod_tag).trim()
    sh 'mkdir licenses'
    sh 'cp ' + report_filename + ' licenses/'
    sh 'docker cp licenses/ ' + containerId + ':/'
    sh 'docker commit ' + containerId + ' ' + prod_tag
    sh 'rm -r licenses/'
  }
}

def addVerionsInNetfield(version, imageUri, netfieldApi, exposeports, platform) {
  crntContainerId = ""
  crntNetfieldApi = ""
  crntApiToken = ""
  architecture = "All"

  switch (netfieldApi) {
    case 'prod':
      crntContainerId = CONTAINER_ID_PROD
      crntNetfieldApi = API_PROD
      crntApiToken = "Container-Management-API-KEY-PROD"
      break
    case 'train':
      crntContainerId = CONTAINER_ID_TRAIN
      crntNetfieldApi = API_TRAIN
      crntApiToken = "Container-Management-API-KEY-TRAIN"
      break
    case 'dev':
      crntContainerId = CONTAINER_ID_DEV
      crntNetfieldApi = API_DEV
      crntApiToken = "Container-Management-API-KEY-DEV"
      break
    default:
      exit(1)
  }

  switch (platform) {
    case 'arm32':
      architecture = "Linux-ARM32V7"
      break
    case 'arm64':
      architecture = "Linux-ARM64V8"
      break
    case 'amd64':
      architecture = "Linux-X64"
      break
    case 'amd86':
      architecture = "Linux-X86"
      break
    default:
      architecture = "All"
      break
  }

  // Check if a releasnotes file is present
  releaseNotesPath = "./releasenotes/releasenotes_" + version + ".txt"
  releaseNotesArgument = ""
  if(fileExists(releaseNotesPath)) {
    releaseNotesArgument = "--releaseNotes " + releaseNotesPath
  }

  // SECRET =  credentials("Container-Management-API-KEY-TRAIN")
  // sh "add_container_version " + version + " " + crntContainerId + " " + imageUri + " " + crntNetfieldApi + " " + SECRET + " " + DEPLOYMENT_JSON + " --architecture " + architecture + " --exposeports " + exposeports + " " + releaseNotesArgument
  withCredentials([string(credentialsId: crntApiToken, variable: 'SECRET')]) {
    print "add_container_version " + version + " " + crntContainerId + " " + imageUri + " " + crntNetfieldApi + " " + SECRET + " " + DEPLOYMENT_JSON + " --architecture " + architecture + " --exposeports " + exposeports + " " + releaseNotesArgument 
    sh "add_container_version " + version + " " + crntContainerId + " " + imageUri + " " + crntNetfieldApi + " " + SECRET + " " + DEPLOYMENT_JSON + " --architecture " + architecture + " --exposeports " + exposeports + " " + releaseNotesArgument
  }
}
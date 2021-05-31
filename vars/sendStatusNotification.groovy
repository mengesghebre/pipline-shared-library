#!/usr/bin/env groovy

/**
 * Send status notifications based on outcome
 */
def call(String buildJobStatus = 'STARTED') {

  buildJobStatus =  buildJobStatus ?: 'SUCCESSFUL'

  // Default values
  def buildColorName = 'RED'
  def buildColorCode = '#FF0000'
  def mailSubject = "${buildJobStatus}: Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]'"
  def summary = "${mailSubject} (${env.BUILD_URL})"
  def mailDetails = """<p>${buildJobStatus}: Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]':</p>
    <p>Check console output at &QUOT;<a href='${env.BUILD_URL}'>${env.JOB_NAME} [${env.BUILD_NUMBER}]</a>&QUOT;</p>"""

  // Override values based on build status 
  if (buildJobStatus == 'STARTED') {
    buildColor = 'YELLOW'
    buildColorCode = '#FFFF00'
  } else if (buildJobStatus == 'SUCCESSFUL') {
    buildColor = 'GREEN'
    buildColorCode = '#00FF00'
  } else {
    buildColor = 'RED'
    buildColorCode = '#FF0000'
  }

  // Send status notifications
  // slackSend (color: buildColorCode, message: summary)

  hipchatSend (color: buildColor, notify: true, message: summary)

  emailext (
      to: 'mghebreyesus@hilscher.com, ghebreym@gmx.de',
      subject: mailSubject,
      body: mailDetails,
      recipientProviders: [[$class: 'DevelopersRecipientProvider']]
    )
}
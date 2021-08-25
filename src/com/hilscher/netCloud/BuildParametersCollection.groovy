package com.hilscher.netCloud

import com.cloudbees.groovy.cps.NonCPS

class BuildParametersCollection implements Serializable
{   
    def buildParams = [
        run_test: "Yes",
        tag: "No",
        create_version: "No",
        tag_to_production: "No",
        tag_to_training: "No",
        tag_to_development: "No",
        create_license_report: "No",
        tag_latest: 'No',
        tag_latest_dev: 'No',
    ]
}
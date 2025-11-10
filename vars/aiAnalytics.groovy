def call(String terraformRepoUrl) {
    def tfRepoName = terraformRepoUrl.tokenize('/').last().replace('.git', '')
    def sharedLibRepo = "https://github.com/shakilmunavary/jenkins-shared-ai-lib.git"

    def tfDir = "${tfRepoName}/terraform"
    def outputHtml = "${tfDir}/output.html"

    stage('Clone Repos') {
        sh """
            git clone ${terraformRepoUrl}
            git clone ${sharedLibRepo}
        """
    }

    stage('Download tfstate from S3') {
        withCredentials([
            string(credentialsId: 'aws-access-key-id', variable: 'AWS_ACCESS_KEY_ID'),
            string(credentialsId: 'aws-secret-access-key', variable: 'AWS_SECRET_ACCESS_KEY')
        ]) {
            sh """
                if aws s3 ls s3://ai-terraform-state-file/${tfRepoName}/${tfRepoName}.state; then
                    aws s3 cp s3://ai-terraform-state-file/${tfRepoName}/${tfRepoName}.state ${tfDir}/terraform.tfstate
                fi
            """
        }
    }

    stage('Terraform Init & Plan') {
        dir(tfDir) {
            withCredentials([
                string(credentialsId: 'INFRACOST_APIKEY', variable: 'INFRACOST_API_KEY')
            ]) {
                sh """
                    terraform init
                    terraform plan -out=tfplan.binary
                    terraform show -json tfplan.binary > tfplan.raw.json

                    jq '
                      .resource_changes |= sort_by(.address) |
                      del(.resource_changes[].change.after_unknown) |
                      del(.resource_changes[].change.before_sensitive) |
                      del(.resource_changes[].change.after_sensitive) |
                      del(.resource_changes[].change.after_identity) |
                      del(.resource_changes[].change.before) |
                      del(.resource_changes[].change.after.tags_all) |
                      del(.resource_changes[].change.after.tags) |
                      del(.resource_changes[].change.after.id) |
                      del(.resource_changes[].change.after.arn)
                    ' tfplan.raw.json > tfplan.json

                    infracost configure set api_key \$INFRACOST_API_KEY
                    infracost breakdown --path=tfplan.binary --format json --out-file totalcost.json
                """
            }
        }
    }

    stage('AI Analytics') {
        withCredentials([
            string(credentialsId: 'AZURE_API_KEY', variable: 'AZURE_API_KEY'),
            string(credentialsId: 'AZURE_API_BASE', variable: 'AZURE_API_BASE'),
            string(credentialsId: 'AZURE_DEPLOYMENT_NAME', variable: 'DEPLOYMENT_NAME'),
            string(credentialsId: 'AZURE_API_VERSION', variable: 'AZURE_API_VERSION')
        ]) {
            aiAnalytics(
                "${tfDir}/tfplan.json",
                "jenkins-shared-ai-lib/guardrails/guardrails_v1.txt",
                "jenkins-shared-ai-lib/reference_terra_analysis_html.html",
                outputHtml,
                "${tfDir}/payload.json",
                AZURE_API_KEY,
                AZURE_API_BASE,
                DEPLOYMENT_NAME,
                AZURE_API_VERSION
            )
        }
    }

    stage('Publish Report') {
        publishHTML([
            reportName: 'AI Analysis',
            reportDir: tfDir,
            reportFiles: 'output.html',
            keepAll: true,
            allowMissing: false,
            alwaysLinkToLastBuild: true
        ])
    }

    stage('Evaluate Guardrail Coverage') {
        def passCount = sh(script: "grep -o 'class=\"pass\"' ${outputHtml} | wc -l", returnStdout: true).trim().toInteger()
        def failCount = sh(script: "grep -o 'class=\"fail\"' ${outputHtml} | wc -l", returnStdout: true).trim().toInteger()
        def coverage = passCount + failCount > 0 ? (passCount * 100 / (passCount + failCount)).toInteger() : 0

        echo "🔍 Guardrail Coverage: ${coverage}%"
        sh "sed -i 's/Overall Guardrail Coverage: .*/Overall Guardrail Coverage: ${coverage}%/' ${outputHtml}"

        currentBuild.description = "Auto-${coverage >= 50 ? 'approved' : 'rejected'} (Coverage: ${coverage}%)"
    }
}

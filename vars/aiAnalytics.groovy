def call(Map config) {
  def terraformRepo = config.terraformRepo
  def folderName    = config.folderName

  node {
    // Isolated temp workspace inside Jenkins job folder
    def TMP_DIR = "${env.WORKSPACE}/tmp-${env.BUILD_ID}"
    sh "rm -rf '${TMP_DIR}' && mkdir -p '${TMP_DIR}'"

    stage('Clone Repos') {
      dir(TMP_DIR) {
        sh """
          echo "📦 Cloning Terraform Repo"
          rm -rf terraform-code
          git clone '${terraformRepo}' terraform-code

          echo "📦 Cloning Shared AI Library"
          rm -rf jenkins-shared-ai-lib
          git clone 'https://github.com/shakilmunavary/jenkins-shared-ai-lib.git' jenkins-shared-ai-lib
        """
      }
    }

    stage('Terraform Init & Plan') {
      dir("${TMP_DIR}/terraform-code/${folderName}") {
        sh """
          terraform init
          terraform plan -out=tfplan.binary
          terraform show -json tfplan.binary > tfplan.raw.json

          # jq normalization exactly as in your Jenkinsfile (minus cost step)
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
        """
      }
    }

    stage('AI Analytics') {
      withCredentials([
        string(credentialsId: 'AZURE_API_KEY',        variable: 'AZURE_API_KEY'),
        string(credentialsId: 'AZURE_API_BASE',       variable: 'AZURE_API_BASE'),
        string(credentialsId: 'AZURE_DEPLOYMENT_NAME',variable: 'DEPLOYMENT_NAME'),
        string(credentialsId: 'AZURE_API_VERSION',    variable: 'AZURE_API_VERSION')
      ]) {
        // Paths inside temp workspace
        def tfDir           = "${TMP_DIR}/terraform-code/${folderName}"
        def sharedLibDir    = "${TMP_DIR}/jenkins-shared-ai-lib"
        def tfPlanJson      = "${tfDir}/tfplan.json"
        def guardrailsPath  = "guardrails/guardrails_v1.txt"
        def htmlTemplate    = "reference_terra_analysis_html.html"
        def outputHtml      = "${tfDir}/output.html"
        def payloadJson     = "${tfDir}/payload.json"

        // Use the updated aiAnalytics (below)
        aiAnalytics(
          terraformRepo: terraformRepo,
          folderName: folderName,
          guardrailsPath: guardrailsPath,
          htmlTemplatePath: htmlTemplate,
          outputHtmlPath: outputHtml,
          payloadPath: payloadJson,
          azureApiKey: env.AZURE_API_KEY,
          azureApiBase: env.AZURE_API_BASE,
          deploymentName: env.DEPLOYMENT_NAME,
          apiVersion: env.AZURE_API_VERSION
        )
      }
    }

    stage('Publish Report') {
      // Publish from the temp terraform folder
      publishHTML([
        reportName: 'AI Analysis',
        reportDir: "${TMP_DIR}/terraform-code/${folderName}",
        reportFiles: 'output.html',
        keepAll: true,
        allowMissing: false,
        alwaysLinkToLastBuild: true
      ])
    }

    stage('Evaluate Guardrail Coverage') {
      def outputHtml = "${TMP_DIR}/terraform-code/${folderName}/output.html"
      def passCount  = sh(script: "grep -oi 'class=\"pass\"' '${outputHtml}' | wc -l",  returnStdout: true).trim().toInteger()
      def failCount  = sh(script: "grep -oi 'class=\"fail\"' '${outputHtml}' | wc -l",  returnStdout: true).trim().toInteger()
      def coverage   = (passCount + failCount) > 0 ? (passCount * 100 / (passCount + failCount)).toInteger() : 0

      echo "🔍 Guardrail Coverage: ${coverage}%"
      sh "sed -i 's/Overall Guardrail Coverage: .*/Overall Guardrail Coverage: ${coverage}%/' '${outputHtml}'"

      env.PIPELINE_DECISION     = coverage >= 50 ? 'APPROVED' : 'REJECTED'
      currentBuild.description  = "Auto-${env.PIPELINE_DECISION.toLowerCase()} (Coverage: ${coverage}%)"
    }

    stage('Decision') {
      if (env.PIPELINE_DECISION == 'APPROVED') {
        echo "✅ Pipeline approved. Proceeding..."
      } else {
        echo "❌ Pipeline rejected. Halting..."
      }
    }
  }
}

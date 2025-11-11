def call(Map config) {
  def terraformRepo = config.terraformRepo
  def folderName    = config.folderName

  def TMP_DIR = "${env.WORKSPACE}/tmp-${env.BUILD_ID}"
  sh "rm -rf '${TMP_DIR}' && mkdir -p '${TMP_DIR}'"

  stage('Clone Repos') {
    dir(TMP_DIR) {
      sh """
        git clone '${terraformRepo}' terraform-code
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

  stage('Generate Resource × Rule Matrix') {
    def tfDir        = "${TMP_DIR}/terraform-code/${folderName}"
    def guardrails   = new File("${TMP_DIR}/jenkins-shared-ai-lib/guardrails/guardrails_v1.txt")
    def matrixFile   = new File("${tfDir}/resource_rule_matrix.txt")
    matrixFile.text  = ""

    // Collect resource addresses
    def resources = sh(script: "jq -r '.resource_changes[].address' ${tfDir}/tfplan.json", returnStdout: true).trim().split("\n")

    def lines = guardrails.readLines()

    resources.each { res ->
      def type = res.split("\\.")[0]
      def inType = false
      def ruleId = null

      lines.eachWithIndex { line, idx ->
        if (line.startsWith("Resource Type: ${type}")) {
          inType = true
        } else if (line.startsWith("Resource Type:")) {
          inType = false
        }

        if (inType && line.contains("Rule ID:")) {
          ruleId = line.replaceAll(/.*Rule ID:\s*([^\]]*).*/, '$1')
          // Look ahead for Rule description
          def ruleDesc = ""
          for (int j = idx+1; j < lines.size(); j++) {
            if (lines[j].startsWith("Rule:")) {
              ruleDesc = lines[j].replaceFirst("Rule:\\s*", "")
              break
            }
          }
          if (!ruleDesc) ruleDesc = "Rule description not found"
          matrixFile << "${res}\t${ruleId}\t${ruleDesc}\n"
        }
      }
    }
  }

  stage('AI analytics with Azure OpenAI') {
    withCredentials([
      string(credentialsId: 'AZURE_API_KEY',         variable: 'AZURE_API_KEY'),
      string(credentialsId: 'AZURE_API_BASE',        variable: 'AZURE_API_BASE'),
      string(credentialsId: 'AZURE_DEPLOYMENT_NAME', variable: 'DEPLOYMENT_NAME'),
      string(credentialsId: 'AZURE_API_VERSION',     variable: 'AZURE_API_VERSION')
    ]) {
      def tfDir          = "${TMP_DIR}/terraform-code/${folderName}"
      def sharedLibDir   = "${TMP_DIR}/jenkins-shared-ai-lib"
      def tfPlanJsonPath = "${tfDir}/tfplan.json"
      def guardrailsPath = "${sharedLibDir}/guardrails/guardrails_v1.txt"
      def templatePath   = "${sharedLibDir}/reference_terra_analysis_html.html"
      def matrixPath     = "${tfDir}/resource_rule_matrix.txt"
      def outputHtmlPath = "${tfDir}/output.html"
      def payloadPath    = "${tfDir}/payload.json"
      def responsePath   = "${outputHtmlPath}.raw"

      sh """
        PLAN_FILE_CONTENT=\$(jq -Rs . < "${tfPlanJsonPath}")
        GUARDRAILS_CONTENT=\$(jq -Rs . < "${guardrailsPath}")
        SAMPLE_HTML=\$(jq -Rs . < "${templatePath}")
        MATRIX_CONTENT=\$(jq -Rs . < "${matrixPath}")

        cat <<EOF > "${payloadPath}"
{
  "messages": [
    {
      "role": "system",
      "content": """
You are a Terraform compliance auditor. You will receive four input files:
1) Terraform Plan JSON,
2) Guardrails Checklist,
3) Sample HTML Template,
4) Resource × Rule Matrix.

Return a single HTML document with these sections:

1️⃣ Change Summary Table
- Title: 'What's Being Changed'
- Columns: Resource Name, Resource Type, Action (Add/Delete/Update), Details
- Ensure resource count matches Terraform plan

2️⃣ Terraform Code Recommendations
- Provide actionable suggestions to improve code quality

3️⃣ Security and Compliance Recommendations
- Highlight misconfigurations and generic recommendations

4️⃣ Guardrail Compliance Summary
- Title: 'Guardrail Compliance Summary'
- Columns: Terraform Resource, Rule Id, Rule, Status (PASS or FAIL)
- Evaluate each row in the Resource × Rule Matrix
- No N/A values; every rule must be PASS or FAIL
- Calculate Overall Guardrail Coverage % = (PASS / total rules evaluated) × 100

5️⃣ Overall Status
- PASS if coverage ≥ 90%, else FAIL

6️⃣ HTML Formatting
- Copy the <html>, <head>, <body> structure from the Sample HTML Template
- Use <h2>, <h3> for headings
- Use <table>, <thead>, <tbody>, <tr>, <th>, <td> for tables
- Do not output Markdown, LaTeX, or code fences
- Return only valid HTML that Jenkins publishHTML can render
"""
    },
    { "role": "user", "content": "Terraform Plan File:\\n" },
    { "role": "user", "content": ${PLAN_FILE_CONTENT} },
    { "role": "user", "content": "Sample HTML File:\\n" },
    { "role": "user", "content": ${SAMPLE_HTML} },
    { "role": "user", "content": "Guardrails Checklist File:\\n" },
    { "role": "user", "content": ${GUARDRAILS_CONTENT} },
    { "role": "user", "content": "Resource × Rule Matrix:\\n" },
    { "role": "user", "content": ${MATRIX_CONTENT} }
  ],
  "max_tokens": 10000,
  "temperature": 0.0
}
EOF

        curl -s -X POST "\${AZURE_API_BASE}/openai/deployments/\${DEPLOYMENT_NAME}/chat/completions?api-version=\${AZURE_API_VERSION}" \\
             -H "Content-Type: application/json" \\
             -H "api-key: \${AZURE_API_KEY}" \\
             -d @"${payloadPath}" > "${responsePath}"

        jq -r '.choices[0].message.content' "${responsePath}" | sed '/^```/d' > "${outputHtmlPath}"
      """
    }
  }

  stage('Publish Report') {
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

    echo "Guardrail Coverage: ${coverage}%"
    env.PIPELINE_DECISION     = coverage >= 90 ? 'APPROVED' : 'REJECTED'
    currentBuild.description  = "Decision: ${env.PIPELINE_DECISION} (Coverage: ${coverage}%)"
  }

  stage('Decision') {
    if (env.PIPELINE_DECISION == 'APPROVED') {
      echo "✅ Pipeline approved. Proceeding..."
    } else {
      echo "❌ Pipeline rejected. Halting..."
    }
  }
}

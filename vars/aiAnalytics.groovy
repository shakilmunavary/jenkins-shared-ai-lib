def call(Map config) {
  def terraformRepo = config.terraformRepo
  def folderName    = config.folderName

  def TMP_DIR    = "${env.WORKSPACE}/tmp-${env.BUILD_ID}"
  def tfDir      = "${TMP_DIR}/terraform-code/${folderName}"
  def matrixPath = "${tfDir}/resource_rule_matrix.txt"

  sh "rm -rf '${TMP_DIR}' && mkdir -p '${TMP_DIR}'"

  stage('Clone Repo 123') {
    dir(TMP_DIR) {
      sh """
        git clone '${terraformRepo}' terraform-code
        git clone 'https://github.com/shakilmunavary/jenkins-shared-ai-lib.git' jenkins-shared-ai-lib
      """
    }
  }

  stage('Terraform Init & Plan') {
    dir(tfDir) {
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
    dir(tfDir) {
      sh """
        set -euo pipefail
        PLAN=tfplan.json
        GUARDRAILS=${TMP_DIR}/jenkins-shared-ai-lib/guardrails/guardrails_v1.txt
        MATRIX=${matrixPath}
        : > "$MATRIX123"

        RESOURCES=\$(jq -r ".resource_changes[].address" "$PLAN")
        echo "Resources detected:"
        echo "\$RESOURCES" | sed "s/^/  - /"

        for RES in \$RESOURCES; do
          TYPE=\$(echo "\$RES" | cut -d"." -f1)
          awk -v type="\$TYPE" '
            \$0 ~ "^Resource Type:[[:space:]]*"type"\$" { inType=1; next }
            /^Resource Type:/ { inType=0 }
            inType && /^[[]/ { print; next }
          ' "\$GUARDRAILS" | while read -r RULELINE; do
            RULEID=\$(echo "\$RULELINE" | sed -n "s/.*Rule ID:[[:space:]]*\\([^]]*\\)].*/\\1/p")
            RULEDESC=\$(awk -v hdr="\$RULELINE" '
              BEGIN {found=0}
              \$0 == hdr {found=1; next}
              found && /^Rule:/ { sub(/^Rule:[[:space:]]*/, "", \$0); print; exit }
            ' "\$GUARDRAILS")
            if [ -z "\$RULEDESC" ]; then RULEDESC="Rule description not found"; fi
            printf "%s\\t%s\\t%s\\n" "\$RES" "\$RULEID" "\$RULEDESC" >> "$MATRIX"
          done
        done
      """
    }
  }

  stage('AI analytics with Azure OpenAI') {
    withCredentials([
      string(credentialsId: 'AZURE_API_KEY',         variable: 'AZURE_API_KEY'),
      string(credentialsId: 'AZURE_API_BASE',        variable: 'AZURE_API_BASE'),
      string(credentialsId: 'AZURE_DEPLOYMENT_NAME', variable: 'DEPLOYMENT_NAME'),
      string(credentialsId: 'AZURE_API_VERSION',     variable: 'AZURE_API_VERSION')
    ]) {
      def tfPlanJsonPath = "${tfDir}/tfplan.json"
      def guardrailsPath = "${TMP_DIR}/jenkins-shared-ai-lib/guardrails/guardrails_v1.txt"
      def outputHtmlPath = "${tfDir}/output.html"
      def payloadPath    = "${tfDir}/payload.json"
      def responsePath   = "${tfDir}/ai_results.raw"

      sh """
        set -euo pipefail

        PLAN_FILE_CONTENT=\$(jq -Rs . < "${tfPlanJsonPath}")
        GUARDRAILS_CONTENT=\$(jq -Rs . < "${guardrailsPath}")
        MATRIX_CONTENT=\$(jq -Rs . < "${matrixPath}")

        cat <<EOF > "${payloadPath}"
{
  "messages": [
    {
      "role": "system",
      "content": "You are a Terraform compliance auditor. For each resource × rule in the matrix, output ONLY one tab-separated line: Resource\\tRuleID\\tRuleDescription\\tPASS or FAIL. Do not generate HTML, Markdown, headings, or explanations."
    },
    { "role": "user", "content": "Terraform Plan File:\\n" },
    { "role": "user", "content": \${PLAN_FILE_CONTENT} },
    { "role": "user", "content": "Guardrails Checklist File:\\n" },
    { "role": "user", "content": \${GUARDRAILS_CONTENT} },
    { "role": "user", "content": "Resource × Rule Matrix:\\n" },
    { "role": "user", "content": \${MATRIX_CONTENT} }
  ],
  "max_tokens": 5000,
  "temperature": 0.0,
  "seed": 42
}
EOF

        curl -s -X POST "\${AZURE_API_BASE}/openai/deployments/\${DEPLOYMENT_NAME}/chat/completions?api-version=\${AZURE_API_VERSION}" \\
             -H "Content-Type: application/json" \\
             -H "api-key: \${AZURE_API_KEY}" \\
             -d @"${payloadPath}" > "${responsePath}"

        jq -r '.choices[0].message.content' "${responsePath}" > "${tfDir}/ai_results.txt"
      """

      // Build HTML deterministically in Groovy
      def aiResults = readFile(matrixPath).split("\\r?\\n")
      def passCount = aiResults.count { it.endsWith("PASS") }
      def failCount = aiResults.count { it.endsWith("FAIL") }
      def total     = passCount + failCount
      def coverage  = total > 0 ? (passCount * 100 / total).toDouble().round(2) : 0.0
      def finalStatus = coverage >= 90 ? "PASS" : "FAIL"

      def htmlReport = """
      <html>
      <head>
        <title>Terraform Compliance Report</title>
        <style>
          body { font-family: Arial, sans-serif; }
          table { border-collapse: collapse; width: 100%; }
          th, td { border: 1px solid #ccc; padding: 8px; text-align: left; }
          th { background-color: #f2f2f2; }
          .pass { color: green; font-weight: bold; }
          .fail { color: red; font-weight: bold; }
        </style>
      </head>
      <body>
        <h2>Guardrail Compliance Summary</h2>
        <table>
          <tr><th>Terraform Resource</th><th>Rule ID</th><th>Rule Description</th><th>Status</th></tr>
      """

      aiResults.each { line ->
        def parts = line.split("\\t")
        if (parts.size() == 4) {
          def resource = parts[0]
          def ruleId   = parts[1]
          def ruleDesc = parts[2]
          def status   = parts[3]
          def cssClass = status.toLowerCase()
          htmlReport += "<tr><td>${resource}</td><td>${ruleId}</td><td>${ruleDesc}</td><td class='${cssClass}'>${status}</td></tr>\n"
        }
      }

      htmlReport += """
        </table>
        <h3>Overall Guardrail Coverage</h3>
        <p>Total Rules Evaluated: ${total}</p>
        <p>PASS: ${passCount}</p>
        <p>FAIL: ${failCount}</p>
        <p>Coverage: ${coverage}%</p>
        <h3>Final Status: ${finalStatus}</h3>
      </body>
      </html>
      """

      writeFile file: outputHtmlPath, text: htmlReport
      archiveArtifacts artifacts: outputHtmlPath, fingerprint: true

      env.PIPELINE_DECISION    = finalStatus == "PASS" ? "APPROVED" : "REJECTED"
      currentBuild.description = "Auto-${env.PIPELINE_DECISION.toLowerCase()} (Coverage: ${coverage}%)"
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

  stage('Decision') {
    if (env.PIPELINE_DECISION == 'APPROVED') {
      echo "✅ Pipeline approved. Proceeding..."
    } else {
      echo "❌ Pipeline rejected. Halting..."
    }
  }
}
